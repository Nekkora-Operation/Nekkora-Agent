package com.nekkora.agent.ui;

import com.nekkora.agent.app.AgentContext;
import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.core.MainViewModel;
import com.nekkora.agent.core.ShortcutState;
import com.nekkora.agent.diagnostics.AgentLog.Field;
import com.nekkora.agent.diagnostics.DiagnosticReport;
import com.nekkora.agent.diagnostics.LogEvent;
import com.nekkora.agent.os.ShortcutLauncher;
import com.nekkora.agent.os.ShortcutPolicy;
import com.nekkora.agent.os.TrayController;
import com.nekkora.agent.storage.AgentConfig;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.ConfigStore;
import com.nekkora.tokens.NekkoraTheme;
import com.nekkora.tokens.NekkoraThemes;
import com.nekkora.ui.Nekkora;
import com.nekkora.ui.brand.NekkoraLogo;
import com.nekkora.ui.core.Severity;
import com.nekkora.ui.feedback.NekkoraToast;
import com.nekkora.ui.file.NekkoraFileDialogs;
import com.nekkora.ui.theme.ThemeManager;
import java.awt.Frame;
import java.awt.Window;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

/**
 * Apresentação e ciclo de vida da instância proprietária: janela, bandeja, diálogos, atalhos e saída.
 * Roda na EDT; trabalho com arquivos, processos e navegador vai para a thread {@code nekkora-agent-io}.
 * Nada aqui é periódico: com a janela oculta não há timers nem animações em andamento.
 */
public final class AgentApp {

    /** Encerramento do processo (substituível em testes de desktop, que não podem chamar System.exit). */
    @FunctionalInterface
    public interface Terminator {
        void terminate(int status);
    }

    static final String HIDDEN_NOTICE = "O Nekkora Agent continua aberto na bandeja do sistema. Para encerrar, use “Sair” no menu do ícone.";
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final AgentContext ctx;
    private final TrayController.Port trayPort;
    private final Terminator terminator;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nekkora-agent-io");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean exiting = new AtomicBoolean();
    private MainWindow window;
    private TrayController tray;
    private JDialog activeDialog;
    private int initialNotices = -1;

    public AgentApp(AgentContext ctx, TrayController.Port trayPort, Terminator terminator) {
        this.ctx = Objects.requireNonNull(ctx);
        this.trayPort = Objects.requireNonNull(trayPort);
        this.terminator = Objects.requireNonNull(terminator);
    }

    /** Monta e mostra a janela, depois instala a bandeja. Chamar na EDT. {@code initial} foi calculado fora da EDT. */
    public void start(MainViewModel initial) {
        Nekkora.install(themeFor(ctx.config().current().themeId()));
        window = new MainWindow(listener());
        window.render(initial);
        window.fitToScreen();
        window.setVisible(true);
        window.focusFirstAction();

        initialNotices = initial.notices().size();
        tray = TrayController.install(trayPort, ctx.options().trayDisabled(), NekkoraLogo::appIcon, trayActions());
        ctx.setTray(tray.state());
        if (tray.state().code() == null) {
            ctx.log().info(LogEvent.TRAY_INSTALLED);
        } else {
            ctx.log().warn(LogEvent.TRAY_UNAVAILABLE, tray.state().code(),
                    Field.of("exception", tray.failureType() == null ? "none" : tray.failureType()));
        }
        refresh();
    }

    static NekkoraTheme themeFor(String id) {
        return NekkoraThemes.byId(id).orElse(NekkoraThemes.defaultTheme());
    }

    private MainWindow.Listener listener() {
        return new MainWindow.Listener() {
            @Override
            public void closeRequested() {
                AgentApp.this.closeRequested();
            }

            @Override
            public void opened() {
                ctx.markReady().ifPresent(ms -> ctx.log().info(LogEvent.LIFE_READY, Field.of("startup_ms", ms)));
            }

            @Override
            public void openStudio() {
                AgentApp.this.openStudio();
            }

            @Override
            public void openPlatform() {
                AgentApp.this.openPlatform();
            }

            @Override
            public void settings() {
                openSettings();
            }

            @Override
            public void diagnostics() {
                openDiagnostics();
            }

            @Override
            public void exit() {
                AgentApp.this.exit();
            }
        };
    }

    private TrayController.Actions trayActions() {
        return new TrayController.Actions() {
            @Override
            public void open() {
                restore();
            }

            @Override
            public void settings() {
                restore();
                openSettings();
            }

            @Override
            public void diagnostics() {
                restore();
                openDiagnostics();
            }

            @Override
            public void exit() {
                AgentApp.this.exit();
            }
        };
    }

    // ----- janela -----

