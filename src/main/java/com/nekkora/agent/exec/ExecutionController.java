package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Supervisor da execução local (sem Swing: a interface só observa). Regras:
 * <ul>
 *   <li>UMA execução ativa por usuário; pedido concorrente é recusado ({@code AGT-EXE-014}), sempre;</li>
 *   <li>nada começa sozinho: só {@link #start} com um pedido explícito; antes de lançar, confere a sessão interativa, o
 *       pacote (hashes de novo), a aprovação da MESMA impressão digital, o Python, a entrada e a pasta de saída;</li>
 *   <li>roda da cópia conferida, com argumentos em lista e ambiente mínimo; a saída do processo é drenada e descartada
 *       (só a contagem de bytes fica) — nenhum valor da automação chega ao registro;</li>
 *   <li>fim: o estado vem do protocolo estruturado do processo ({@link Launch#classify}); cancelamento e prazo encerram
 *       só a árvore possuída ({@link ProcessOwner});</li>
 *   <li>bloqueio da tela e suspensão do Windows (salto do relógio) durante a execução são registrados; resultado sem
 *       prova depois de suspensão é {@code OUTCOME_UNKNOWN};</li>
 *   <li>reinício do Agent: {@link #reconcile} adota o processo que ainda é o mesmo (PID + início + executável) ou fecha o
 *       registro sem inventar resultado; nada é relançado. A02: se o processo terminou enquanto o Agent estava fora, o
 *       protocolo publicado decide o desfecho só depois de conferido ({@link ProtocolEvidence}); senão {@code OUTCOME_UNKNOWN};</li>
 *   <li>F-AGT-01: depois do lançamento, falha ao gravar o {@code estado.json} nunca deixa o processo sem dono nem muda o
 *       desfecho: a supervisão segue, o disco fica no último estado gravado (nada incerto é dado por gravado), a gravação
 *       é tentada de novo e a falha que não passa é sinalizada ({@link RecordDiagnostics}, evento, tela).</li>
 * </ul>
 */
public final class ExecutionController implements AutoCloseable {

    /** Pedido tipado (nenhuma linha de comando livre). {@code input} só quando o pacote exige entrada. */
    public record Request(Path packageDir, String fingerprint, Path python, Path input, Path outputRoot, Duration timeout) {
        public Request {
            Objects.requireNonNull(packageDir);
            Objects.requireNonNull(fingerprint);
            Objects.requireNonNull(python);
            Objects.requireNonNull(outputRoot);
            timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        }
    }

    /**
     * O que a interface vê da execução ativa ou da última. {@code recordSaved} falso: o estado mostrado ainda NÃO está no
     * {@code estado.json} (F-AGT-01; a gravação é tentada de novo e a tela avisa).
     */
    public record Snapshot(ExecutionRecord record, long elapsedMillis, boolean running, boolean recordSaved) {
        public Snapshot(ExecutionRecord record, long elapsedMillis, boolean running) {
            this(record, elapsedMillis, running, true);
        }
    }

    public interface Listener {
        void changed(Snapshot s);
    }

    /**
     * Diagnóstico da gravação do {@code estado.json} (para o log do Agent): só transições, nunca caminho ou conteúdo.
     * Uma falha aqui é ignorada.
     */
    public interface RecordDiagnostics {
        /** A gravação falhou; o estado fica em memória e é tentado de novo. */
        void delayed(ExecutionId id, ExecutionState state);

        /** Uma gravação que tinha falhado foi concluída. */
        void recovered(ExecutionId id, ExecutionState state);

        /** O estado FINAL não pôde ser gravado no prazo de {@link #FINAL_SAVE_GRACE}: o disco ficou num estado anterior. */
        void lost(ExecutionId id, ExecutionState state);
    }

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(60);
    /** Por quanto tempo o supervisor insiste em gravar o estado FINAL antes de sinalizar a perda (depois, só em nova ocasião). */
    public static final Duration FINAL_SAVE_GRACE = Duration.ofSeconds(10);
    /** Arquivo da pasta da execução com o caminho da saída (local; fora do registro e do diagnóstico). */
    static final String OUTPUT_REF = "saida.ref";
    /**
     * Posse do processo lançado (PID + início + resumo do executável), gravada num arquivo NOVO logo depois do lançamento
     * e antes de gravar RUNNING: um arquivo que acabou de ser criado não pode estar aberto por outro processo. Se a
     * gravação de RUNNING falhar e o Agent cair, a reconciliação ainda encontra o processo (F-AGT-01).
     */
    static final String PROCESS_REF = "processo.json";
    /** Salto do relógio de parede entre duas verificações acima disto = a máquina parou (suspensão/hibernação). */
    static final Duration SUSPENSION_GAP = Duration.ofSeconds(20);

    private final RunStore store;
    private final ApprovalStore approvals;
    private final SessionProbe session;
    private final Clock clock;
    private final Path agentDir;
    private final Duration tick;
    private final Duration sessionEvery;
    private final Duration finalGrace;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private Active active;
    private volatile ExecutionRecord last;
    /** Estados de execuções JÁ terminadas (ou reconciliadas) que ainda não chegaram ao disco; tentados de novo. */
    private final Map<ExecutionId, ExecutionRecord> unsaved = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile RecordDiagnostics diagnostics;

    /** Execução ativa (só uma). */
    private final class Active {
        volatile ExecutionRecord record;
        final Process process; // null quando adotada depois de reinício (sem fluxo nem código de saída)
        final ExecutionPackage.Kind kind;
        final ExecutionPackage pkg;
        final Path output;
        final long startNanos;
        final Duration timeout;
        final AtomicBoolean cancel = new AtomicBoolean();
        final AtomicLong bytes = new AtomicLong();
        final List<Thread> drains = new ArrayList<>();
        /** Gravações do {@code record} que falharam desde a última que deu certo (0 = o disco tem o {@code record}). */
        volatile int saveFailures;

        Active(ExecutionRecord r, Process p, ExecutionPackage pkg, ExecutionPackage.Kind kind, Path output, Duration timeout) {
            this.record = r;
            this.process = p;
            this.pkg = pkg;
            this.kind = kind;
            this.output = output;
            this.timeout = timeout;
            this.startNanos = System.nanoTime();
        }
    }

    public ExecutionController(RunStore store, ApprovalStore approvals, SessionProbe session, Clock clock, Path agentDir) {
        this(store, approvals, session, clock, agentDir, Duration.ofMillis(500), Duration.ofSeconds(5));
    }

    ExecutionController(RunStore store, ApprovalStore approvals, SessionProbe session, Clock clock, Path agentDir, Duration tick, Duration sessionEvery) {
        this(store, approvals, session, clock, agentDir, tick, sessionEvery, FINAL_SAVE_GRACE);
    }

    ExecutionController(RunStore store, ApprovalStore approvals, SessionProbe session, Clock clock, Path agentDir, Duration tick, Duration sessionEvery,
            Duration finalGrace) {
        this.store = store;
        this.approvals = approvals;
        this.session = session;
        this.clock = clock;
        this.agentDir = agentDir;
        this.tick = tick;
        this.sessionEvery = sessionEvery;
        this.finalGrace = finalGrace;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void recordDiagnostics(RecordDiagnostics d) {
        this.diagnostics = d;
    }

    public ApprovalStore approvals() {
        return approvals;
    }

    public SessionProbe session() {
        return session;
    }

    public RunStore store() {
        return store;
    }

    public boolean running() {
        synchronized (lock) {
            return active != null;
        }
    }

    /** A execução ativa, ou a última terminada nesta sessão do Agent. */
    public Optional<Snapshot> current() {
        synchronized (lock) {
            if (active != null) return Optional.of(new Snapshot(active.record, elapsed(active), true, active.saveFailures == 0));
        }
        ExecutionRecord l = last;
        return l == null ? Optional.empty() : Optional.of(endSnapshot(l));
    }

    private Snapshot endSnapshot(ExecutionRecord l) {
        return new Snapshot(l, l.started() == null || l.ended() == null ? 0 : Duration.between(l.started(), l.ended()).toMillis(), false,
                !unsaved.containsKey(l.id()));
    }

    // ------------------------------------------------------------------------------------------- início

    /**
     * Confere tudo e lança (fora da EDT). Recusa sem lançar → {@link ExecRefused} (o registro fica como {@code BLOCKED},
     * exceto quando outra execução já está ativa — aí nenhum registro novo é criado).
     */
    public Snapshot start(Request req) throws ExecRefused {
        synchronized (lock) {
            if (active != null) throw new ExecRefused(ErrorCode.EXEC_ALREADY_RUNNING);
            flushUnsaved();
            ExecutionId id = ExecutionId.random();
            Instant now = clock.instant();
            ExecutionPackage pkg;
            try {
                pkg = ExecutionPackage.inspect(req.packageDir());
            } catch (ExecRefused e) {
                throw blocked(new ExecutionRecord(id, null, "pacote", "sha256:" + "0".repeat(64), ExecutionState.READY, null, now, null, null, null, null,
                        Map.of(), 0, 0, false, 0), e.code());
            }
            // no registro, o pacote é identificado só pelo tipo e pela impressão digital (o nome da pasta pode ser de um cliente)
            ExecutionRecord r = new ExecutionRecord(id, pkg.kind(), "pacote " + pkg.shortFingerprint(), pkg.fingerprint(), ExecutionState.READY, null, now,
                    null, null, null, null, Map.of(), 0, 0, false, 0);
            // a impressão digital conferida AGORA precisa ser a que a pessoa escolheu e aprovou
            if (!pkg.fingerprint().equals(req.fingerprint())) throw blocked(r, ErrorCode.EXEC_PACKAGE_CHANGED);
            if (!approvals.approved(pkg)) throw blocked(r, ErrorCode.EXEC_NOT_APPROVED);
            SessionProbe.Session s = session.probe();
            if (!s.usable()) throw blocked(r, s.problem());
            PythonRuntime.Probe probe;
            try {
                probe = PythonRuntime.probe(req.python(), agentDir.resolve("sonda"));
                PythonRuntime.requireCompatible(probe, pkg);
            } catch (ExecRefused e) {
                throw blocked(r, e.code());
            }
            Path input = null;
            if (pkg.needsInput()) {
                if (req.input() == null || !Files.isRegularFile(req.input()) || inside(req.input(), pkg.dir())) throw blocked(r, ErrorCode.EXEC_INPUT_MISSING);
                input = req.input().toAbsolutePath().normalize();
            }
            Path outRoot = req.outputRoot().toAbsolutePath().normalize();
            if (!Files.isDirectory(outRoot) || inside(outRoot, pkg.dir()) || inside(outRoot, agentDir) || inside(agentDir, outRoot)) {
                throw blocked(r, ErrorCode.EXEC_OUTPUT_INVALID);
            }
            // a partir daqui: STARTING (registro gravado antes de lançar — uma queda deixa rastro)
            r = r.moveTo(ExecutionState.STARTING, null, clock.instant());
            store.save(r);
            store.event(id, clock.instant(), "STARTING", Map.of("kind", pkg.kind().name(), "mode", pkg.mode().name(), "session", s.id()));
            Path copy;
            Path output = outRoot.resolve(id.value());
            try {
                copy = Launch.snapshot(pkg, store.dir(id));
                Files.createDirectories(output);
                // só para reconciliar depois de um reinício (ler o protocolo publicado); nunca vai ao diagnóstico
                Files.writeString(store.dir(id).resolve(OUTPUT_REF), output.toString(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (ExecRefused e) {
                throw finish(r, ExecutionState.BLOCKED, e.code());
            } catch (IOException e) {
                throw finish(r, ExecutionState.BLOCKED, ErrorCode.EXEC_OUTPUT_INVALID);
            }
            Launch.Spec spec = Launch.spec(pkg, probe.python(), copy, input, output);
            Process p;
            try {
                ProcessBuilder pb = new ProcessBuilder(spec.command()).directory(spec.workDir().toFile());
                PythonRuntime.minimalEnvironment(pb.environment());
                p = pb.start();
                p.getOutputStream().close();
            } catch (IOException | RuntimeException e) {
                throw finish(r, ExecutionState.FAILED, ErrorCode.EXEC_LAUNCH_FAILED);
            }
            // F-AGT-01: daqui em diante o processo EXISTE. Nada pode escapar de start() sem que ele tenha dono: uma falha ao
            // gravar RUNNING não encerra nem relança nada — a supervisão começa igual e a gravação é tentada de novo
            ProcessOwner owner = ProcessOwner.of(p, spec.executable().toString());
            writeProcessRef(id, owner);
            r = r.withOwner(owner).moveTo(ExecutionState.RUNNING, null, clock.instant());
            Active a = new Active(r, p, pkg, pkg.kind(), output, req.timeout());
            store.event(id, clock.instant(), "RUNNING", Map.of("pid", owner.pid()));
            persist(a);
            drain(a, p.getInputStream(), "saida");
            drain(a, p.getErrorStream(), "erros");
            active = a;
            Thread t = new Thread(() -> supervise(a), "nekkora-agent-execucao");
            t.setDaemon(true);
            t.start();
            Snapshot snap = new Snapshot(r, 0, true, a.saveFailures == 0);
            notifyListeners(snap);
            return snap;
        }
    }

    /** Pede o cancelamento da execução ativa (o supervisor encerra a árvore possuída). */
    public boolean cancel() {
        synchronized (lock) {
            if (active == null) return false;
            active.cancel.set(true);
            return true;
        }
    }

    private ExecRefused blocked(ExecutionRecord r, ErrorCode why) {
        ExecutionRecord b = r.moveTo(ExecutionState.BLOCKED, why, clock.instant());
        try {
            store.save(b);
            store.event(b.id(), clock.instant(), "BLOCKED", Map.of("code", why.code()));
        } catch (ExecRefused ignored) {
            // a recusa vale mesmo sem registro em disco
        }
        last = b;
        notifyListeners(new Snapshot(b, 0, false));
        return new ExecRefused(why);
    }

    private ExecRefused finish(ExecutionRecord r, ExecutionState to, ErrorCode why) {
        ExecutionRecord f = r.moveTo(to, why, clock.instant());
        try {
            store.save(f);
            store.event(f.id(), clock.instant(), to.name(), Map.of("code", why.code()));
        } catch (ExecRefused ignored) {
            // idem
        }
        last = f;
        notifyListeners(new Snapshot(f, 0, false));
        return new ExecRefused(why);
    }

    /** Lê o fluxo até o fim só contando bytes (sem guardar conteúdo): o processo nunca trava por saída cheia. */
    private static void drain(Active a, InputStream in, String name) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try (in) {
                for (int n; (n = in.read(buf)) >= 0; ) a.bytes.addAndGet(n);
            } catch (IOException e) {
                // fluxo fechado: o processo terminou
            }
        }, "nekkora-agent-" + name);
        t.setDaemon(true);
        t.start();
        a.drains.add(t);
    }

    // ------------------------------------------------------------------------------------------- supervisão

    private void supervise(Active a) {
        long lastWall = clock.millis();
        long lastSession = System.nanoTime();
        boolean locked = false;
        ExecutionState forced = null;
        ErrorCode forcedCode = null;
        while (true) {
            boolean done = a.process != null ? waitFor(a.process, tick) : !aliveAdopted(a) || pause(tick);
            long wall = clock.millis();
            if (wall - lastWall > SUSPENSION_GAP.toMillis() + tick.toMillis()) {
                a.record = a.record.withSuspension();
                store.event(a.record.id(), clock.instant(), "SUSPENSION_DETECTED", Map.of("gap_ms", wall - lastWall));
                persist(a);
            }
            lastWall = wall;
            if (a.saveFailures > 0 && !done) persist(a); // F-AGT-01: o disco ficou para trás; nova tentativa a cada volta
            if (done) break;
            if (System.nanoTime() - lastSession >= sessionEvery.toNanos()) {
                lastSession = System.nanoTime();
                boolean now = session.probe().locked();
                if (now != locked) {
                    locked = now;
                    if (now) a.record = a.record.withLock();
                    store.event(a.record.id(), clock.instant(), now ? "SESSION_LOCKED" : "SESSION_UNLOCKED", Map.of());
                    persist(a);
                }
            }
            if (a.cancel.get()) {
                forced = ExecutionState.CANCELLED;
                forcedCode = ErrorCode.EXEC_CANCELLED;
                break;
            }
            if (elapsed(a) > a.timeout.toMillis()) {
                forced = ExecutionState.TIMED_OUT;
                forcedCode = ErrorCode.EXEC_TIMEOUT;
                break;
            }
            notifyListeners(new Snapshot(a.record, elapsed(a), true));
        }
        if (forced != null) {
            int killed = a.record.owner() == null ? 0 : a.record.owner().terminate(Duration.ofSeconds(10));
            if (a.process != null) a.process.destroyForcibly();
            store.event(a.record.id(), clock.instant(), "TERMINATED", Map.of("processes", killed));
        }
        for (Thread t : a.drains) {
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Integer exit = null;
        if (a.process != null) {
            waitFor(a.process, Duration.ofSeconds(5));
            exit = a.process.isAlive() ? null : a.process.exitValue();
        }
        Optional<Launch.Reported> rep = a.pkg == null ? Optional.empty() : Launch.reported(a.kind, a.output);
        ExecutionState state;
        ErrorCode code;
        boolean recovered = false;
        if (forced != null) {
            state = forced;
            code = forcedCode;
        } else if (a.process == null) {
            // adotada depois de reinício: sem código de saída; só o protocolo CONFERIDO pode dizer o resultado (A02)
            ProtocolEvidence.Verdict v = verdict(a.record);
            if (v.accepted()) {
                Launch.Outcome o = Launch.classifyVerified(a.pkg, v);
                state = o.state();
                code = o.code();
                rep = Optional.of(v.reported());
                recovered = true;
            } else {
                store.event(a.record.id(), clock.instant(), "PROTOCOL_REJECTED", Map.of("reason", v.rejection().name()));
                state = ExecutionState.OUTCOME_UNKNOWN;
                code = ErrorCode.EXEC_AGENT_RESTARTED;
                rep = Optional.empty(); // contagens de um protocolo não conferido não entram no registro
            }
        } else {
            Launch.Outcome o = Launch.classify(a.pkg, rep, exit == null ? -1 : exit);
            state = o.state();
            code = o.code();
            // suspensão no meio e nenhuma prova estruturada: não se presume nada
            if (a.record.suspensions() > 0 && rep.isEmpty()) {
                state = ExecutionState.OUTCOME_UNKNOWN;
                code = ErrorCode.EXEC_SUSPENDED;
            }
        }
        // o desfecho já está decidido (protocolo, código de saída, prazo, cancelamento): a gravação só o registra, nunca o muda
        ExecutionRecord end = a.record.withExit(exit, rep.map(Launch.Reported::counts).orElse(Map.of()), a.bytes.get()).moveTo(state, code, clock.instant());
        if (recovered) end = end.recoveredFromProtocol();
        // A03: sem resultado final do processo (cancelado, prazo, desconhecido, falha sem protocolo), o progresso parcial
        // VERIFICADO fica no registro, separado das contagens do protocolo; o estado continua o que foi decidido acima
        if (a.kind == ExecutionPackage.Kind.STUDIO_PYTHON && (forced != null || rep.isEmpty())) end = withPartialProgress(end);
        a.record = end;
        persist(a);
        boolean saved = a.saveFailures == 0;
        if (!saved) unsaved.put(end.id(), end);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("exit", exit == null ? "none" : exit);
        f.put("protocol", rep.isPresent());
        if (recovered) f.put("recovered", true);
        f.put("output_bytes", a.bytes.get());
        if (code != null) f.put("code", code.code());
        store.event(end.id(), clock.instant(), state.name(), f);
        synchronized (lock) {
            active = null;
            last = end;
        }
        store.prune();
        notifyListeners(endSnapshot(end));
        if (!saved) insistOnFinal(end);
    }

    /**
     * F-AGT-02: o estado FINAL não foi gravado. O processo já acabou (a vaga de execução foi liberada); o supervisor
     * insiste por {@link #finalGrace}. Sem sucesso, a perda é SINALIZADA (evento, diagnóstico, tela) e o estado continua
     * guardado em memória para a próxima ocasião (novo início, encerramento do Agent). Reinício antes disso: o disco tem
     * um estado não terminal, que a reconciliação fecha como {@code OUTCOME_UNKNOWN} — nada é presumido nem relançado.
     */
    private void insistOnFinal(ExecutionRecord end) {
        long deadline = System.nanoTime() + finalGrace.toNanos();
        while (System.nanoTime() < deadline) {
            pause(tick);
            if (saveUnsaved(end.id())) return;
        }
        if (!unsaved.containsKey(end.id())) return;
        store.event(end.id(), clock.instant(), "RECORD_SAVE_FAILED", Map.of("state", end.state().name(), "code", ErrorCode.EXEC_STORAGE_FAILED.code()));
        diagnose(d -> d.lost(end.id(), end.state()));
        notifyListeners(endSnapshot(end));
    }

    /**
     * Grava o estado em memória da execução ativa. Nunca lança: uma falha só deixa o disco PARA TRÁS (marcado em
     * {@link Active#saveFailures} e mostrado na tela); o processo, a supervisão e o desfecho não mudam. A primeira falha
     * e a recuperação viram evento e diagnóstico (uma linha por mudança, não por tentativa).
     */
    private void persist(Active a) {
        ExecutionRecord r = a.record;
        try {
            store.save(r);
        } catch (ExecRefused e) {
            if (a.saveFailures++ == 0) {
                store.event(r.id(), clock.instant(), "RECORD_SAVE_DELAYED", Map.of("state", r.state().name(), "code", e.code().code()));
                diagnose(d -> d.delayed(r.id(), r.state()));
            }
            return;
        }
        if (a.saveFailures > 0) {
            a.saveFailures = 0;
            store.event(r.id(), clock.instant(), "RECORD_SAVE_RECOVERED", Map.of("state", r.state().name()));
            diagnose(d -> d.recovered(r.id(), r.state()));
        }
    }

    /** Grava um estado de execução que não está ativa (reconciliação); na falha, guarda para nova tentativa e sinaliza. */
    private void saveOrKeep(ExecutionRecord r) {
        try {
            store.save(r);
        } catch (ExecRefused e) {
            unsaved.put(r.id(), r);
            store.event(r.id(), clock.instant(), "RECORD_SAVE_DELAYED", Map.of("state", r.state().name(), "code", e.code().code()));
            diagnose(d -> d.delayed(r.id(), r.state()));
        }
    }

    /** Uma nova tentativa para um estado guardado; verdadeiro quando nada mais falta gravar para essa execução. */
    private boolean saveUnsaved(ExecutionId id) {
        ExecutionRecord r = unsaved.get(id);
        if (r == null) return true;
        try {
            store.save(r);
        } catch (ExecRefused e) {
            return false;
        }
        if (unsaved.remove(id, r)) {
            store.event(id, clock.instant(), "RECORD_SAVE_RECOVERED", Map.of("state", r.state().name()));
            diagnose(d -> d.recovered(id, r.state()));
            ExecutionRecord l = last;
            if (l != null && l.id().equals(id)) notifyListeners(endSnapshot(l));
        }
        return true;
    }

    private void flushUnsaved() {
        for (ExecutionId id : List.copyOf(unsaved.keySet())) saveUnsaved(id);
    }

    private void diagnose(java.util.function.Consumer<RecordDiagnostics> call) {
        RecordDiagnostics d = diagnostics;
        if (d == null) return;
        try {
            call.accept(d);
        } catch (RuntimeException e) {
            // o diagnóstico nunca derruba a supervisão
        }
    }

    /** Posse do processo num arquivo novo, sincronizado no disco; falha aqui só reduz o rastro (o evento RUNNING tem o PID). */
    private void writeProcessRef(ExecutionId id, ProcessOwner o) {
        String json = "{\"pid\": " + o.pid() + ", \"start\": " + o.startMillis() + ", \"executableDigest\": "
                + com.nekkora.agent.storage.StrictJson.quote(o.executableDigest()) + "}\n";
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(store.dir(id).resolve(PROCESS_REF), java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        } catch (IOException | RuntimeException e) {
            // segue: o processo continua supervisionado nesta sessão do Agent
        }
    }

    /** A posse gravada no lançamento, só se o arquivo for pequeno, completo e com os três campos válidos. */
    private Optional<ProcessOwner> processRef(ExecutionId id) {
        try {
            Path f = store.dir(id).resolve(PROCESS_REF);
            if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(f) > 1024) return Optional.empty();
            Map<String, Object> m = JsonReader.object(Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
            if (m.size() == 3 && m.get("pid") instanceof Long pid && pid > 0 && m.get("start") instanceof Long start && start >= 0
                    && m.get("executableDigest") instanceof String exe && exe.matches("sha256:[0-9a-f]{64}")) {
                return Optional.of(new ProcessOwner(pid, start, exe));
            }
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            // ilegível ou pela metade: sem prova de posse
        }
        return Optional.empty();
    }

    /** Espera um intervalo (execução adotada: não há {@code Process} para esperar); devolve sempre falso. */
    private static boolean pause(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private boolean aliveAdopted(Active a) {
        return a.record.owner() != null && a.record.owner().alive();
    }

    private static boolean waitFor(Process p, Duration d) {
        try {
            return p.waitFor(d.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !p.isAlive();
        }
    }

    private long elapsed(Active a) {
        return (System.nanoTime() - a.startNanos) / 1_000_000;
    }

    private void notifyListeners(Snapshot s) {
        for (Listener l : listeners) {
            try {
                l.changed(s);
            } catch (RuntimeException e) {
                // a interface não derruba a supervisão
            }
        }
    }

    // ------------------------------------------------------------------------------------------- reinício

    /**
     * Depois de um reinício do Agent: cada registro não terminado é fechado SEM inventar resultado e SEM relançar nada.
     * O processo que ainda é o mesmo (PID + início + executável) é ADOTADO (supervisão e cancelamento continuam; código de
     * saída não é mais observável). Devolve quantos registros foram tocados.
     * <p>
     * F-AGT-01: um registro em {@code STARTING} com a posse gravada no lançamento ({@link #PROCESS_REF}) é de um processo
     * que FOI lançado (a gravação de RUNNING não chegou ao disco): é tratado como RUNNING com essa posse — adotado se ainda
     * for o mesmo processo (a vaga única de execução continua ocupada), senão fechado como {@code OUTCOME_UNKNOWN}.
     */
    public int reconcile() {
        flushUnsaved();
        int n = 0;
        for (ExecutionRecord r : store.all()) {
            if (r.state().terminal() || unsaved.containsKey(r.id()) || isActive(r.id())) continue;
            n++;
            store.event(r.id(), clock.instant(), "RECONCILE", Map.of("state", r.state().name()));
            if (r.state() == ExecutionState.STARTING) {
                Optional<ProcessOwner> launched = processRef(r.id());
                if (launched.isPresent()) {
                    ProcessOwner o = launched.get();
                    r = r.withOwner(o).moveTo(ExecutionState.RUNNING, null, o.startMillis() > 0 ? Instant.ofEpochMilli(o.startMillis()) : clock.instant());
                }
            }
            if (r.state() == ExecutionState.RUNNING && r.owner() != null && r.owner().alive()) {
                synchronized (lock) {
                    if (active == null) {
                        ExecutionRecord adopted = r.adoptedAfterRestart();
                        Active a = new Active(adopted, null, pkgOf(r), r.kind(), outputOf(r), DEFAULT_TIMEOUT);
                        persist(a);
                        active = a;
                        Thread t = new Thread(() -> supervise(a), "nekkora-agent-execucao");
                        t.setDaemon(true);
                        t.start();
                        store.event(r.id(), clock.instant(), "ADOPTED", Map.of("pid", r.owner().pid()));
                        continue;
                    }
                }
            }
            if (r.state() == ExecutionState.RUNNING) {
                // A02: o processo terminou enquanto o Agent estava fora. Um protocolo publicado, COMPLETO e COERENTE decide
                // o desfecho (sem código de saída: o fim não foi observado); qualquer dúvida mantém OUTCOME_UNKNOWN
                ProtocolEvidence.Verdict v = verdict(r);
                if (v.accepted()) {
                    ExecutionPackage pkg = verifiedCopy(r);
                    Launch.Outcome o = Launch.classifyVerified(pkg, v);
                    ExecutionRecord closed = r.withExit(null, v.reported().counts(), r.outputBytes()).moveTo(o.state(), o.code(), clock.instant())
                            .recoveredFromProtocol();
                    saveOrKeep(closed);
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("exit", "none");
                    f.put("protocol", true);
                    f.put("recovered", true);
                    if (o.code() != null) f.put("code", o.code().code());
                    store.event(r.id(), clock.instant(), o.state().name(), f);
                    last = closed;
                    continue;
                }
                store.event(r.id(), clock.instant(), "PROTOCOL_REJECTED", Map.of("reason", v.rejection().name()));
            }
            ExecutionState to = r.state() == ExecutionState.READY ? ExecutionState.BLOCKED : ExecutionState.OUTCOME_UNKNOWN;
            ExecutionRecord closed = r.moveTo(to, ErrorCode.EXEC_AGENT_RESTARTED, clock.instant());
            if (r.state() == ExecutionState.RUNNING && r.kind() == ExecutionPackage.Kind.STUDIO_PYTHON) closed = withPartialProgress(closed);
            saveOrKeep(closed);
            store.event(r.id(), clock.instant(), to.name(), Map.of("code", ErrorCode.EXEC_AGENT_RESTARTED.code()));
            last = closed;
        }
        return n;
    }

    private boolean isActive(ExecutionId id) {
        synchronized (lock) {
            return active != null && active.record.id().equals(id);
        }
    }

    /** Pacote da cópia da execução (para ler o protocolo); vazio se a cópia não for mais legível. */
    private ExecutionPackage pkgOf(ExecutionRecord r) {
        try {
            return ExecutionPackage.inspect(store.dir(r.id()).resolve("pacote"));
        } catch (ExecRefused e) {
            return null;
        }
    }

    /**
     * A03: o progresso parcial conferido do checkpoint do Framework (ou o motivo de não haver) anexado ao registro, com um
     * evento só de números e códigos. Nunca muda o estado.
     */
    private ExecutionRecord withPartialProgress(ExecutionRecord r) {
        ExecutionRecord.PartialProgress p = CheckpointProgress.read(store.output(r.id()).orElse(null));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("available", p.available());
        if (p.available()) {
            f.put("transactions", p.total());
            f.put("delivered", p.delivered());
        } else {
            f.put("problem", p.problem());
        }
        store.event(r.id(), clock.instant(), "PARTIAL_PROGRESS", f);
        return r.withProgress(p);
    }

    /** A cópia da execução, só se ainda for exatamente o pacote registrado (mesma impressão digital); senão vazio. */
    private ExecutionPackage verifiedCopy(ExecutionRecord r) {
        ExecutionPackage p = pkgOf(r);
        return p != null && p.fingerprint().equals(r.fingerprint()) ? p : null;
    }

    /** A02: o protocolo da execução conferido (cópia íntegra do pacote, pasta de saída validada, posterior ao lançamento). */
    private ProtocolEvidence.Verdict verdict(ExecutionRecord r) {
        return ProtocolEvidence.verify(verifiedCopy(r), store.output(r.id()).orElse(null), r.started());
    }

    /** A pasta de saída anotada no início (sem ela, a adoção só pode terminar em OUTCOME_UNKNOWN). */
    private Path outputOf(ExecutionRecord r) {
        try {
            return Path.of(Files.readString(store.dir(r.id()).resolve(OUTPUT_REF), java.nio.charset.StandardCharsets.UTF_8).strip());
        } catch (IOException | RuntimeException e) {
            return store.dir(r.id()).resolve("sem-saida");
        }
    }

    static boolean inside(Path child, Path parent) {
        try {
            Path c = child.toAbsolutePath().normalize();
            Path p = parent.toAbsolutePath().normalize();
            return c.startsWith(p);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Encerramento do Agent: a execução ativa é cancelada (a árvore possuída é encerrada) e o registro fechado; estados
     * ainda não gravados têm uma última tentativa.
     */
    @Override
    public void close() {
        Active a;
        synchronized (lock) {
            a = active;
        }
        if (a != null) {
            a.cancel.set(true);
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && running()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        flushUnsaved();
    }
}
