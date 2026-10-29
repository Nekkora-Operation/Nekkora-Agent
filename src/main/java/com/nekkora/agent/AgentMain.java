package com.nekkora.agent;

import com.nekkora.agent.app.AgentBootstrap;
import com.nekkora.agent.app.AgentContext;
import com.nekkora.agent.app.AgentOptions;
import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.core.MainViewModel;
import com.nekkora.agent.diagnostics.AgentLog.Field;
import com.nekkora.agent.diagnostics.LogEvent;
import com.nekkora.agent.os.TrayController;
import com.nekkora.agent.storage.IsolatedPreferencesFactory;
import com.nekkora.agent.ui.AgentApp;
import com.nekkora.agent.ui.StartupNotice;
import java.lang.reflect.InvocationTargetException;
import javax.swing.SwingUtilities;

/**
 * Ponto de entrada do Nekkora Agent.
 *
 * <p>Códigos de saída: 0 encerrado pelo usuário; 2 falha inicial (sem diretório de dados ou sem
 * trava); 3 outra instância já é a proprietária.
 */
public final class AgentMain {
    public static final int EXIT_OK = 0;
    public static final int EXIT_FATAL = 2;
    public static final int EXIT_ALREADY_RUNNING = 3;

    private AgentMain() {}

    public static void main(String[] args) throws InterruptedException, InvocationTargetException {
        // antes de qualquer uso de Preferences: o tema do Studio não é lido nem alterado (D-05)
        IsolatedPreferencesFactory.install();
        AgentOptions options = AgentOptions.parse(args);

        switch (AgentBootstrap.start(options, AgentBootstrap.Environment.system())) {
            case AgentBootstrap.AlreadyRunning a -> {
                SwingUtilities.invokeAndWait(StartupNotice::alreadyRunning);
                System.exit(EXIT_ALREADY_RUNNING);
            }
            case AgentBootstrap.Fatal f -> {
                SwingUtilities.invokeAndWait(() -> StartupNotice.fatal(f.code()));
                System.exit(EXIT_FATAL);
            }
            case AgentBootstrap.Ready r -> run(r.context());
        }
    }

    private static void run(AgentContext ctx) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                ctx.log().error(LogEvent.UNEXPECTED_ERROR, ErrorCode.UNEXPECTED, Field.exception(e)));
        // calculado fora da EDT (confere a existência do executável configurado)
        MainViewModel initial = ctx.viewModel();
        AgentApp app = new AgentApp(ctx, TrayController.SYSTEM, System::exit);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!app.isExiting()) ctx.log().info(LogEvent.LIFE_SHUTDOWN_HOOK);
            ctx.close();
        }, "nekkora-agent-shutdown"));
        SwingUtilities.invokeLater(() -> app.start(initial));
    }
}
