package com.nekkora.agent.app;

import com.nekkora.agent.core.BuildInfo;
import com.nekkora.agent.core.ErrorJournal;
import com.nekkora.agent.core.MainViewModel;
import com.nekkora.agent.core.ShortcutState;
import com.nekkora.agent.core.TrayState;
import com.nekkora.agent.diagnostics.AgentLog;
import com.nekkora.agent.diagnostics.DiagnosticReport;
import com.nekkora.agent.exec.ExecutionController;
import com.nekkora.agent.obs.ActivityService;
import com.nekkora.agent.os.ShortcutLauncher;
import com.nekkora.agent.os.ShortcutPolicy;
import com.nekkora.agent.platform.PlatformService;
import com.nekkora.agent.storage.AgentConfig;
import com.nekkora.agent.storage.AgentDataDir;
import com.nekkora.agent.storage.ConfigStore;
import com.nekkora.agent.storage.InstanceLock;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Serviços de uma instância proprietária do Agent (trava adquirida). Sem Swing: a interface consome
 * este contexto. Métodos que tocam em arquivos devem ser chamados fora da EDT.
 */
public final class AgentContext implements AutoCloseable {
    private final BuildInfo build;
    private final AgentDataDir dataDir;
    private final InstanceLock lock;
    private final AgentLog log;
    private final ErrorJournal journal;
    private final ConfigStore config;
    private final Optional<String> machineName;
    private final AgentOptions options;
    private final Clock clock;
    private final Instant sessionStart;
    private final Optional<Instant> processStart;
    private final ShortcutLauncher launcher;
    private final ShortcutPolicy.FileProbe probe;
    private final boolean windows;
    private final ExecutionController execution;
    private final ActivityService activity;
    private final PlatformService platform;
    private volatile TrayState tray = TrayState.PENDING;
    private volatile Long startupMillis;
    private boolean closed;

    AgentContext(BuildInfo build, AgentDataDir dataDir, InstanceLock lock, AgentLog log, ErrorJournal journal, ConfigStore config,
            Optional<String> machineName, AgentOptions options, Clock clock, Optional<Instant> processStart,
            ShortcutLauncher launcher, ShortcutPolicy.FileProbe probe, boolean windows, ExecutionController execution, ActivityService activity,
            PlatformService platform) {
        this.build = build;
        this.dataDir = dataDir;
        this.lock = lock;
        this.log = log;
        this.journal = journal;
        this.config = config;
        this.machineName = machineName;
        this.options = options;
        this.clock = clock;
        this.sessionStart = clock.instant();
        this.processStart = processStart;
        this.launcher = launcher;
        this.probe = probe;
        this.windows = windows;
        this.execution = execution;
        this.activity = activity;
        this.platform = platform;
    }

    /** Transporte para a Platform (AGENT-PLATFORM-001): só leitura dos fatos locais; a tela lê o retrato dele. */
    public PlatformService platform() {
        return platform;
    }

    /** Atividade local das execuções (OBS-001): linha do tempo e histórico, só leitura. */
    public ActivityService activity() {
        return activity;
    }

    /** Execução local supervisionada (DESKTOP-001). */
    public ExecutionController execution() {
        return execution;
    }

    public BuildInfo build() {
        return build;
    }

    public AgentDataDir dataDir() {
        return dataDir;
    }

    public AgentLog log() {
        return log;
    }

    public ErrorJournal journal() {
        return journal;
    }

    public ConfigStore config() {
        return config;
    }

    public Optional<String> machineName() {
        return machineName;
    }

    public AgentOptions options() {
        return options;
    }

    public Clock clock() {
        return clock;
    }

    public ShortcutLauncher launcher() {
        return launcher;
    }

    public boolean windows() {
        return windows;
    }

    public TrayState tray() {
        return tray;
    }

    public void setTray(TrayState state) {
        this.tray = state;
    }

    /** Milissegundos entre o início do processo e a janela visível; registra uma única vez. */
    public Optional<Long> markReady() {
        if (startupMillis != null || processStart.isEmpty()) return Optional.ofNullable(startupMillis);
        startupMillis = Math.max(0, Duration.between(processStart.get(), clock.instant()).toMillis());
        return Optional.of(startupMillis);
    }

