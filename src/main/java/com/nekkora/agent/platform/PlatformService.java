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

        default void linkRemoved() {}

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
    private final MachineClient client;
    private final Backoff net;
    private final Backoff redeemBackoff;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<ExecutionId> abandonRequests = new ConcurrentLinkedQueue<>();
    /** Execuções cuja cópia local não pode ser apagada pela retenção (entrega pendente do vínculo atual). */
    private final Set<ExecutionId> protectedRuns = ConcurrentHashMap.newKeySet();
    private final Map<ExecutionId, Long> lastSequence = new ConcurrentHashMap<>();
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
        this.client = new MachineClient(http, agentVersion);
        this.net = new Backoff(random);
        this.redeemBackoff = new Backoff(random);
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
        resolveIntent(clock.instant());
        refreshProtection();
        publish();
    }

    public synchronized void start() {
        if (exec != null || closed) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nekkora-agent-plataforma");
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

    /**
     * Remove o vínculo LOCAL (decisão da pessoa, tipicamente depois de uma recusa): credencial, material e autorizações
     * guardadas. Entregas pendentes do vínculo removido ficam guardadas e marcadas como de outro vínculo (não são
     * reenviadas por um vínculo novo). No servidor nada muda.
     */
    public Action removeLocalLink() {
        try {
            vault.remove();
            authorizations.clear();
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
            return Action.refused(ErrorCode.PLT_STORAGE_FAILED);
        }
        problem = null;
        contractRefused = false;
        refreshProtection();
        diagnostics.linkRemoved();
        publish();
        return Action.ok();
    }

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
        // credencial recusada: nada novo é registrado por este vínculo (a execução pode seguir só local)
        if (l.get().refused()) return Action.refused(ErrorCode.PLT_CREDENTIAL_REFUSED);
        Optional<Authorization> z = authorizations.find(authorizationId);
        if (fp.isEmpty() || z.isEmpty() || !z.get().fingerprint().equals(fp.get()) || !PackageFingerprint.RULE.equals(z.get().rule())
                || !clock.instant().isBefore(z.get().validUntil())) {
            return Action.refused(ErrorCode.PLT_AUTHORIZATION_REFUSED);
        }
        try {
            deliveries.saveIntent(new DeliveryStore.Intent(l.get().agentId(), authorizationId, fp.get(), clock.instant()));
        } catch (IOException e) {
            diagnostics.storageFailed(ErrorCode.PLT_STORAGE_FAILED);
            return Action.refused(ErrorCode.PLT_STORAGE_FAILED);
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
        if (known.containsKey(id)) return;
        try {
            Delivery d = Delivery.linked(id, i.agentId(), i.authorizationId(), i.fingerprint(), clock.instant());
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
        if (l.isEmpty() || !l.get().agentId().equals(d.agentId())) st = DeliveryView.State.OTHER_LINK;
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
            Optional<Pending> p = vault.pending();
            if (p.isPresent()) {
                if (redeemBackoff.ready(now)) redeem(p.get(), now);
                return;
            }
            Optional<Link> ol = vault.link();
            if (ol.isEmpty() || ol.get().refused() || contractRefused) return;
            Link l = ol.get();
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
        Result<CredentialVault.Redeemed> r = client.redeem(p.destination(), m.get(), current);
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
                abandonPending(p, ErrorCode.PLT_WORKER_UNAVAILABLE);
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
            Result<MachineClient.Registered> res = client.register(l.destination(), token, id.value(), d.authorizationId(), d.fingerprint(), rep.json());
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
        if (p.isPresent() && p.get().kind() == CredentialVault.Kind.PAIRING) state = LinkState.PAIRING;
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
        return new PlatformStatus(state, c, host, l.map(x -> x.agentId().toString()).orElse(null),
                l.map(x -> x.workerId() == null ? null : x.workerId().toString()).orElse(null), lc, lastSync, l.map(Link::credentialExpiresAt).orElse(null),
                pendingRuns, pendingEvents, blocked, incomplete, authorizations.all().size(), authorizations.downloadedAt().orElse(null), pr, secrets.available());
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
