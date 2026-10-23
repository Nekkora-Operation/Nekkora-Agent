package com.nekkora.agent.app;

import com.nekkora.agent.core.BuildInfo;
import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.core.ErrorJournal;
import com.nekkora.agent.core.MachineName;
import com.nekkora.agent.core.SafeText;
import com.nekkora.agent.diagnostics.AgentLog;
import com.nekkora.agent.diagnostics.AgentLog.Field;
import com.nekkora.agent.diagnostics.LogEvent;
import com.nekkora.agent.exec.ApprovalStore;
import com.nekkora.agent.exec.ExecutionController;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.exec.SessionProbe;
import com.nekkora.agent.obs.ActivityService;
import com.nekkora.agent.os.ShortcutLauncher;
import com.nekkora.agent.os.ShortcutPolicy;
import com.nekkora.agent.storage.AgentDataDir;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.ConfigStore;
import com.nekkora.agent.storage.InstanceLock;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Inicialização fora da EDT: diretório de dados → trava → logs → configuração → nome da máquina.
 * A trava vem antes de qualquer gravação: uma segunda instância não toca em logs nem configuração.
 */
public final class AgentBootstrap {

    /** Resultado da inicialização. */
    public sealed interface Start permits Ready, AlreadyRunning, Fatal {}

    public record Ready(AgentContext context) implements Start {}

    /** Outra instância é a proprietária da trava neste diretório. */
    public record AlreadyRunning() implements Start {}

    /** Não é possível continuar com segurança (sem diretório ou sem trava). */
    public record Fatal(ErrorCode code) implements Start {}

    /** Dependências do ambiente (substituíveis em testes). */
    public record Environment(
            AgentDataDir.Location location,
            Map<String, String> env,
            Clock clock,
            Optional<Instant> processStart,
            ShortcutLauncher launcher,
            ShortcutPolicy.FileProbe probe,
            boolean windows,
            AtomicWriter writer) {

        public static Environment system() {
            return new Environment(AgentDataDir.locateCurrent(), System.getenv(), Clock.systemUTC(),
                    ProcessHandle.current().info().startInstant(), ShortcutLauncher.system(), ShortcutPolicy.FileProbe.DEFAULT,
                    ShortcutPolicy.isWindows(), AtomicWriter.DEFAULT);
        }
    }

    private AgentBootstrap() {}

    public static Start start(AgentOptions options, Environment e) {
        AgentDataDir dir;
        try {
            dir = AgentDataDir.open(e.location());
        } catch (IOException | SecurityException ex) {
            return new Fatal(ErrorCode.DATA_DIR_UNAVAILABLE);
        }
        InstanceLock lock;
        switch (InstanceLock.tryAcquire(dir.lockFile())) {
            case InstanceLock.Acquired a -> lock = a.lock();
            case InstanceLock.HeldElsewhere h -> {
                return new AlreadyRunning();
            }
            case InstanceLock.Failed f -> {
                return new Fatal(ErrorCode.LOCK_FAILED);
            }
        }

        ErrorJournal journal = new ErrorJournal(e.clock());
        AgentLog log = AgentLog.open(dir.logsDir(), e.clock(), journal);
        BuildInfo build = BuildInfo.load();
        log.info(LogEvent.LIFE_START,
                Field.of("version", build.version()),
                Field.of("build", build.commitShort()),
                Field.of("dirty", build.dirty()),
                Field.of("design_system", shortHash(build.designSystemCommit())),
                Field.of("java", System.getProperty("java.version", "")),
                Field.of("os", SafeText.token(System.getProperty("os.name", ""), 30)),
                Field.of("os_version", System.getProperty("os.version", "")),
                Field.of("arch", System.getProperty("os.arch", "")),
                Field.of("data_dir", dir.kind().diagnosticValue()),
                Field.of("tray_option", options.trayDisabled() ? "off" : "on"),
                Field.of("ignored_args", options.ignoredArguments()),
                Field.of("pid", ProcessHandle.current().pid()));
        log.info(LogEvent.LOCK_ACQUIRED);

        ConfigStore config = new ConfigStore(dir.configFile(), e.writer(), e.clock());
        ConfigStore.Load load = config.load();
        if (load.state().code() != null) {
            log.warn(LogEvent.CONFIG_PROBLEM, load.state().code(), Field.of("state", load.state().diagnosticValue()));
        } else {
            log.info(LogEvent.CONFIG_LOADED, Field.of("state", load.state().diagnosticValue()));
        }

        Optional<String> machine = MachineName.resolve(e.env());
        if (machine.isEmpty()) log.warn(LogEvent.MACHINE_NAME_UNAVAILABLE, ErrorCode.MACHINE_NAME_UNAVAILABLE);

        // DESKTOP-001: execução local supervisionada. Depois de um reinício, execuções não terminadas são reconciliadas
        // (adotadas se o processo ainda for o mesmo; senão fechadas sem inventar resultado) — nada é relançado
        RunStore runs = new RunStore(dir.root().resolve("execucoes"), e.writer());
        ExecutionController execution = new ExecutionController(runs,
                new ApprovalStore(dir.root().resolve("execucao").resolve("aprovacoes.json"), e.writer()), SessionProbe.system(e.windows(), e.env()),
                e.clock(), dir.root().resolve("execucao"));
        // OBS-001: atividade local (só observa: lê os eventos já gravados pelo supervisor e pelo Framework, em segundo plano)
        ActivityService activity = new ActivityService(runs, e.clock());
        execution.addListener(activity.controllerListener());
        int reconciled = execution.reconcile();
        activity.start(); // retoma a coleta pendente (inclusive da execução adotada) a partir das posições gravadas
        if (reconciled > 0) log.warn(LogEvent.EXEC_RECONCILED, ErrorCode.EXEC_AGENT_RESTARTED, Field.of("records", reconciled),
                Field.of("adopted", execution.running()));

        // a abertura do arquivo de log é assíncrona: espera o resultado para a primeira tela já mostrar o estado real
        log.flush(1000);
        return new Ready(new AgentContext(build, dir, lock, log, journal, config, machine, options, e.clock(),
                e.processStart(), e.launcher(), e.probe(), e.windows(), execution, activity));
    }

    private static String shortHash(String h) {
        return h.length() > 12 ? h.substring(0, 12) : h;
    }
}