    public boolean lockHeld() {
        return lock.isHeld();
    }

    // ----- visão (fora da EDT: confere a existência do executável) -----

    public MainViewModel.ShortcutView studioView(AgentConfig c) {
        ShortcutPolicy.Check<Path> check = ShortcutPolicy.studioExecutable(c.studioExecutable(), windows, probe);
        if (check instanceof ShortcutPolicy.Valid<Path> v) {
            return new MainViewModel.ShortcutView(ShortcutState.VALID, null, String.valueOf(v.target().getFileName()));
        }
        return new MainViewModel.ShortcutView(check.state(), ((ShortcutPolicy.Rejected<Path>) check).reason().text(), null);
    }

    public MainViewModel.ShortcutView platformView(AgentConfig c) {
        ShortcutPolicy.Check<URI> check = ShortcutPolicy.platformUrl(c.platformUrl());
        if (check instanceof ShortcutPolicy.Valid<URI> v) {
            return new MainViewModel.ShortcutView(ShortcutState.VALID, null, v.target().getHost());
        }
        return new MainViewModel.ShortcutView(check.state(), ((ShortcutPolicy.Rejected<URI>) check).reason().text(), null);
    }

    public MainViewModel viewModel() {
        AgentConfig c = config.current();
        return MainViewModel.from(new MainViewModel.Inputs(machineName, build, tray, config.state().code(),
                log.state() == AgentLog.State.UNAVAILABLE, studioView(c), platformView(c)));
    }

    public DiagnosticReport.Facts diagnosticFacts() {
        AgentConfig c = config.current();
        return new DiagnosticReport.Facts(build,
                System.getProperty("java.version", ""), System.getProperty("java.vendor", ""),
                System.getProperty("os.name", ""), System.getProperty("os.version", ""), System.getProperty("os.arch", ""),
                machineName, tray, config.state(), log.state(), dataDir.kind(), lock.isHeld(),
                studioView(c).state(), platformView(c).state(), journal.snapshot(), sessionStart, startupMillis, executionFacts(), platformFacts());
    }

    /** Fatos do canal com a Platform para o diagnóstico (lista fechada, sem segredo nem endereço). */
    private DiagnosticReport.PlatformFacts platformFacts() {
        com.nekkora.agent.platform.PlatformStatus s = platform.status();
        return new DiagnosticReport.PlatformFacts(s.link().name(), s.paired() ? s.connectivity().name() : "SEM_VINCULO", s.agentId(), s.workerId(), s.pendingRuns(),
                s.pendingEvents(), s.blocked().size(), s.incompleteRuns(), s.authorizations(), s.secretStoreAvailable(),
                s.problem() == null ? null : s.problem().code());
    }

    /** Fatos da execução local para o diagnóstico (lista fechada; fora da EDT: consulta a sessão). */
    private DiagnosticReport.ExecFacts executionFacts() {
        com.nekkora.agent.exec.SessionProbe.Session s = execution.session().probe();
        String session = s.usable() ? "INTERATIVA" : s.problem() == com.nekkora.agent.core.ErrorCode.EXEC_SESSION_LOCKED ? "BLOQUEADA" : "INDISPONIVEL";
        int records = execution.store().all().size();
        return execution.current().map(snap -> {
            com.nekkora.agent.exec.ExecutionRecord r = snap.record();
            return new DiagnosticReport.ExecFacts(r.state().name(), r.code() == null ? null : r.code().code(), r.kind() == null ? null : r.kind().name(),
                    snap.running(), snap.elapsedMillis(), r.suspensions(), r.locks(), r.adopted(), records, session);
        }).orElse(new DiagnosticReport.ExecFacts("NENHUMA", null, null, false, null, 0, 0, false, records, session));
    }

    /** Fecha o log e libera a trava (idempotente). O arquivo da trava permanece. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        execution.close(); // uma execução ainda ativa é cancelada (só a árvore possuída) antes de liberar a trava
        activity.close(); // o que faltar ler é retomado na próxima abertura, a partir das posições gravadas
        platform.close(); // o progresso já está no disco; a entrega continua na próxima abertura de settledThrough + 1
        log.close();
        lock.close();
    }
}