    void closeRequested() {
        if (exiting.get()) return;
        if (ctx.tray().canHideToTray()) {
            window.setVisible(false);
            ctx.log().info(LogEvent.LIFE_WINDOW_HIDDEN, Field.of("to", "tray"));
            tray.notifyOnce("Nekkora Agent", HIDDEN_NOTICE);
        } else {
            // sem bandeja, a barra de tarefas é o caminho de volta: minimiza em vez de esconder
            window.setExtendedState(window.getExtendedState() | Frame.ICONIFIED);
            ctx.log().info(LogEvent.LIFE_WINDOW_HIDDEN, Field.of("to", "taskbar"));
        }
    }

    /** Mostra, restaura e traz a janela para a frente. */
    public void restore() {
        if (exiting.get() || window == null) return;
        boolean wasHidden = !window.isVisible() || (window.getExtendedState() & Frame.ICONIFIED) != 0;
        window.setVisible(true);
        window.setExtendedState(window.getExtendedState() & ~Frame.ICONIFIED);
        window.toFront();
        window.requestFocus();
        if (activeDialog != null && activeDialog.isDisplayable()) activeDialog.toFront();
        if (wasHidden) ctx.log().info(LogEvent.LIFE_WINDOW_RESTORED);
    }

    private void refresh() {
        background(ctx::viewModel, vm -> {
            if (window == null) return;
            window.render(vm);
            // o aviso da bandeja só é conhecido depois de a janela aparecer: reajusta a altura uma única vez
            if (initialNotices >= 0 && vm.notices().size() != initialNotices) window.fitToScreen();
            initialNotices = -1;
        });
    }

    // ----- atalhos -----

    void openStudio() {
        AgentConfig c = ctx.config().current();
        background(() -> ctx.launcher().openStudio(c.studioExecutable()), r -> shortcutDone("Studio", r,
                LogEvent.SHORTCUT_STUDIO_STARTED, LogEvent.SHORTCUT_STUDIO_FAILED, ErrorCode.STUDIO_INVALID));
    }

    void openPlatform() {
        AgentConfig c = ctx.config().current();
        background(() -> ctx.launcher().openPlatform(c.platformUrl()), r -> shortcutDone("Platform", r,
                LogEvent.SHORTCUT_PLATFORM_OPENED, LogEvent.SHORTCUT_PLATFORM_FAILED, ErrorCode.PLATFORM_INVALID));
    }

    private void shortcutDone(String what, ShortcutLauncher.Result r, LogEvent ok, LogEvent failed, ErrorCode invalid) {
        switch (r) {
            case ShortcutLauncher.Opened o -> {
                ctx.log().info(ok);
                toast(Severity.INFO, what.equals("Studio") ? "Studio iniciado" : "Platform aberta no navegador", null);
            }
            case ShortcutLauncher.Refused f -> {
                if (f.reason() == ShortcutPolicy.Reason.NOT_CONFIGURED) ctx.log().info(LogEvent.SHORTCUT_REJECTED, Field.of("target", what));
                else ctx.log().warn(LogEvent.SHORTCUT_REJECTED, invalid, Field.of("target", what), Field.of("reason", f.reason().name()));
                toast(Severity.WARNING, what + " indisponível", capitalize(f.reason().text()) + ".");
            }
            case ShortcutLauncher.Failed f -> {
                ctx.log().error(failed, f.code(), Field.of("exception", f.exceptionType()));
                toast(Severity.ERROR, "Não foi possível abrir " + (what.equals("Studio") ? "o Studio" : "a Platform"),
                        f.code().description() + " (" + f.code().code() + ")");
            }
        }
        refresh();
    }

    // ----- configurações -----

    void openSettings() {
        if (exiting.get() || bringActiveDialogToFront()) return;
        SettingsDialog[] holder = new SettingsDialog[1];
        holder[0] = new SettingsDialog(window, ctx.config().current(), ctx.config().state(), ctx.windows(), req -> save(holder[0], req));
        activeDialog = holder[0];
        holder[0].showDialog();
        activeDialog = null;
    }

    private void save(SettingsDialog dialog, SettingsDialog.Request req) {
        String oldTheme = ctx.config().current().themeId();
        background(() -> {
            AgentConfig c = req.config();
            if (c.studioExecutable() != null
                    && ShortcutPolicy.studioExecutable(c.studioExecutable(), ctx.windows(), ShortcutPolicy.FileProbe.DEFAULT) instanceof ShortcutPolicy.Rejected<?> r
                    && r.reason() == ShortcutPolicy.Reason.PATH_MISSING) {
                return null; // arquivo inexistente: nada é gravado
            }
            return req.preserveIncompatible() ? ctx.config().preserveAndSave(c) : ctx.config().save(c);
        }, result -> {
            boolean dialogOpen = dialog.isDisplayable();
            if (result == null) {
                if (dialogOpen) dialog.saveFinished(false, true, null);
                return;
            }
            switch (result) {
                case ConfigStore.Saved s -> {
                    ctx.log().info(LogEvent.CONFIG_SAVED, Field.of("preserved_copy", s.preservedAs() != null));
                    if (dialogOpen) dialog.saveFinished(true, false, null);
                    if (!s.config().themeId().equals(oldTheme)) {
                        ThemeManager.setTheme(themeFor(s.config().themeId()));
                        ctx.log().info(LogEvent.THEME_CHANGED, Field.of("theme", s.config().themeId()));
                    }
                    toast(Severity.SUCCESS, "Configurações salvas",
                            s.preservedAs() == null ? null : "O arquivo anterior foi guardado como " + s.preservedAs() + ".");
                }
                case ConfigStore.SaveFailed f -> {
                    ctx.log().error(LogEvent.CONFIG_SAVE_FAILED, f.code(), Field.of("exception", f.exceptionType()));
                    String msg = f.code().description() + " (" + f.code().code() + "). A configuração anterior continua em uso."
                            + (f.preservedAs() == null ? "" : " O arquivo incompatível foi guardado como " + f.preservedAs() + ".");
                    if (dialogOpen) dialog.saveFinished(false, false, msg);
                    else toast(Severity.ERROR, "Não foi possível salvar", msg);
                }
            }
            refresh();
        });
    }

