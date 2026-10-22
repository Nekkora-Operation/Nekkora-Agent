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
 *       registro sem inventar resultado; nada é relançado.</li>
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

    /** O que a interface vê da execução ativa ou da última. */
    public record Snapshot(ExecutionRecord record, long elapsedMillis, boolean running) {}

    public interface Listener {
        void changed(Snapshot s);
    }

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(60);
    /** Arquivo da pasta da execução com o caminho da saída (local; fora do registro e do diagnóstico). */
    static final String OUTPUT_REF = "saida.ref";
    /** Salto do relógio de parede entre duas verificações acima disto = a máquina parou (suspensão/hibernação). */
    static final Duration SUSPENSION_GAP = Duration.ofSeconds(20);

    private final RunStore store;
    private final ApprovalStore approvals;
    private final SessionProbe session;
    private final Clock clock;
    private final Path agentDir;
    private final Duration tick;
    private final Duration sessionEvery;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private Active active;
    private volatile ExecutionRecord last;

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
        this.store = store;
        this.approvals = approvals;
        this.session = session;
        this.clock = clock;
        this.agentDir = agentDir;
        this.tick = tick;
        this.sessionEvery = sessionEvery;
    }

    public void addListener(Listener l) {
        listeners.add(l);
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
            if (active != null) return Optional.of(new Snapshot(active.record, elapsed(active), true));
        }
        ExecutionRecord l = last;
        return l == null ? Optional.empty() : Optional.of(new Snapshot(l, l.started() == null || l.ended() == null ? 0
                : Duration.between(l.started(), l.ended()).toMillis(), false));
    }

    // ------------------------------------------------------------------------------------------- início

    /**
     * Confere tudo e lança (fora da EDT). Recusa sem lançar → {@link ExecRefused} (o registro fica como {@code BLOCKED},
     * exceto quando outra execução já está ativa — aí nenhum registro novo é criado).
     */
    public Snapshot start(Request req) throws ExecRefused {
        synchronized (lock) {
            if (active != null) throw new ExecRefused(ErrorCode.EXEC_ALREADY_RUNNING);
            ExecutionId id = ExecutionId.random();
            Instant now = clock.instant();
            ExecutionPackage pkg;
            try {
                pkg = ExecutionPackage.inspect(req.packageDir());
            } catch (ExecRefused e) {
                throw blocked(new ExecutionRecord(id, null, "pacote", "sha256:" + "0".repeat(64), ExecutionState.READY, null, now, null, null, null, null,
                        Map.of(), 0, 0, false, 0), e.code());
            }
            ExecutionRecord r = new ExecutionRecord(id, pkg.kind(), pkg.name(), pkg.fingerprint(), ExecutionState.READY, null, now, null, null, null, null,
                    Map.of(), 0, 0, false, 0);
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
            ProcessOwner owner = ProcessOwner.of(p, spec.executable().toString());
            r = r.withOwner(owner).moveTo(ExecutionState.RUNNING, null, clock.instant());
            store.save(r);
            store.event(id, clock.instant(), "RUNNING", Map.of("pid", owner.pid()));
            Active a = new Active(r, p, pkg, pkg.kind(), output, req.timeout());
            drain(a, p.getInputStream(), "saida");
            drain(a, p.getErrorStream(), "erros");
            active = a;
            Thread t = new Thread(() -> supervise(a), "nekkora-agent-execucao");
            t.setDaemon(true);
            t.start();
            Snapshot snap = new Snapshot(r, 0, true);
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
                saveQuietly(a.record);
            }
            lastWall = wall;
            if (done) break;
            if (System.nanoTime() - lastSession >= sessionEvery.toNanos()) {
                lastSession = System.nanoTime();
                boolean now = session.probe().locked();
                if (now != locked) {
                    locked = now;
                    if (now) a.record = a.record.withLock();
                    store.event(a.record.id(), clock.instant(), now ? "SESSION_LOCKED" : "SESSION_UNLOCKED", Map.of());
                    saveQuietly(a.record);
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
        if (forced != null) {
            state = forced;
            code = forcedCode;
        } else if (a.process == null) {
            // adotada depois de reinício: sem código de saída; só o protocolo publicado pode dizer o resultado
            Launch.Outcome o = rep.isPresent() && a.pkg != null ? Launch.classify(a.pkg, rep, 0) : new Launch.Outcome(ExecutionState.OUTCOME_UNKNOWN,
                    ErrorCode.EXEC_AGENT_RESTARTED);
            state = o.state();
            code = o.code() == null ? ErrorCode.EXEC_AGENT_RESTARTED : o.code();
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
        ExecutionRecord end = a.record.withExit(exit, rep.map(Launch.Reported::counts).orElse(Map.of()), a.bytes.get()).moveTo(state, code, clock.instant());
        saveQuietly(end);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("exit", exit == null ? "none" : exit);
        f.put("protocol", rep.isPresent());
        f.put("output_bytes", a.bytes.get());
        if (code != null) f.put("code", code.code());
        store.event(end.id(), clock.instant(), state.name(), f);
        synchronized (lock) {
            active = null;
            last = end;
        }
        store.prune();
        notifyListeners(new Snapshot(end, end.started() == null ? 0 : Duration.between(end.started(), end.ended()).toMillis(), false));
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

    private void saveQuietly(ExecutionRecord r) {
        try {
            store.save(r);
        } catch (ExecRefused e) {
            // registro em disco falhou: o estado em memória segue; a próxima gravação tenta de novo
        }
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
     */
    public int reconcile() {
        int n = 0;
        for (ExecutionRecord r : store.all()) {
            if (r.state().terminal()) continue;
            n++;
            store.event(r.id(), clock.instant(), "RECONCILE", Map.of("state", r.state().name()));
            if (r.state() == ExecutionState.RUNNING && r.owner() != null && r.owner().alive()) {
                synchronized (lock) {
                    if (active == null) {
                        ExecutionRecord adopted = r.adoptedAfterRestart();
                        saveQuietly(adopted);
                        Active a = new Active(adopted, null, pkgOf(r), r.kind(), outputOf(r), DEFAULT_TIMEOUT);
                        active = a;
                        Thread t = new Thread(() -> supervise(a), "nekkora-agent-execucao");
                        t.setDaemon(true);
                        t.start();
                        store.event(r.id(), clock.instant(), "ADOPTED", Map.of("pid", r.owner().pid()));
                        continue;
                    }
                }
            }
            ExecutionState to = r.state() == ExecutionState.READY ? ExecutionState.BLOCKED : ExecutionState.OUTCOME_UNKNOWN;
            ExecutionRecord closed = r.moveTo(to, ErrorCode.EXEC_AGENT_RESTARTED, clock.instant());
            saveQuietly(closed);
            store.event(r.id(), clock.instant(), to.name(), Map.of("code", ErrorCode.EXEC_AGENT_RESTARTED.code()));
            last = closed;
        }
        return n;
    }

    /** Pacote da cópia da execução (para ler o protocolo); vazio se a cópia não for mais legível. */
    private ExecutionPackage pkgOf(ExecutionRecord r) {
        try {
            return ExecutionPackage.inspect(store.dir(r.id()).resolve("pacote"));
        } catch (ExecRefused e) {
            return null;
        }
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

    /** Encerramento do Agent: a execução ativa é cancelada (a árvore possuída é encerrada) e o registro fechado. */
    @Override
    public void close() {
        Active a;
        synchronized (lock) {
            a = active;
        }
        if (a == null) return;
        a.cancel.set(true);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            synchronized (lock) {
                if (active == null) return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
