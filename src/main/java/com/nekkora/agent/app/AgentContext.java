package com.nekkora.agent.app;

import com.nekkora.agent.core.BuildInfo;
import com.nekkora.agent.core.ErrorJournal;
import com.nekkora.agent.core.MainViewModel;
import com.nekkora.agent.core.ShortcutState;
import com.nekkora.agent.core.TrayState;
import com.nekkora.agent.diagnostics.AgentLog;
import com.nekkora.agent.diagnostics.DiagnosticReport;
import com.nekkora.agent.os.ShortcutLauncher;
import com.nekkora.agent.os.ShortcutPolicy;
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
    private volatile TrayState tray = TrayState.PENDING;
    private volatile Long startupMillis;
    private boolean closed;

    AgentContext(BuildInfo build, AgentDataDir dataDir, InstanceLock lock, AgentLog log, ErrorJournal journal, ConfigStore config,
            Optional<String> machineName, AgentOptions options, Clock clock, Optional<Instant> processStart,
            ShortcutLauncher launcher, ShortcutPolicy.FileProbe probe, boolean windows) {
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
                studioView(c).state(), platformView(c).state(), journal.snapshot(), sessionStart, startupMillis);
    }

    /** Fecha o log e libera a trava (idempotente). O arquivo da trava permanece. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        log.close();
        lock.close();
    }
}