    // ----- diagnóstico -----

    void openDiagnostics() {
        if (exiting.get() || bringActiveDialogToFront()) return;
        background(ctx::diagnosticFacts, facts -> {
            if (exiting.get() || bringActiveDialogToFront()) return;
            DiagnosticsDialog[] holder = new DiagnosticsDialog[1];
            holder[0] = new DiagnosticsDialog(window, facts, ctx.dataDir().displayLogsLocation(), ctx.clock(),
                    text -> export(holder[0], text));
            activeDialog = holder[0];
            holder[0].showDialog();
            activeDialog = null;
        });
    }

    private void export(DiagnosticsDialog dialog, String text) {
        String name = "nekkora-agent-diagnostico-" + FILE_STAMP.format(ctx.clock().instant()) + ".json";
        Path target = NekkoraFileDialogs.saveFile(dialog, "Exportar diagnóstico", name, "json").orElse(null);
        if (target == null) return;
        dialog.exportStarted();
        background(() -> {
            try {
                AtomicWriter.DEFAULT.write(target, text.getBytes(StandardCharsets.UTF_8));
                return null;
            } catch (Exception e) {
                return e.getClass().getSimpleName();
            }
        }, failureType -> {
            if (failureType == null) {
                ctx.log().info(LogEvent.DIAGNOSTIC_EXPORTED, Field.of("machine_name", dialog.includeNameBox().isSelected()));
                dialog.exportFinished(true, null);
                toast(Severity.SUCCESS, "Diagnóstico exportado", "Arquivo " + target.getFileName() + " gravado.");
            } else {
                ErrorCode code = ErrorCode.DIAGNOSTIC_EXPORT_FAILED;
                ctx.log().error(LogEvent.DIAGNOSTIC_EXPORT_FAILED, code, Field.of("exception", failureType));
                dialog.exportFinished(false, code.description() + " (" + code.code() + "). Escolha outra pasta e tente de novo.");
            }
        });
    }

    // ----- saída -----

    /** Encerra: remove o ícone, fecha janelas e recursos, libera a trava e termina o processo. Idempotente. */
    public void exit() {
        if (!exiting.compareAndSet(false, true)) return;
        ctx.log().info(LogEvent.LIFE_EXIT_REQUESTED);
        if (tray != null) {
            tray.remove();
            ctx.log().info(LogEvent.TRAY_REMOVED);
        }
        for (Window w : Window.getWindows()) w.dispose();
        io.shutdownNow();
        try {
            io.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ctx.log().info(LogEvent.LOCK_RELEASED);
        ctx.log().info(LogEvent.LIFE_EXIT, Field.of("status", 0));
        ctx.close();
        terminator.terminate(0);
    }

    public boolean isExiting() {
        return exiting.get();
    }

    // ----- utilidades -----

    private boolean bringActiveDialogToFront() {
        if (activeDialog != null && activeDialog.isDisplayable()) {
            activeDialog.toFront();
            return true;
        }
        return false;
    }

    private <T> void background(Supplier<T> work, java.util.function.Consumer<T> onEdt) {
        if (io.isShutdown()) return;
        io.execute(() -> {
            T value;
            try {
                value = work.get();
            } catch (RuntimeException e) {
                ctx.log().error(LogEvent.UNEXPECTED_ERROR, ErrorCode.UNEXPECTED, Field.exception(e));
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (!exiting.get()) onEdt.accept(value);
            });
        });
    }

    private void toast(Severity s, String title, String message) {
        if (window != null && window.isShowing()) NekkoraToast.show(window, s, title, message);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // acesso para testes de desktop
    MainWindow window() {
        return window;
    }

    TrayController tray() {
        return tray;
    }

    JDialog activeDialog() {
        return activeDialog;
    }

    AgentContext context() {
        return ctx;
    }

    ShortcutState studioState() {
        return ctx.studioView(ctx.config().current()).state();
    }
}
