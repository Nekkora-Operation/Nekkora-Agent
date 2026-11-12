package com.nekkora.agent.platform;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.platform.AuthorizationCache.Authorization;
import com.nekkora.agent.platform.AuthorizationCache.Usability;
import com.nekkora.agent.platform.CredentialVault.Link;
import com.nekkora.agent.platform.CredentialVault.Pending;
import com.nekkora.agent.platform.DeliveryStore.Delivery;
import com.nekkora.agent.platform.DeliveryStore.Range;
import com.nekkora.agent.core.FaultPoints;
import com.nekkora.agent.platform.MachineClient.Malformed;
import com.nekkora.agent.platform.MachineClient.NoResponse;
import com.nekkora.agent.platform.MachineClient.Ok;
import com.nekkora.agent.platform.MachineClient.Refused;
import com.nekkora.agent.platform.MachineClient.Result;
import com.nekkora.agent.platform.PlatformStatus.Connectivity;
import com.nekkora.agent.platform.PlatformStatus.LinkState;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Transporte do Agent para a Platform (contrato Platform–Agent 1.0): pareamento e credencial, presença, autorizações,
 * registro e correlação das execuções, relatórios do supervisor e entrega durável dos eventos.
 * <p>
 * Isolamento (contrato, sec. 12.3): o transporte NÃO chama o executor e o executor não chama o transporte. Ele só LÊ os
 * fatos já gravados ({@code estado.json} pelo {@link RunStore}, {@code atividade.jsonl}) e grava o seu próprio progresso
 * em {@code <dados>/plataforma}. Uma falha de rede, da Platform ou da credencial nunca muda o estado de uma execução,
 * nunca relança nada e nunca impede uma execução de começar; o heartbeat não decide nada sobre execuções.
 * <p>
 * Tudo o que fala com a rede roda numa ÚNICA linha de fundo ({@code nekkora-agent-plataforma}, prioridade reduzida). A
 * interface só lê {@link #status()} e pede ações que retornam na hora (nenhuma chamada de rede bloqueia a tela).
 */
public final class PlatformService implements AutoCloseable {
    static final Duration TICK = Duration.ofSeconds(1);
    static final Duration HEARTBEAT = Duration.ofSeconds(60);
    static final Duration AUTHORIZATIONS = Duration.ofMinutes(5);
    /** AGENT-PYTHON-001: consulta dos pedidos de execução (puxados pela máquina; nenhum canal aberto pela Platform). */
    static final Duration RUN_REQUESTS = Duration.ofSeconds(10);
    /** Conectividade só é dada como ativa até este tempo depois da última resposta (o mesmo critério da Platform). */
    static final Duration CONTACT_FRESH = Duration.ofSeconds(180);
    /** Pedidos HTTP no máximo por rodada (a rodada seguinte continua de onde parou). */
    static final int REQUESTS_PER_TICK = 12;
    /** Intenção gravada e não ligada a uma execução é descartada depois disso (a execução não chegou a existir). */
    static final Duration INTENT_WINDOW = Duration.ofMinutes(10);

    /** Diagnóstico para o log do Agent: só transições, contagens e códigos; nunca segredo, código de pareamento nem conteúdo. */
    public interface Diagnostics {
        default void pairingStarted() {}

        default void paired(UUID agentId) {}

        default void pairingRefused(ErrorCode why) {}

        default void rotationStarted() {}

        default void rotated() {}

        default void rotationRefused(ErrorCode why) {}

        default void credentialRefused() {}

        /** T06: vínculo removido desta máquina; quantas execuções ficaram sem entrega e o que a Platform respondeu antes. */
        default void linkRemoved(int undeliveredRuns, ServerView server) {}

        /** T13: desvínculo confirmado pela pessoa e gravado (pedido à Platform a seguir). */
        default void unlinkRequested(int undeliveredRuns, ServerView server) {}

        /** T13: uma tentativa NÃO conclusiva (código curto: SEM_RESPOSTA, CREDENCIAL_RECUSADA, LIMITE…). */
        default void unlinkAttempt(String result, int attempts) {}

        /** T13: desfecho conclusivo; o vínculo antigo saiu desta máquina. */
        default void unlinkConcluded(PlatformStatus.RemoteOutcome outcome, int undeliveredRuns, int attempts) {}

        default void connected() {}

        default void disconnected(ErrorCode why) {}

        default void authorizations(int count, int rejected) {}

        default void runLinked(ExecutionId id) {}

        default void runRegistered(ExecutionId id, boolean created) {}

        default void report(ExecutionId id, ExecutionState state, String outcome) {}

        default void blocked(ExecutionId id, ErrorCode why, Long sequence) {}

        default void delivered(ExecutionId id, long settledThrough) {}

        default void lossDeclared(ExecutionId id, long count) {}

        /** Um ACK aceito: cursores e quantos itens do lote foram gravados agora ou já estavam lá (reenvio sem efeito). */
        default void acked(ExecutionId id, long storedThrough, long settledThrough, int stored, int duplicates) {}

        default void responseRejected(ExecutionId id, String why) {}

        default void storageFailed(ErrorCode why) {}
    }

    /** O que a execução local publica para o transporte (só leitura). */
    public interface ActivityAccess {
        /** Maior sequência já sincronizada no disco de uma coleta aberta; vazio = tudo o que está no arquivo vale. */
        OptionalLong durableThrough(ExecutionId id);
    }

    /** Autorização que pode servir a um pacote, para a pessoa escolher (sec. 6.1). */
    public record AuthorizationChoice(UUID authorizationId, UUID workspaceId, UUID automationVersionId, Instant validUntil, boolean usable, String reason) {}

    /** Resposta imediata de uma ação pedida pela pessoa (a parte de rede segue em segundo plano). */
    public record Action(boolean accepted, ErrorCode problem) {
        static Action ok() {
            return new Action(true, null);
        }

        static Action refused(ErrorCode c) {
            return new Action(false, c);
        }
    }

    private final Path dir;
    private final Supplier<String> configuredUrl;
    private final RunStore runs;
    private final ActivityAccess activity;
    private final SecretStore secrets;
    private final Clock clock;
    private final CredentialVault vault;
    private final AuthorizationCache authorizations;
    private final DeliveryStore deliveries;
    private final RunRequestStore requests;
    private final UnlinkLedger unlinks;
    /** T13: o pedido de desvínculo confirmado e ainda não concluído na Platform ({@code null}: nenhum). */
    private final UnlinkRequestStore unlinkRequests;
    private volatile UnlinkRequestStore.Request unlinking;
    /** T13: há um arquivo de pedido que não se lê: na dúvida o vínculo antigo continua bloqueado e nada é enviado. */
    private volatile boolean unlinkUnreadable;
    private volatile PlatformStatus.RemoteOutcome unlinkConcluded;
    private volatile Instant unlinkConcludedAt;
    private final Backoff unlinkBackoff;
    /** Chamado (na linha do transporte) quando o vínculo antigo termina de fato: o app apaga o que era dele fora do canal. */
    private volatile Runnable onLinkEnded = () -> {};
    /** T06: execuções registradas como não entregues num desvínculo (lidas na abertura e mantidas a cada desvínculo). */
    private volatile Set<ExecutionId> unlinkedUndelivered = Set.of();
    private Instant nextRunRequests = Instant.MIN;
    private final MachineClient client;
    private final Backoff net;
    private final Backoff redeemBackoff;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<ExecutionId> abandonRequests = new ConcurrentLinkedQueue<>();
    /** Execuções cuja cópia local não pode ser apagada pela retenção (entrega pendente do vínculo atual). */
    private final Set<ExecutionId> protectedRuns = ConcurrentHashMap.newKeySet();
    private final Map<ExecutionId, Long> lastSequence = new ConcurrentHashMap<>();
    /** AGENT-PYTHON-001: pastas de execução já vistas SEM pedido de vínculo (com estado.json): não são lidas de novo. */
    private final Set<ExecutionId> withoutLinkRequest = ConcurrentHashMap.newKeySet();
    /**
     * As entregas, lidas uma vez na abertura e mantidas aqui a cada gravação: só este processo grava em
     * {@code entregas/} (a trava de instância única garante), então a memória é a cópia fiel do disco.
     */
    private final Map<ExecutionId, Delivery> known = new ConcurrentHashMap<>();
    private Instant nextHousekeeping = Instant.MIN;
    static final Duration HOUSEKEEPING = Duration.ofMinutes(10);
    private volatile Diagnostics diagnostics = new Diagnostics() {};
    private volatile PlatformStatus status;
    private volatile Instant lastContact;
    private volatile Instant lastSync;
    private volatile Connectivity connectivity = Connectivity.UNKNOWN;
    private volatile ErrorCode problem;
    private volatile boolean contractRefused;
    private volatile boolean authorizationsDue = true;
    private Instant nextHeartbeat = Instant.MIN;
    private Instant nextAuthorizations = Instant.MIN;
    private ScheduledExecutorService exec;
    private boolean closed;

    public PlatformService(Path dir, Supplier<String> configuredUrl, RunStore runs, ActivityAccess activity, SecretStore secrets, HttpTransport http,
                           AtomicWriter writer, Clock clock, String agentVersion, DoubleSupplier random) {
        this.dir = dir;
        this.configuredUrl = configuredUrl;
        this.runs = runs;
        this.activity = activity;
        this.secrets = secrets;
        this.clock = clock;
        this.vault = new CredentialVault(dir, secrets, writer);
        this.authorizations = new AuthorizationCache(dir, writer);
        this.deliveries = new DeliveryStore(dir, writer);
        this.requests = new RunRequestStore(dir, writer);
        this.unlinks = new UnlinkLedger(dir, writer);
        this.unlinkRequests = new UnlinkRequestStore(dir, writer);
        this.client = new MachineClient(http, agentVersion);
        this.net = new Backoff(random);
        this.redeemBackoff = new Backoff(random);
        this.unlinkBackoff = new Backoff(random);
    }

    /** Transporte de produção: HTTPS do JDK e DPAPI no Windows. */
    public static PlatformService system(Path dir, Supplier<String> configuredUrl, RunStore runs, ActivityAccess activity, boolean windows, AtomicWriter writer,
                                         Clock clock, String agentVersion) {
        java.security.SecureRandom r = new java.security.SecureRandom();
        return new PlatformService(dir, configuredUrl, runs, activity, SecretStore.system(dir, windows, writer), new JdkHttpTransport(), writer, clock, agentVersion,
                r::nextDouble);
    }

    public void diagnostics(Diagnostics d) {
        this.diagnostics = d == null ? new Diagnostics() {} : d;
    }

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    /** T13: o que o app faz quando o vínculo antigo termina (ex.: apagar as aprovações locais de pacote). */
    public void onLinkEnded(Runnable r) {
        this.onLinkEnded = r == null ? () -> {} : r;
    }

    /**
     * T13: há um desvínculo confirmado e não concluído (ou um pedido ilegível): nada novo começa pelo vínculo antigo e um
     * novo pareamento é recusado.
     */
    public boolean unlinkPending() {
        return unlinking != null || unlinkUnreadable;
    }

    // ------------------------------------------------------------------------------------------- ciclo de vida

    /** Lê o estado local (vínculo, autorizações guardadas, entregas) sem rede. Chamado na inicialização, fora da EDT. */
    public synchronized void load() {
        try {
            vault.load();
        } catch (IOException e) {
            problem = ErrorCode.PLT_STORAGE_FAILED;
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
        }
        vault.link().ifPresent(l -> authorizations.load(l.agentId()));
        known.clear();
        for (Delivery d : deliveries.list().deliveries()) known.put(d.executionId(), d);
        unlinkedUndelivered = UnlinkLedger.undelivered(unlinks.all());
        unlinking = unlinkRequests.load().orElse(null);
        unlinkUnreadable = unlinking == null && UnlinkRequestStore.present(dir);
        if (unlinkUnreadable) problem = ErrorCode.PLT_STORAGE_FAILED;
        resolveIntent(clock.instant());
        refreshProtection();
        publish();
    }

    public synchronized void start() {
        if (exec != null || closed) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, THREAD);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        exec.scheduleWithFixedDelay(this::tick, 0, TICK.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Pede uma rodada já (depois de uma ação da pessoa); nunca bloqueia. */
    public void wake() {
        ScheduledExecutorService e;
        synchronized (this) {
            e = exec;
        }
        if (e == null) return;
        try {
            e.execute(this::tick);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            // encerrando
        }
    }

    @Override
    public void close() {
        ScheduledExecutorService e;
        synchronized (this) {
            closed = true;
            e = exec;
        }
        if (e == null) return;
        e.shutdownNow();
        try {
            e.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------------- ações da pessoa

    /**
     * Pareamento pedido pela pessoa (sec. 4.2): confere o destino e o formato do código, grava o material protegido e
     * devolve na hora; o resgate segue em segundo plano (repetido com o mesmo material se a resposta se perder).
     */
    public Action pair(String platformUrl, char[] code) {
        try {
            // T13: a operação antiga nunca usa uma credencial nova, nem a nova sobrescreve o material da antiga
            if (unlinkPending()) return Action.refused(ErrorCode.PLT_UNLINK_IN_PROGRESS);
            Optional<Destination> d = Destination.parse(platformUrl);
            if (d.isEmpty()) return Action.refused(ErrorCode.PLT_DESTINATION_INVALID);
            String c = code == null ? null : new String(code).strip();
            try {
                vault.beginPairing(d.get(), c, clock.instant());
            } catch (CredentialVault.Refused r) {
                return Action.refused(r.code());
            } catch (IOException e) {
                diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
                return Action.refused(ErrorCode.PLT_STORAGE_FAILED);
            }
            redeemBackoff.succeeded();
            problem = null;
            diagnostics.pairingStarted();
            publish();
            wake();
            return Action.ok();
        } finally {
            if (code != null) java.util.Arrays.fill(code, '\0');
        }
    }

    /** Rotação com o código gerado pela pessoa na Platform (sec. 4.3). A credencial atual só sai depois da confirmação. */
    public Action rotate(char[] code) {
        try {
            if (unlinkPending()) return Action.refused(ErrorCode.PLT_UNLINK_IN_PROGRESS);
            String c = code == null ? null : new String(code).strip();
            try {
                vault.beginRotation(c, clock.instant());
            } catch (CredentialVault.Refused r) {
                return Action.refused(r.code());
            } catch (IOException e) {
                diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
                return Action.refused(ErrorCode.PLT_STORAGE_FAILED);
            }
            redeemBackoff.succeeded();
            diagnostics.rotationStarted();
            publish();
            wake();
            return Action.ok();
        } finally {
            if (code != null) java.util.Arrays.fill(code, '\0');
        }
    }

    // ------------------------------------------------------------------------------------------- desvínculo (T06)

    /**
     * O que a Platform respondeu à credencial desta máquina, consultada ANTES do desvínculo. Nunca diz que o Worker está
     * livre na Platform: só uma pessoa com permissão revoga o Agent lá (a máquina não tem rota para isso).
     */
    public enum ServerView {
        /** A credencial ainda é aceita: o vínculo continua na Platform até uma pessoa autorizada revogar o Agent. */
        ACCEPTED,
        /** A Platform recusou a credencial ({@code 401}: revogada ou vencida). */
        REFUSED,
        /** Sem resposta, indisponível ou resposta fora do contrato: a Platform não pôde ser consultada. */
        UNREACHABLE,
        /** Não consultada (endereço alterado, versão recusada, resgate em andamento ou consulta não pedida). */
        NOT_CONSULTED
    }

    /** Retrato para a confirmação do desvínculo (identificadores completos: o contrato não traz nomes). */
    public record UnlinkPreview(boolean linked, LinkState state, String host, String organizationId, String agentId, String workerId, int pendingRuns,
                                long pendingEvents, boolean rotationPending, ServerView server) {}

    /**
     * Resultado do desvínculo. {@code done}: o vínculo saiu desta máquina (concluído; {@code remote} diz como a Platform
     * respondeu). {@code pending}: confirmado e gravado, aguardando a Platform (nada novo começa pelo vínculo antigo).
     * {@code queued}: o pedido ficou na linha do transporte (ocupada) e será aplicado em seguida. Senão, {@code problem} diz
     * por que nada mudou.
     */
    public record UnlinkResult(boolean done, boolean queued, ErrorCode problem, int undeliveredRuns, long undeliveredEvents, boolean pending,
                               PlatformStatus.RemoteOutcome remote) {
        public UnlinkResult(boolean done, boolean queued, ErrorCode problem, int undeliveredRuns, long undeliveredEvents) {
            this(done, queued, problem, undeliveredRuns, undeliveredEvents, false, null);
        }

        static UnlinkResult refused(ErrorCode why, int runs, long events) {
            return new UnlinkResult(false, false, why, runs, events);
        }
    }

    /** Espera máxima pela linha do transporte (uma rodada em andamento pode estar numa chamada de até 30 s). */
    static final Duration TRANSPORT_WAIT = Duration.ofSeconds(45);

    /**
     * Antes de confirmar o desvínculo: os envios pendentes do vínculo atual e, se {@code consult} e o vínculo está em uso,
     * uma consulta da credencial à Platform (presença). Um {@code 401} aqui marca a credencial como recusada (como em
     * qualquer chamada). Roda na linha do transporte; chamar fora da EDT.
     */
    public UnlinkPreview previewUnlink(boolean consult) {
        return onTransport(() -> previewNow(consult), () -> preview(ServerView.UNREACHABLE));
    }

    /**
     * Desvincula esta máquina (decisão da pessoa, já confirmada). PLATFORM-AGENT-V2-004 T13 (protocolo:
     * docs/platform-agent-v2-004/t13-desvinculo-remoto.md na API):
     * <ol>
     *   <li>grava o PEDIDO ({@link UnlinkRequestStore}: operação, vínculo original) antes de qualquer envio; a partir daí nada
     *       novo começa pelo vínculo antigo (tela, pedidos da Platform, lançadores externos) e o pareamento é recusado;</li>
     *   <li>confere de novo, DEPOIS de gravar, que nenhuma execução começou ({@code stillIdle} e a intenção pré-início): se
     *       começou, o pedido sai e nada muda (a corrida com um início fica fechada dos dois lados);</li>
     *   <li>grava o registro do desvínculo (entregas não terminadas = NÃO entregues; nunca enviadas por outro vínculo), apaga as
     *       autorizações guardadas e fecha os pedidos de execução locais;</li>
     *   <li>pede o encerramento à Platform (a credencial e o {@code vinculo.json} ficam até a conclusão). Concluído: o vínculo
     *       sai desta máquina. Sem conclusão: fica PENDENTE e o transporte repete a MESMA operação, espaçado, enquanto o Agent
     *       estiver aberto (inclusive depois de reiniciar).</li>
     * </ol>
     * O histórico das execuções e os arquivos de entrega ficam. Roda na linha do transporte (nunca no meio de uma rodada).
     */
    public UnlinkResult unlink(boolean evenWithPending, ServerView observed, java.util.function.BooleanSupplier stillIdle) {
        return onTransport(() -> unlinkNow(evenWithPending, observed == null ? ServerView.NOT_CONSULTED : observed, stillIdle),
                () -> new UnlinkResult(false, true, null, 0, 0));
    }

    /** Sem conferência de execução (testes do canal; o app confere pelo coordenador do desvínculo). */
    public UnlinkResult unlink(boolean evenWithPending, ServerView observed) {
        return unlink(evenWithPending, observed, () -> true);
    }

    /** Remoção do vínculo mesmo com envios pendentes (registrados como não entregues). */
    public Action removeLocalLink() {
        UnlinkResult r = unlink(true, ServerView.NOT_CONSULTED);
        return r.done() || r.queued() || r.pending() ? Action.ok() : Action.refused(r.problem());
    }

    private UnlinkPreview previewNow(boolean consult) {
        Optional<Link> l = vault.link();
        Optional<Pending> p = vault.pending();
        ServerView server = ServerView.NOT_CONSULTED;
        LinkState state = build().link();
        if (l.isPresent()) {
            if (state == LinkState.REFUSED) server = ServerView.REFUSED; // já recusada: nenhuma chamada nova (D-AP-9)
            else if (consult && state == LinkState.PAIRED && p.isEmpty()) server = probe(l.get());
        }
        return preview(server);
    }

    private UnlinkPreview preview(ServerView server) {
        Optional<Link> l = vault.link();
        Optional<Pending> p = vault.pending();
        PlatformStatus st = build();
        return new UnlinkPreview(l.isPresent(), st.link(), st.host(), st.organizationId(), st.agentId(), st.workerId(), st.pendingRuns(), st.pendingEvents(),
                p.isPresent() && p.get().kind() == CredentialVault.Kind.ROTATION, server);
    }

    /** Uma chamada autenticada (presença) só para saber se a credencial ainda vale. */
    private ServerView probe(Link l) {
        Optional<String> token = vault.token();
        if (token.isEmpty()) return ServerView.NOT_CONSULTED;
        Instant now = clock.instant();
        Result<MachineClient.Presence> r = client.heartbeat(l.destination(), token.get());
        if (r instanceof Ok<MachineClient.Presence> ok) {
            contact(now);
            authorizations.observe(ok.value().serverTime());
            nextHeartbeat = now.plus(HEARTBEAT);
            publish();
            return ServerView.ACCEPTED;
        }
        if (r instanceof Refused<MachineClient.Presence> ref && ref.status() == 401) {
            contact(now);
            credentialRefused(now);
            publish();
            return ServerView.REFUSED;
        }
        return ServerView.UNREACHABLE;
    }

    private UnlinkResult unlinkNow(boolean evenWithPending, ServerView observed, java.util.function.BooleanSupplier stillIdle) {
        if (unlinkUnreadable) return UnlinkResult.refused(ErrorCode.PLT_STORAGE_FAILED, 0, 0);
        if (unlinking != null) return attemptUnlink(clock.instant()); // já confirmado: só a MESMA operação, de novo
        Optional<Link> ol = vault.link();
        if (ol.isEmpty()) return UnlinkResult.refused(ErrorCode.PLT_NOT_PAIRED, 0, 0);
        Link l = ol.get();
        Instant now = clock.instant();
        List<UnlinkLedger.Undelivered> left = new ArrayList<>();
        for (Delivery d : ordered()) {
            if (d.complete() || !d.agentId().equals(l.agentId())) continue;
            long events = Math.max(0, lastSequence.getOrDefault(d.executionId(), d.settledThrough()) - d.settledThrough());
            left.add(new UnlinkLedger.Undelivered(d.executionId(), d.settledThrough(), events));
        }
        long leftEvents = left.stream().mapToLong(UnlinkLedger.Undelivered::pendingEvents).sum();
        // nada é descartado em silêncio: com envio pendente, só a escolha explícita "Desvincular mesmo assim" segue
        if (!left.isEmpty() && !evenWithPending) return UnlinkResult.refused(ErrorCode.PLT_UNLINK_PENDING, left.size(), leftEvents);
        // uma execução sendo iniciada pela tela (intenção pré-início gravada) conta como em andamento
        if (deliveries.intent().isPresent()) return UnlinkResult.refused(ErrorCode.PLT_UNLINK_RUNNING, left.size(), leftEvents);
        List<RunRequestStore.Request> open = new ArrayList<>();
        List<RunRequestStore.Request> launchedReqs = new ArrayList<>();
        for (RunRequestStore.Request r : requests.all()) {
            if (!r.agentId().equals(l.agentId())) continue;
            if (r.stage() == RunRequestStore.Stage.LAUNCHED) launchedReqs.add(r);
            else if (r.stage() != RunRequestStore.Stage.CLOSED) open.add(r);
        }
        // pedidos aceitos e INICIADOS cujo registro pode não ter chegado: a Platform não os marca como "não iniciados"
        List<UUID> launched = launchedReqs.stream().sorted(java.util.Comparator.comparing(RunRequestStore.Request::updatedAt).reversed())
                .limit(UnlinkRequestStore.MAX_LAUNCHED).map(RunRequestStore.Request::runRequestId).toList();
        UnlinkRequestStore.Request req = new UnlinkRequestStore.Request(UUID.randomUUID(), l.destination().origin(), l.organizationId(), l.agentId(),
                l.workerId(), l.credentialId(), now, launched, 0, null, null);
        // 1. o PEDIDO, antes de qualquer envio e de qualquer remoção (uma queda aqui: no reinício, o pedido é retomado)
        try {
            unlinkRequests.save(req);
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
            return UnlinkResult.refused(ErrorCode.PLT_STORAGE_FAILED, left.size(), leftEvents);
        }
        unlinking = req;
        // 2. conferência DEPOIS de gravar: um início que passou pela trava antes do pedido aparece aqui; nesse caso nada muda
        if (!stillIdle.getAsBoolean() || deliveries.intent().isPresent()) {
            withdraw();
            return UnlinkResult.refused(ErrorCode.PLT_UNLINK_RUNNING, left.size(), leftEvents);
        }
        // 3. o registro do desvínculo (os não entregues ficam marcados como tal) e o fim do que era deste vínculo aqui
        UnlinkLedger.Entry entry = new UnlinkLedger.Entry(l.agentId(), l.organizationId(), l.workerId(), l.destination().origin(), now, PENDING_SERVER, left,
                open.size(), false);
        try {
            unlinks.save(entry);
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
            withdraw();
            return UnlinkResult.refused(ErrorCode.PLT_STORAGE_FAILED, left.size(), leftEvents);
        }
        Set<ExecutionId> marked = new java.util.HashSet<>(unlinkedUndelivered);
        for (UnlinkLedger.Undelivered u : left) marked.add(u.executionId());
        unlinkedUndelivered = Set.copyOf(marked);
        ErrorCode partial = null;
        try {
            authorizations.clear();
        } catch (IOException e) {
            // o arquivo ficou, mas o pedido gravado já bloqueia o lançador (LocalGate) e a tela
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
            partial = ErrorCode.PLT_STORAGE_FAILED;
        }
        for (RunRequestStore.Request r : open) {
            try {
                requests.save(r.with(RunRequestStore.Stage.CLOSED, r.decision(), r.reason(), r.executionId(), "UNLINKED", now));
            } catch (IOException e) {
                diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED); // o pedido gravado já impede decidir ou lançar
            }
        }
        abandonRequests.clear();
        unlinkBackoff.succeeded();
        problem = partial;
        diagnostics.unlinkRequested(left.size(), observed);
        publish();
        // 4. o pedido à Platform, já (a mesma operação é repetida até uma resposta conclusiva). Com uma renovação de credencial
        //    em voo, primeiro ela termina (próximas rodadas): o desvínculo sai com a credencial que valer
        Optional<Pending> rp = vault.pending();
        if (rp.isPresent() && rp.get().kind() == CredentialVault.Kind.ROTATION) {
            wake();
            return new UnlinkResult(false, false, null, left.size(), leftEvents, true, null);
        }
        return attemptUnlink(now);
    }

    /** Valor de {@code server} no registro enquanto a Platform não confirmou o desvínculo. */
    static final String PENDING_SERVER = "PENDING";

    /** O pedido sai (nada mudou além dele): uma execução começou entre a confirmação e a gravação, ou o registro falhou. */
    private void withdraw() {
        unlinking = null;
        try {
            unlinkRequests.delete();
        } catch (IOException e) {
            // o arquivo ficou: na dúvida, o vínculo antigo continua bloqueado (sem envio) até a pessoa resolver
            unlinkUnreadable = true;
            problem = ErrorCode.PLT_STORAGE_FAILED;
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
        }
        publish();
    }

    /**
     * Uma tentativa da operação pendente. Conclusivo: {@code 200} desta operação (inclusive na repetição depois de uma
     * resposta perdida), {@code 409} "já encerrado" (pela administração ou por outra operação desta máquina) e {@code 404}
     * (Platform sem a rota). Todo o resto, inclusive um {@code 401} isolado, NÃO prova o desvínculo: fica pendente e o
     * transporte repete a mesma operação, espaçado (2 s dobrando até 5 min, com o {@code Retry-After}).
     */
    private UnlinkResult attemptUnlink(Instant now) {
        UnlinkRequestStore.Request u = unlinking;
        if (u == null) return UnlinkResult.refused(ErrorCode.PLT_NOT_PAIRED, 0, 0);
        UnlinkLedger.Entry ledger = unlinks.all().get(u.agentId());
        int runs = ledger == null ? 0 : ledger.undelivered().size();
        long events = ledger == null ? 0 : ledger.undeliveredEvents();
        Optional<Link> ol = vault.link();
        Optional<String> token = vault.tokenForUnlink();
        PlatformStatus.RemoteOutcome done = null;
        String why;
        if (ol.isEmpty() || token.isEmpty() || !ol.get().agentId().equals(u.agentId())) {
            // o material da credencial não está legível agora: sem prova para enviar (nunca outra credencial)
            why = "SEM_CREDENCIAL";
            unlinkBackoff.failed(now, Optional.empty());
        } else {
            Result<MachineClient.Unlinked> r = client.unlink(ol.get().destination(), token.get(), u.operationId(), u.agentId(), u.launchedRunRequests());
            if (r instanceof Ok<MachineClient.Unlinked> && FaultPoints.reached(FaultPoints.Point.UNLINK_RESPONSE_RECEIVED)) r = new NoResponse<>();
            if (r instanceof Ok<MachineClient.Unlinked>) {
                contact(now);
                done = PlatformStatus.RemoteOutcome.CONFIRMED;
                why = null;
            } else if (r instanceof Refused<MachineClient.Unlinked> ref) {
                contact(now);
                if (ref.status() == 409 && "link_ended_by_administration".equals(ref.reason())) done = PlatformStatus.RemoteOutcome.ENDED_BY_ADMINISTRATION;
                else if (ref.status() == 409 && "link_ended_by_machine".equals(ref.reason())) done = PlatformStatus.RemoteOutcome.ENDED_BY_MACHINE;
                else if (ref.status() == 404) done = PlatformStatus.RemoteOutcome.UNSUPPORTED;
                why = switch (ref.status()) {
                    case 401 -> "CREDENCIAL_RECUSADA";
                    case 429 -> "LIMITE";
                    default -> ref.status() >= 500 ? "INDISPONIVEL" : "RECUSA_" + ref.status();
                };
                if (done == null) unlinkBackoff.failed(now, ref.retryAfterSeconds());
            } else if (r instanceof Malformed<MachineClient.Unlinked>) {
                contact(now);
                why = "RESPOSTA_INVALIDA";
                unlinkBackoff.failed(now, Optional.empty());
            } else {
                connectivity = Connectivity.DISCONNECTED;
                why = "SEM_RESPOSTA";
                unlinkBackoff.failed(now, Optional.empty());
            }
        }
        if (done != null) return conclude(u, done, now, runs, events);
        UnlinkRequestStore.Request next = u.attempted(now, why);
        unlinking = next;
        try {
            unlinkRequests.save(next);
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED); // só a contagem de tentativas: a operação é a mesma
        }
        diagnostics.unlinkAttempt(why, next.attempts());
        publish();
        return new UnlinkResult(false, false, null, runs, events, true, null);
    }

    /** A Platform respondeu de forma conclusiva: o vínculo antigo sai desta máquina (e só então o pedido). */
    private UnlinkResult conclude(UnlinkRequestStore.Request u, PlatformStatus.RemoteOutcome outcome, Instant now, int runs, long events) {
        UnlinkLedger.Entry prev = unlinks.all().get(u.agentId());
        UnlinkLedger.Entry entry = new UnlinkLedger.Entry(u.agentId(), u.organizationId(), u.workerId(), u.origin(), prev == null ? u.confirmedAt() : prev.unlinkedAt(),
                outcome.name(), prev == null ? List.of() : prev.undelivered(), prev == null ? 0 : prev.runRequestsClosed(), prev != null && prev.intentDiscarded());
        ErrorCode partial = null;
        try {
            unlinks.save(entry);
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
            partial = ErrorCode.PLT_STORAGE_FAILED;
        }
        Optional<Link> l = vault.link();
        if (l.isPresent() && l.get().agentId().equals(u.agentId())) {
            try {
                vault.remove();
            } catch (IOException e) {
                diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
                partial = ErrorCode.PLT_STORAGE_FAILED;
            }
        }
        try {
            authorizations.clear();
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
            partial = ErrorCode.PLT_STORAGE_FAILED;
        }
        try {
            unlinkRequests.delete();
        } catch (IOException e) {
            // ficou: no reinício a MESMA operação é repetida e a Platform responde igual (sem efeito novo)
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
            partial = ErrorCode.PLT_STORAGE_FAILED;
        }
        unlinking = null;
        unlinkConcluded = outcome;
        unlinkConcludedAt = now;
        // memória da identidade anterior
        withoutLinkRequest.clear();
        abandonRequests.clear();
        authorizationsDue = true;
        nextHeartbeat = Instant.MIN;
        nextAuthorizations = Instant.MIN;
        nextRunRequests = Instant.MIN;
        net.succeeded();
        redeemBackoff.succeeded();
        unlinkBackoff.succeeded();
        connectivity = Connectivity.UNKNOWN;
        lastContact = null;
        lastSync = null;
        problem = partial;
        contractRefused = false;
        refreshProtection();
        try {
            onLinkEnded.run();
        } catch (RuntimeException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
        }
        diagnostics.unlinkConcluded(outcome, runs, u.attempts() + 1);
        publish();
        return new UnlinkResult(true, false, partial, runs, events, false, outcome);
    }

    /** Executa na linha do transporte (serializado com as rodadas); sem linha (testes) ou já nela: aqui mesmo. */
    private <T> T onTransport(java.util.concurrent.Callable<T> work, Supplier<T> onTimeout) {
        ScheduledExecutorService e;
        synchronized (this) {
            e = exec;
        }
        try {
            if (e == null || Thread.currentThread().getName().equals(THREAD)) return work.call();
            return e.submit(work).get(TRANSPORT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException x) {
            return onTimeout.get();
        } catch (java.util.concurrent.RejectedExecutionException x) {
            throw new IllegalStateException("transporte encerrado", x);
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido", x);
        } catch (java.util.concurrent.ExecutionException x) {
            throw x.getCause() instanceof RuntimeException r ? r : new IllegalStateException(x.getCause());
        } catch (RuntimeException x) {
            throw x;
        } catch (Exception x) {
            throw new IllegalStateException(x);
        }
    }

    private static final String THREAD = "nekkora-agent-plataforma";

    /** Pede nova leitura das autorizações (e tentativa já, se houver espera por falha). */
    public void refreshNow() {
        authorizationsDue = true;
        net.succeeded();
        wake();
    }

    /**
     * A pessoa desiste dos eventos em que a entrega parou ({@code INVALID} ou lacuna sem perda registrada): eles passam a
     * perda definitiva e são declarados (sec. 8.3). Aplicado na linha de fundo, na próxima rodada.
     */
    public Action abandonBlocked(ExecutionId id) {
        Optional<Delivery> d = Optional.ofNullable(known.get(id));
        if (d.isEmpty() || !d.get().blocked() || d.get().blockedSequence() == null) return Action.refused(ErrorCode.PLT_PAYLOAD_INVALID);
        abandonRequests.add(id);
        wake();
        return Action.ok();
    }

    // ------------------------------------------------------------------------------------------- autorização e vínculo da execução

    /**
     * D-AP-1: a pré-escolha da janela de execução. Exatamente uma autorização válida = ela; nenhuma ou mais de uma = nenhuma
     * ("Não registrar" fica escolhido e ninguém escolhe pela pessoa entre várias).
     */
    public static Optional<UUID> preselected(List<AuthorizationChoice> choices) {
        List<AuthorizationChoice> usable = choices.stream().filter(AuthorizationChoice::usable).toList();
        return usable.size() == 1 ? Optional.of(usable.get(0).authorizationId()) : Optional.empty();
    }

    /** Autorizações guardadas que citam esta impressão (forma do Agent, {@code sha256:…}), com a situação de cada uma. */
    public List<AuthorizationChoice> authorizationsFor(String agentFingerprint) {
        Optional<String> fp = PackageFingerprint.wire(agentFingerprint);
        Optional<Link> l = vault.link();
        if (fp.isEmpty() || l.isEmpty() || l.get().refused()) return List.of();
        List<AuthorizationChoice> out = new ArrayList<>();
        for (Map.Entry<Authorization, Usability> e : authorizations.forFingerprint(fp.get(), clock.instant()).entrySet()) {
            Authorization z = e.getKey();
            String reason = switch (e.getValue()) {
                case USABLE -> null;
                case EXPIRED -> "VENCIDA";
                case RULE_UNKNOWN -> "REGRA_DESCONHECIDA";
            };
            out.add(new AuthorizationChoice(z.authorizationId(), z.workspaceId(), z.automationVersionId(), z.validUntil(), e.getValue() == Usability.USABLE, reason));
        }
        return out;
    }

    /**
     * A pessoa escolheu iniciar sob esta autorização: confere de novo (vínculo atual, impressão igual, regra conhecida, no
     * prazo pelo relógio local) e grava a intenção ANTES do início. Recusa: a execução pode seguir só local.
     */
    public Action prepare(UUID authorizationId, String agentFingerprint) {
        Optional<String> fp = PackageFingerprint.wire(agentFingerprint);
        Optional<Link> l = vault.link();
        if (l.isEmpty()) return Action.refused(ErrorCode.PLT_NOT_PAIRED);
        if (unlinkPending()) return Action.refused(ErrorCode.PLT_UNLINK_REMOTE_PENDING); // T13: nada novo pelo vínculo antigo
        // credencial recusada: nada novo é registrado por este vínculo (a execução pode seguir só local)
        if (l.get().refused()) return Action.refused(ErrorCode.PLT_CREDENTIAL_REFUSED);
        Optional<Authorization> z = authorizations.find(authorizationId);
        if (fp.isEmpty() || z.isEmpty() || !fp.get().equals(z.get().fingerprint()) || !PackageFingerprint.known(z.get().rule())
                || !authorizations.effective(clock.instant()).isBefore(z.get().validUntil())) {
            return Action.refused(ErrorCode.PLT_AUTHORIZATION_REFUSED);
        }
        try {
            deliveries.saveIntent(new DeliveryStore.Intent(l.get().agentId(), authorizationId, fp.get(), clock.instant()));
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
            return Action.refused(ErrorCode.PLT_STORAGE_FAILED);
        }
        // T13: confere DEPOIS de gravar a intenção (o desvínculo grava o pedido e confere a intenção): um dos dois desiste
        if (unlinkPending()) {
            discardIntent();
            return Action.refused(ErrorCode.PLT_UNLINK_REMOTE_PENDING);
        }
        return Action.ok();
    }

    /** A execução iniciada a partir da intenção gravada: vira uma entrega (o registro na Platform segue em segundo plano). */
    public void link(ExecutionId id) {
        Optional<DeliveryStore.Intent> i = deliveries.intent();
        if (i.isEmpty()) return;
        Optional<ExecutionRecord> r = runs.record(id);
        if (r.isPresent() && PackageFingerprint.wire(r.get().fingerprint()).map(i.get().fingerprint()::equals).orElse(false)) {
            createDelivery(id, i.get());
        }
        try {
            deliveries.clearIntent();
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
        }
        wake();
    }

    /** Nada foi iniciado (recusa antes do lançamento): a intenção sai sem virar entrega. */
    public void discardIntent() {
        try {
            deliveries.clearIntent();
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
        }
    }

    private void createDelivery(ExecutionId id, DeliveryStore.Intent i) {
        createDelivery(id, i.agentId(), i.authorizationId(), i.fingerprint());
    }

    private void createDelivery(ExecutionId id, UUID agentId, UUID authorizationId, String fingerprint) {
        if (known.containsKey(id)) return;
        try {
            Delivery d = Delivery.linked(id, agentId, authorizationId, fingerprint, clock.instant());
            deliveries.save(d);
            known.put(id, d);
            protectedRuns.add(id);
            diagnostics.runLinked(id);
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
        }
    }

    /**
     * Queda entre gravar a intenção e ligar a execução: liga à ÚNICA execução local com a mesma impressão criada depois
     * da intenção (e dentro da janela); nenhuma ou mais de uma: a intenção é descartada (nada é presumido).
     */
    private void resolveIntent(Instant now) {
        Optional<DeliveryStore.Intent> i = deliveries.intent();
        if (i.isEmpty()) return;
        DeliveryStore.Intent in = i.get();
        List<ExecutionRecord> candidates = new ArrayList<>();
        if (in.at() != null) {
            for (ExecutionRecord r : runs.all()) {
                if (r.created().isBefore(in.at()) || r.created().isAfter(in.at().plus(INTENT_WINDOW))) continue;
                if (!PackageFingerprint.wire(r.fingerprint()).map(in.fingerprint()::equals).orElse(false)) continue;
                if (r.state() == ExecutionState.BLOCKED && r.started() == null) continue; // nada foi lançado
                candidates.add(r);
            }
        }
        if (candidates.size() == 1) createDelivery(candidates.get(0).id(), in);
        try {
            deliveries.clearIntent();
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
        }
    }

    /**
     * AGENT-PYTHON-001: execuções iniciadas FORA da janela (lançador do Python externo; pedido da Platform) trazem, na própria
     * pasta, o pedido de vínculo ({@link LocalGate#LINK_REQUEST}) gravado ANTES do registro da execução. A entrega nasce aqui,
     * só quando o {@code estado.json} já existe e só para o vínculo atual (outro Agent: ignorado). Só lê.
     */
    private void adoptLinkRequests(Link l, Instant now) {
        java.io.File[] dirs = runs.root().toFile().listFiles(java.io.File::isDirectory);
        if (dirs == null) return;
        for (java.io.File f : dirs) {
            if (!ExecutionId.valid(f.getName())) continue;
            ExecutionId id = new ExecutionId(f.getName());
            if (known.containsKey(id) || withoutLinkRequest.contains(id)) continue;
            Path dir = f.toPath();
            boolean hasRecord = Files.isRegularFile(dir.resolve("estado.json"));
            Optional<LocalGate.LinkRequest> req = LocalGate.readLinkRequest(dir);
            if (req.isEmpty()) {
                if (hasRecord && !Files.exists(dir.resolve(LocalGate.LINK_REQUEST))) withoutLinkRequest.add(id);
                continue;
            }
            if (!hasRecord || !req.get().agentId().equals(l.agentId())) continue;
            createDelivery(id, req.get().agentId(), req.get().authorizationId(), req.get().fingerprint());
        }
    }

    // ------------------------------------------------------------------------------------------- pedido de execução (030)

    /** O que o coordenador do app vê de um pedido (sem rede). {@code fingerprint}: forma do Agent ({@code sha256:…}). */
    public record RunRequestView(UUID runRequestId, UUID authorizationId, UUID automationVersionId, String fingerprint, String rule, Instant validUntil,
                                 String stage, String decision, String reason, String executionId) {}

    /** Pedidos do vínculo atual, como estão gravados nesta máquina. */
    public List<RunRequestView> runRequests() {
        Optional<Link> l = vault.link();
        if (l.isEmpty()) return List.of();
        List<RunRequestView> out = new ArrayList<>();
        for (RunRequestStore.Request r : requests.all()) {
            if (!r.agentId().equals(l.get().agentId())) continue;
            out.add(new RunRequestView(r.runRequestId(), r.authorizationId(), r.automationVersionId(), "sha256:" + r.fingerprint(), r.rule(), r.validUntil(),
                    r.stage().name(), r.decision(), r.reason(), r.executionId()));
        }
        return out;
    }

    /**
     * A decisão desta máquina sobre um pedido RECEBIDO: gravada ANTES do envio (aceitar reserva o {@code agentExecutionId}).
     * Uma segunda decisão sobre o mesmo pedido é recusada (o pedido reentregue nunca gera outra).
     */
    public synchronized Action decideRunRequest(UUID id, String decision, String reason, ExecutionId reserved) {
        Optional<RunRequestStore.Request> r = requests.get(id);
        if (r.isEmpty() || r.get().stage() != RunRequestStore.Stage.RECEIVED) return Action.refused(ErrorCode.EXT_REQUEST_REFUSED);
        if ("ACCEPTED".equals(decision) == (reserved == null)) return Action.refused(ErrorCode.EXT_REQUEST_REFUSED);
        try {
            requests.save(r.get().with(RunRequestStore.Stage.DECIDED, decision, reason, reserved == null ? null : reserved.value(), null, clock.instant()));
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
            return Action.refused(ErrorCode.PLT_STORAGE_FAILED);
        }
        wake();
        return Action.ok();
    }

    /** O lançamento de um pedido CONFIRMADO foi tentado (a pasta da execução existe): nada mais a decidir. */
    public synchronized void runRequestLaunched(UUID id) {
        requests.get(id).filter(r -> r.stage() == RunRequestStore.Stage.CONFIRMED).ifPresent(r -> {
            try {
                requests.save(r.with(RunRequestStore.Stage.LAUNCHED, r.decision(), r.reason(), r.executionId(), r.serverOutcome(), clock.instant()));
            } catch (IOException e) {
                diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
            }
        });
    }

    /** Aceito e confirmado, mas o processo comprovadamente não foi criado: vira NOT_STARTED (enviado; nunca relançado). */
    public synchronized void runRequestNotStarted(UUID id) {
        requests.get(id).filter(r -> r.stage() == RunRequestStore.Stage.CONFIRMED).ifPresent(r -> {
            try {
                requests.save(r.with(RunRequestStore.Stage.DECIDED, "NOT_STARTED", "LAUNCH_NOT_ATTEMPTED", r.executionId(), null, clock.instant()));
            } catch (IOException e) {
                diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
            }
        });
        wake();
    }

    /** A autorização guardada serve a este pacote agora (vínculo atual, mesma impressão e regra, no prazo com o piso do relógio)? */
    public boolean authorizationUsable(UUID authorizationId, String fingerprintHex, String rule) {
        Optional<Link> l = vault.link();
        if (l.isEmpty() || l.get().refused() || unlinkPending()) return false;
        Optional<Authorization> z = authorizations.find(authorizationId);
        return z.isPresent() && fingerprintHex.equals(z.get().fingerprint()) && rule.equals(z.get().rule()) && PackageFingerprint.known(rule)
                && authorizations.effective(clock.instant()).isBefore(z.get().validUntil());
    }

    /** O pedido de vínculo que o lançamento de um pedido grava na pasta da execução (antes do STARTING). */
    public Optional<LocalGate.LinkRequest> linkRequestFor(UUID runRequestId) {
        Optional<Link> l = vault.link();
        Optional<RunRequestStore.Request> r = requests.get(runRequestId);
        if (unlinkPending() || l.isEmpty() || r.isEmpty() || !r.get().agentId().equals(l.get().agentId())) return Optional.empty();
        return Optional.of(new LocalGate.LinkRequest(l.get().agentId(), r.get().authorizationId(), r.get().sourceKind(), r.get().fingerprint(), runRequestId,
                clock.instant()));
    }

    private boolean pollRunRequests(Link l, String token, Instant now, int[] budget) {
        budget[0]--;
        Result<MachineClient.PendingRequests> res = client.runRequests(l.destination(), token);
        if (!(res instanceof Ok<MachineClient.PendingRequests> ok)) return handleFailure(res, now, null);
        contact(now);
        nextRunRequests = now.plus(RUN_REQUESTS);
        for (MachineClient.PendingRequest p : ok.value().items()) {
            if (requests.get(p.runRequestId()).isPresent()) continue; // já conhecido: a decisão (se houver) é desta máquina
            RunRequestStore.Stage stage = RunRequestStore.Stage.RECEIVED;
            String decision = null, reason = null, exec = null;
            if ("ACCEPTED".equals(p.state())) {
                // aceito pela Platform, mas sem registro local (dados locais perdidos): nunca lançado de novo. Se a pasta da execução
                // existe, o lançamento foi tentado; senão, o processo comprovadamente não foi criado
                exec = p.executionId();
                if (exec != null && Files.exists(runs.dir(new ExecutionId(exec)))) {
                    stage = RunRequestStore.Stage.LAUNCHED;
                    decision = "ACCEPTED";
                } else {
                    stage = RunRequestStore.Stage.DECIDED;
                    decision = "NOT_STARTED";
                    reason = "LAUNCH_NOT_ATTEMPTED";
                }
            }
            try {
                requests.save(new RunRequestStore.Request(p.runRequestId(), l.agentId(), p.authorizationId(), p.automationVersionId(),
                        p.sourceKind() == null ? AuthorizationCache.STUDIO_PACKAGE : p.sourceKind(), p.fingerprint(), p.rule(), p.validUntil(), now, stage, decision,
                        reason, exec, null, now));
            } catch (IOException e) {
                diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
                return false;
            }
        }
        publish();
        return true;
    }

    private boolean sendDecisions(Link l, String token, Instant now, int[] budget) {
        for (RunRequestStore.Request r : requests.all()) {
            if (r.stage() != RunRequestStore.Stage.DECIDED || !r.agentId().equals(l.agentId())) continue;
            if (budget[0]-- <= 0) return true;
            Result<MachineClient.Decision> res = client.decide(l.destination(), token, r.runRequestId(), r.decision(),
                    "ACCEPTED".equals(r.decision()) ? null : r.reason(), "REFUSED".equals(r.decision()) ? null : r.executionId());
            RunRequestStore.Stage next;
            String server;
            if (res instanceof Ok<MachineClient.Decision>
                    && com.nekkora.agent.core.FaultPoints.reached(com.nekkora.agent.core.FaultPoints.Point.DECISION_RESPONSE_RECEIVED)) {
                // ponto nomeado (sem efeito na distribuição normal): resposta tratada como perdida; a decisão gravada é reenviada
                res = new MachineClient.NoResponse<>();
            }
            if (res instanceof Ok<MachineClient.Decision> ok) {
                contact(now);
                server = ok.value().outcome();
                boolean applied = "APPLIED".equals(server) || "UNCHANGED".equals(server);
                // só uma aceitação CONFIRMADA libera o lançamento; vencido, revogado ou conflito: nada é lançado
                next = applied && "ACCEPTED".equals(r.decision()) ? RunRequestStore.Stage.CONFIRMED : RunRequestStore.Stage.CLOSED;
            } else if (res instanceof Refused<MachineClient.Decision> rf && (rf.status() == 404 || rf.status() == 400)) {
                server = rf.status() == 404 ? "NOT_FOUND" : "INVALID";
                next = RunRequestStore.Stage.CLOSED;
            } else {
                return handleFailure(res, now, null);
            }
            try {
                requests.save(r.with(next, r.decision(), r.reason(), r.executionId(), server, now));
            } catch (IOException e) {
                diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
                return false;
            }
        }
        return true;
    }

    /** O pedido da Platform que originou a execução (se houver), lido do pedido de vínculo gravado na pasta dela. */
    private UUID runRequestOf(ExecutionId id) {
        return LocalGate.readLinkRequest(runs.dir(id)).map(LocalGate.LinkRequest::runRequestId).orElse(null);
    }

    // ------------------------------------------------------------------------------------------- consultas (sem rede)

    public PlatformStatus status() {
        PlatformStatus s = status;
        return s != null ? s : build();
    }

    /** Envio de uma execução (para a tela da execução). */
    public DeliveryView delivery(ExecutionId id) {
        Optional<Delivery> o = Optional.ofNullable(known.get(id));
        if (o.isEmpty()) return DeliveryView.notLinked();
        Delivery d = o.get();
        Optional<Link> l = vault.link();
        DeliveryView.State st;
        if (l.isEmpty() || !l.get().agentId().equals(d.agentId())) {
            st = unlinkedUndelivered.contains(id) ? DeliveryView.State.UNLINKED_UNDELIVERED : DeliveryView.State.OTHER_LINK;
        }
        else if (d.complete()) st = DeliveryView.State.DELIVERED;
        else if (d.blocked()) st = DeliveryView.State.BLOCKED;
        else if (d.runId() == null) st = DeliveryView.State.WAITING_REGISTRATION;
        else st = DeliveryView.State.PENDING;
        return new DeliveryView(st, d.settledThrough(), d.storedThrough(), d.declaredCount(), code(d.blockedCode()), d.blockedSequence(), d.reportConflict());
    }

    /**
     * A retenção local pode apagar a cópia desta execução? Não, enquanto houver entrega pendente pelo vínculo atual (sec.
     * 8.3: nada é descartado antes do ACK). Seguro para qualquer linha.
     */
    public boolean retains(ExecutionId id) {
        return protectedRuns.contains(id);
    }

    // ------------------------------------------------------------------------------------------- linha de fundo

    void tick() {
        try {
            Instant now = clock.instant();
            applyAbandons(now);
            housekeeping(now);
            // T13: com desvínculo pendente, só a operação de desvínculo sai (nada de presença, pedidos ou entregas do vínculo)
            if (unlinkUnreadable) return;
            if (unlinking != null) {
                // uma renovação de credencial em voo termina (ou é abandonada) antes: a credencial antiga pode já ter sido
                // trocada na Platform, e o desvínculo usa a que valer
                Optional<Pending> rp = vault.pending();
                if (rp.isPresent() && rp.get().kind() == CredentialVault.Kind.ROTATION) {
                    if (redeemBackoff.ready(now)) redeem(rp.get(), now);
                    return;
                }
                if (unlinkBackoff.ready(now)) attemptUnlink(now);
                return;
            }
            Optional<Pending> p = vault.pending();
            if (p.isPresent()) {
                if (redeemBackoff.ready(now)) redeem(p.get(), now);
                return;
            }
            Optional<Link> ol = vault.link();
            if (ol.isEmpty() || ol.get().refused() || contractRefused) return;
            Link l = ol.get();
            adoptLinkRequests(l, now); // local, sem rede: a entrega nasce (e protege a cópia local) mesmo offline
            Optional<Destination> cfg = Destination.parse(configuredUrl.get());
            if (cfg.isEmpty() || !cfg.get().equals(l.destination())) return; // DESTINATION_CHANGED: nada é enviado
            Optional<String> token = vault.token();
            if (token.isEmpty()) {
                problem = ErrorCode.PLT_STORAGE_FAILED;
                return;
            }
            if (!net.ready(now)) return;
            int[] budget = {REQUESTS_PER_TICK};
            if (!now.isBefore(nextHeartbeat)) {
                if (!heartbeat(l, token.get(), now, budget)) return;
            }
            if (authorizationsDue || !now.isBefore(nextAuthorizations)) {
                if (!downloadAuthorizations(l, token.get(), now, budget)) return;
            }
            // AGENT-PYTHON-001: decisões gravadas vão primeiro (uma resposta perdida é repetida antes de qualquer pedido novo)
            if (!sendDecisions(l, token.get(), now, budget)) return;
            if (!now.isBefore(nextRunRequests)) {
                if (!pollRunRequests(l, token.get(), now, budget)) return;
            }
            for (Delivery d : ordered()) {
                if (budget[0] <= 0) break;
                if (d.complete() || d.blocked() || !d.agentId().equals(l.agentId())) continue;
                if (!deliver(l, token.get(), d, now, budget)) return;
            }
        } catch (RuntimeException e) {
            problem = ErrorCode.UNEXPECTED; // a linha de fundo continua; a automação nunca é afetada
        } finally {
            refreshProtection();
            publish();
        }
    }

    // ----- resgate (pareamento e rotação)

    private void redeem(Pending p, Instant now) {
        Optional<CredentialVault.Material> m;
        try {
            m = vault.material();
        } catch (IOException e) {
            m = Optional.empty();
        }
        if (m.isEmpty()) {
            // material ilegível: não há como repetir com segurança; o pendente sai e a pessoa recomeça
            abandonPending(p, ErrorCode.PLT_STORAGE_FAILED);
            return;
        }
        Optional<String> current = p.kind() == CredentialVault.Kind.ROTATION ? vault.token() : Optional.empty();
        // T06: a intenção vai declarada; um código do tipo errado é recusado pela Platform SEM ser consumido
        MachineClient.Intent intent = p.kind() == CredentialVault.Kind.ROTATION ? MachineClient.Intent.ROTATE : MachineClient.Intent.PAIR;
        Result<CredentialVault.Redeemed> r = client.redeem(p.destination(), m.get(), current, intent);
        if (r instanceof Ok<CredentialVault.Redeemed> ok) {
            contact(now);
            boolean applied;
            try {
                applied = vault.confirm(ok.value(), now);
            } catch (IOException e) {
                // a Platform confirmou e o disco falhou: o pendente fica e a repetição (idempotente) refaz os passos
                problem = ErrorCode.PLT_STORAGE_FAILED;
                diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
                redeemBackoff.failed(now, Optional.empty());
                return;
            }
            if (!applied) {
                abandonPending(p, ErrorCode.PLT_RESPONSE_INVALID); // rotação respondida para outro Agent: incoerente
                return;
            }
            redeemBackoff.succeeded();
            problem = null;
            authorizationsDue = true;
            nextHeartbeat = Instant.MIN;
            authorizations.load(ok.value().agentId());
            if (p.kind() == CredentialVault.Kind.PAIRING) diagnostics.paired(ok.value().agentId());
            else diagnostics.rotated();
            return;
        }
        if (r instanceof Refused<CredentialVault.Redeemed> ref) {
            if (ref.status() == 401) {
                contact(now);
                abandonPending(p, p.kind() == CredentialVault.Kind.PAIRING ? ErrorCode.PLT_CODE_REFUSED : ErrorCode.PLT_ROTATION_REFUSED);
                return;
            }
            if (ref.status() == 409) {
                contact(now);
                // código do tipo errado: só a tentativa sai; o vínculo existente (se houver) fica intacto
                ErrorCode why = switch (ref.reason() == null ? "" : ref.reason()) {
                    case "pairing_code_for_rotation" -> ErrorCode.PLT_PAIRING_CODE_FOR_ROTATION;
                    case "rotation_code_for_pairing" -> ErrorCode.PLT_ROTATION_CODE_FOR_PAIRING;
                    default -> ErrorCode.PLT_WORKER_UNAVAILABLE;
                };
                abandonPending(p, why);
                return;
            }
            if (ref.status() == 400) {
                contact(now);
                abandonPending(p, "unsupported_contract".equals(ref.error()) ? ErrorCode.PLT_CONTRACT_REFUSED : ErrorCode.PLT_CODE_INVALID);
                return;
            }
            if (ref.status() == 429 || ref.status() >= 500) {
                failure(ref.status() == 429 ? ErrorCode.PLT_RATE_LIMITED : ErrorCode.PLT_SERVER_UNAVAILABLE, now);
                redeemBackoff.failed(now, ref.retryAfterSeconds());
                return;
            }
            // outra recusa (3xx de um redirecionamento, 403, 404...): fora do contrato; o resgate não é repetido em laço
            contact(now);
            abandonPending(p, ErrorCode.PLT_RESPONSE_INVALID);
            return;
        }
        // sem resposta ou resposta malformada: resultado DESCONHECIDO; o mesmo material é repetido depois
        failure(r instanceof MachineClient.Malformed ? ErrorCode.PLT_RESPONSE_INVALID : ErrorCode.PLT_UNREACHABLE, now);
        redeemBackoff.failed(now, Optional.empty());
    }

    private void abandonPending(Pending p, ErrorCode why) {
        try {
            vault.abandon(why);
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
        }
        problem = why;
        if (p.kind() == CredentialVault.Kind.PAIRING) diagnostics.pairingRefused(why);
        else diagnostics.rotationRefused(why);
    }

    // ----- presença e autorizações

    private boolean heartbeat(Link l, String token, Instant now, int[] budget) {
        budget[0]--;
        Result<MachineClient.Presence> r = client.heartbeat(l.destination(), token);
        if (!(r instanceof Ok<MachineClient.Presence> ok)) return handleFailure(r, now, null);
        contact(now);
        authorizations.observe(ok.value().serverTime());
        nextHeartbeat = now.plus(HEARTBEAT);
        try {
            vault.expiresAt(ok.value().credentialExpiresAt());
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
        }
        return true;
    }

    private boolean downloadAuthorizations(Link l, String token, Instant now, int[] budget) {
        budget[0]--;
        Result<MachineClient.Authorizations> r = client.authorizations(l.destination(), token);
        if (!(r instanceof Ok<MachineClient.Authorizations> ok)) return handleFailure(r, now, null);
        contact(now);
        try {
            authorizations.replace(l.agentId(), ok.value().items(), now);
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
        }
        authorizationsDue = false;
        nextAuthorizations = now.plus(AUTHORIZATIONS);
        diagnostics.authorizations(ok.value().items().size(), ok.value().rejected());
        return true;
    }

    // ----- entrega de uma execução

    /** Registro, relatórios e eventos de uma execução. Falso = a rodada para (falha de comunicação ou credencial). */
    private boolean deliver(Link l, String token, Delivery d, Instant now, int[] budget) {
        ExecutionId id = d.executionId();
        Optional<ExecutionRecord> rec = runs.record(id);
        if (rec.isEmpty()) {
            if (!Files.exists(runs.dir(id))) save(d.block(null, ErrorCode.PLT_LOCAL_HISTORY_REMOVED.code(), now), true);
            return true; // estado.json momentaneamente ilegível: tenta na próxima rodada
        }
        ExecutionRecord r = rec.get();
        // 1. registro (idempotente por agent_id + agent_execution_id)
        if (d.runId() == null) {
            budget[0]--;
            SupervisorReports.Report rep = SupervisorReports.of(r);
            Result<MachineClient.Registered> res = client.register(l.destination(), token, id.value(), d.authorizationId(), d.fingerprint(), runRequestOf(id),
                    rep.json());
            if (res instanceof Ok<MachineClient.Registered> ok) {
                contact(now);
                String outcome = ok.value().outcome();
                boolean conflict = "CONFLICT".equals(outcome);
                d = d.registered(ok.value().runId(), rep.state().name(), rep.digest(), rep.terminal() || conflict, now);
                if (conflict) d = d.reported(rep.state().name(), rep.digest(), true, true, now);
                if (!save(d, false)) return false;
                lastSync = now;
                diagnostics.runRegistered(id, ok.value().created());
                diagnostics.report(id, rep.state(), outcome);
            } else {
                return runFailure(res, d, now);
            }
        }
        // 2. relatórios do supervisor (a cada mudança do estado.json gravado)
        if (!d.reportConflict()) {
            ExecutionState last = d.reportedState() == null ? null : ExecutionState.valueOf(d.reportedState());
            for (SupervisorReports.Report rep : SupervisorReports.plan(last, d.reportedDigest(), r)) {
                if (budget[0]-- <= 0) return true;
                Result<MachineClient.Reported> res = client.report(l.destination(), token, id.value(), rep.json());
                if (!(res instanceof Ok<MachineClient.Reported> ok)) return runFailure(res, d, now);
                contact(now);
                boolean conflict = "CONFLICT".equals(ok.value().outcome());
                d = conflict ? d.reported(d.reportedState(), rep.digest(), true, true, now) : d.reported(rep.state().name(), rep.digest(), rep.terminal(), false, now);
                if (!save(d, false)) return false;
                lastSync = now;
                diagnostics.report(id, rep.state(), ok.value().outcome());
                if (conflict) {
                    diagnostics.blocked(id, ErrorCode.PLT_REPORT_CONFLICT, null);
                    break;
                }
            }
        }
        // 3. eventos, a partir de settledThrough + 1
        while (budget[0] > 0) {
            EventReader.Batch b;
            try {
                b = EventReader.next(runs.dir(id), id.value(), d.settledThrough(), d.readOffset(), d.readSequence(), activity.durableThrough(id), d.declared());
            } catch (IOException e) {
                return true; // histórico momentaneamente ilegível: nada muda, nova leitura depois
            }
            if (b.rescanned()) d = d.rewound(now);
            if (b.empty()) {
                lastSequence.put(id, b.lastSequence());
                if (b.stop() == EventReader.Stop.HOLE_UNEXPLAINED) {
                    save(d.block(b.holeAt(), ErrorCode.PLT_HISTORY_GAP.code(), now), true);
                    return true;
                }
                if (b.stop() == EventReader.Stop.END && b.closed() && d.settledThrough() >= b.lastSequence() && r.state().terminal() && d.reportFinal()) {
                    d = d.done(now);
                    if (save(d, false)) diagnostics.delivered(id, d.settledThrough());
                } else if (b.rescanned()) {
                    save(d, false);
                }
                return true;
            }
            // faixas novas de perda definitiva: gravadas ANTES do envio (o reenvio repete exatamente as mesmas)
            List<Range> already = d.declared();
            List<Range> fresh = b.missing().stream().filter(x -> !already.contains(x)).toList();
            if (!fresh.isEmpty()) {
                List<Range> all = new ArrayList<>(already);
                all.addAll(fresh);
                Delivery nd = d.declaring(all, now);
                if (!save(nd, false)) return false;
                long n = fresh.stream().mapToLong(Range::size).sum();
                diagnostics.lossDeclared(id, n);
                d = nd;
            }
            budget[0]--;
            List<String> raws = b.events().stream().map(EventReader.Line::raw).toList();
            Result<MachineClient.Ack> res = client.events(l.destination(), token, id.value(), raws, b.missing());
            if (!(res instanceof Ok<MachineClient.Ack> ok)) return runFailure(res, d, now);
            contact(now);
            AckCheck.Verdict v = AckCheck.check(d, b.events(), b.missing(), ok.value());
            if (!v.valid()) {
                // ACK recusado: nada avança, nada é liberado; o reenvio é seguro (DUPLICATE)
                diagnostics.responseRejected(id, v.why());
                problem = ErrorCode.PLT_RESPONSE_INVALID;
                net.failed(now, Optional.empty());
                return false;
            }
            long offset = b.baseOffset();
            long offsetSeq = b.baseSequence();
            long sent = d.maxSent();
            for (EventReader.Line line : b.events()) {
                sent = Math.max(sent, line.sequence());
                if (line.sequence() <= v.settled()) {
                    offset = line.end();
                    offsetSeq = line.sequence();
                }
            }
            Delivery nd = d.acked(v.stored(), v.settled(), sent, offset, offsetSeq, d.contentConflicts() + v.contentConflicts(), now);
            if (v.contentConflicts() > 0) diagnostics.blocked(id, ErrorCode.PLT_EVENT_CONFLICT, null);
            if (v.stoppedAt() != null) {
                ErrorCode why = v.stopReason().startsWith("invalido") ? ErrorCode.PLT_EVENT_INVALID : ErrorCode.PLT_PAYLOAD_INVALID;
                nd = nd.block(v.stoppedAt(), why.code(), now);
            }
            if (!save(nd, false)) return false; // o progresso só existe depois de gravado
            lastSync = now;
            int storedNow = 0;
            int duplicates = 0;
            for (MachineClient.ItemResult it : ok.value().results()) {
                if ("STORED".equals(it.status())) storedNow++;
                else if ("DUPLICATE".equals(it.status())) duplicates++;
            }
            diagnostics.acked(id, v.stored(), v.settled(), storedNow, duplicates);
            d = nd;
            lastSequence.put(id, Math.max(b.lastSequence(), v.settled()));
            if (nd.blocked()) {
                diagnostics.blocked(id, code(nd.blockedCode()), nd.blockedSequence());
                return true;
            }
            if (b.stop() != EventReader.Stop.LIMIT) break;
        }
        return true;
    }

    /** Desistência pedida pela pessoa: o número (ou a lacuna) bloqueado vira faixa declarada e a entrega segue. */
    private void applyAbandons(Instant now) {
        ExecutionId id;
        while ((id = abandonRequests.poll()) != null) {
            Optional<Delivery> o = Optional.ofNullable(known.get(id));
            if (o.isEmpty() || !o.get().blocked() || o.get().blockedSequence() == null) continue;
            Delivery d = o.get();
            long from = d.blockedSequence();
            long to = from;
            if (ErrorCode.PLT_HISTORY_GAP.code().equals(d.blockedCode())) {
                long next = nextReadableAfter(id, from);
                if (next <= from) continue; // sem evento depois da lacuna: nada a declarar com faixa exata
                to = next - 1;
            }
            Range r = new Range(from, to);
            if (d.declared().stream().anyMatch(x -> x.from() <= r.to() && r.from() <= x.to())) continue;
            List<Range> all = new ArrayList<>(d.declared());
            all.add(r);
            if (save(d.declaring(all, now).unblock(now), false)) diagnostics.lossDeclared(id, r.size());
        }
    }

    private long nextReadableAfter(ExecutionId id, long from) {
        return EventReader.firstSequenceAfter(runs.dir(id), id.value(), from);
    }

    // ----- classificação das respostas

    /** Falha de uma chamada sem execução envolvida (presença, autorizações). */
    private boolean handleFailure(Result<?> r, Instant now, ExecutionId id) {
        if (r instanceof Refused<?> ref) {
            if (ref.status() == 401) {
                contact(now);
                credentialRefused(now);
                return false;
            }
            if (ref.status() == 400 && "unsupported_contract".equals(ref.error())) {
                contact(now);
                contractRefused = true;
                problem = ErrorCode.PLT_CONTRACT_REFUSED;
                return false;
            }
            if (ref.status() == 429 || ref.status() >= 500) {
                failure(ref.status() == 429 ? ErrorCode.PLT_RATE_LIMITED : ErrorCode.PLT_SERVER_UNAVAILABLE, now);
                net.failed(now, ref.retryAfterSeconds());
                return false;
            }
            contact(now);
            problem = ErrorCode.PLT_RESPONSE_INVALID;
            net.failed(now, Optional.empty());
            return false;
        }
        if (r instanceof MachineClient.Malformed<?>) {
            contact(now);
            problem = ErrorCode.PLT_RESPONSE_INVALID;
            diagnostics.responseRejected(id, "malformada");
            net.failed(now, Optional.empty());
            return false;
        }
        failure(ErrorCode.PLT_UNREACHABLE, now);
        net.failed(now, Optional.empty());
        return false;
    }

    /** Falha de uma chamada de entrega: recusas definitivas param SÓ esta execução; as de comunicação, a rodada. */
    private boolean runFailure(Result<?> r, Delivery d, Instant now) {
        if (r instanceof Refused<?> ref && ref.status() != 401 && ref.status() != 429 && ref.status() < 500
                && !(ref.status() == 400 && "unsupported_contract".equals(ref.error()))) {
            contact(now);
            ErrorCode why = switch (ref.status()) {
                case 403 -> ErrorCode.PLT_AUTHORIZATION_REFUSED;
                case 409 -> ErrorCode.PLT_REGISTRATION_CONFLICT;
                default -> ErrorCode.PLT_PAYLOAD_INVALID;
            };
            save(d.block(null, why.code(), now), true);
            return true;
        }
        return handleFailure(r, now, d.executionId());
    }

    private void credentialRefused(Instant now) {
        try {
            vault.refuse(now);
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
        }
        problem = ErrorCode.PLT_CREDENTIAL_REFUSED;
        diagnostics.credentialRefused();
    }

    private void contact(Instant now) {
        boolean was = connectivity == Connectivity.CONNECTED;
        lastContact = now;
        connectivity = Connectivity.CONNECTED;
        if (problem == ErrorCode.PLT_UNREACHABLE || problem == ErrorCode.PLT_SERVER_UNAVAILABLE || problem == ErrorCode.PLT_RATE_LIMITED) problem = null;
        net.succeeded();
        if (!was) diagnostics.connected();
    }

    private void failure(ErrorCode why, Instant now) {
        boolean was = connectivity != Connectivity.DISCONNECTED;
        connectivity = Connectivity.DISCONNECTED;
        problem = why;
        if (was) diagnostics.disconnected(why);
    }

    private List<Delivery> ordered() {
        List<Delivery> l = new ArrayList<>(known.values());
        l.sort(java.util.Comparator.comparing(Delivery::linkedAt));
        return l;
    }

    /** Entregas concluídas cuja execução local já saiu pela retenção não têm mais utilidade: o arquivo sai também. */
    private void housekeeping(Instant now) {
        if (now.isBefore(nextHousekeeping)) return;
        nextHousekeeping = now.plus(HOUSEKEEPING);
        for (Delivery d : List.copyOf(known.values())) {
            if (!d.complete() || Files.exists(runs.dir(d.executionId()))) continue;
            try {
                Files.deleteIfExists(deliveries.dir().resolve(d.executionId().value() + ".json"));
                known.remove(d.executionId());
                lastSequence.remove(d.executionId());
            } catch (IOException e) {
                // fica para a próxima limpeza
            }
        }
    }

    private boolean save(Delivery d, boolean announceBlock) {
        try {
            deliveries.save(d);
            known.put(d.executionId(), d);
            if (announceBlock && d.blocked()) diagnostics.blocked(d.executionId(), code(d.blockedCode()), d.blockedSequence());
            return true;
        } catch (IOException e) {
            problem = ErrorCode.PLT_STORAGE_FAILED;
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
            return false;
        }
    }

    // ----- retrato

    private void refreshProtection() {
        Optional<Link> l = vault.link();
        Set<ExecutionId> keep = new java.util.HashSet<>();
        List<Delivery> pending = new ArrayList<>();
        for (Delivery d : known.values()) {
            if (!d.complete() && l.isPresent() && d.agentId().equals(l.get().agentId())) pending.add(d);
        }
        // as mais recentes primeiro: acima do limite, as mais antigas deixam de ser protegidas (a perda passa a ser declarada)
        pending.sort(java.util.Comparator.comparing(Delivery::linkedAt).reversed());
        for (int i = 0; i < pending.size() && i < DeliveryStore.MAX_PENDING_RUNS; i++) keep.add(pending.get(i).executionId());
        protectedRuns.retainAll(keep);
        protectedRuns.addAll(keep);
    }

    private void publish() {
        PlatformStatus before = status;
        status = build();
        if (status.equals(before)) return; // a tela só é avisada de mudanças
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                // a interface nunca derruba o transporte
            }
        }
    }

    private PlatformStatus build() {
        Optional<Link> l = vault.link();
        Optional<Pending> p = vault.pending();
        LinkState state;
        UnlinkRequestStore.Request u = unlinking;
        if (u != null || unlinkUnreadable) state = LinkState.UNLINKING;
        else if (p.isPresent() && p.get().kind() == CredentialVault.Kind.PAIRING) state = LinkState.PAIRING;
        else if (l.isEmpty()) state = LinkState.NOT_PAIRED;
        else if (l.get().refused()) state = LinkState.REFUSED;
        else if (contractRefused) state = LinkState.CONTRACT_REFUSED;
        else if (Destination.parse(configuredUrl.get()).map(x -> !x.equals(l.get().destination())).orElse(true)) state = LinkState.DESTINATION_CHANGED;
        else if (p.isPresent()) state = LinkState.ROTATING;
        else state = LinkState.PAIRED;
        Connectivity c = connectivity;
        Instant lc = lastContact;
        if (c == Connectivity.CONNECTED && lc != null && lc.plus(CONTACT_FRESH).isBefore(clock.instant())) c = Connectivity.UNKNOWN;
        int pendingRuns = 0;
        long pendingEvents = 0;
        int incomplete = 0;
        List<PlatformStatus.Blocked> blocked = new ArrayList<>();
        for (Delivery d : ordered()) {
            if (l.isEmpty() || !d.agentId().equals(l.get().agentId())) continue;
            if (d.declaredCount() > 0) incomplete++;
            if (d.complete()) continue;
            pendingRuns++;
            pendingEvents += Math.max(0, lastSequence.getOrDefault(d.executionId(), d.settledThrough()) - d.settledThrough());
            if (d.blocked()) blocked.add(new PlatformStatus.Blocked(d.executionId(), code(d.blockedCode()), d.blockedSequence()));
        }
        ErrorCode pr = problem;
        if (state == LinkState.DESTINATION_CHANGED) pr = ErrorCode.PLT_DESTINATION_CHANGED;
        if (state == LinkState.REFUSED) pr = ErrorCode.PLT_CREDENTIAL_REFUSED;
        if (state == LinkState.NOT_PAIRED && pr == null && vault.lastRefusal() != null) pr = code(vault.lastRefusal());
        String host = l.map(x -> x.destination().host()).orElse(p.map(x -> x.destination().host()).orElse(null));
        return new PlatformStatus(state, c, host, l.map(x -> x.organizationId().toString()).orElse(null), l.map(x -> x.agentId().toString()).orElse(null),
                l.map(x -> x.workerId() == null ? null : x.workerId().toString()).orElse(null), lc, lastSync, l.map(Link::credentialExpiresAt).orElse(null),
                pendingRuns, pendingEvents, blocked, incomplete, authorizations.all().size(), authorizations.downloadedAt().orElse(null), pr, secrets.available(),
                u == null && !unlinkUnreadable ? new PlatformStatus.Unlink(false, null, 0, null, null, unlinkConcluded, unlinkConcludedAt)
                        : new PlatformStatus.Unlink(true, u == null ? null : u.confirmedAt(), u == null ? 0 : u.attempts(), u == null ? null : u.lastAttemptAt(),
                                u == null ? "PEDIDO_ILEGIVEL" : u.lastResult(), null, null));
    }

    private static ErrorCode code(String c) {
        if (c == null) return null;
        for (ErrorCode e : ErrorCode.values()) if (e.code().equals(c)) return e;
        return ErrorCode.UNEXPECTED;
    }

    Path dir() {
        return dir;
    }
}
