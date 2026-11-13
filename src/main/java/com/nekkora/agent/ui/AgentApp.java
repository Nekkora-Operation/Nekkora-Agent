package com.nekkora.agent.ui;

import com.nekkora.agent.app.AgentContext;
import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.core.MainViewModel;
import com.nekkora.agent.core.ShortcutState;
import com.nekkora.agent.diagnostics.AgentLog.Field;
import com.nekkora.agent.diagnostics.DiagnosticReport;
import com.nekkora.agent.diagnostics.LogEvent;
import com.nekkora.agent.exec.ExecRefused;
import com.nekkora.agent.exec.ExecutionController;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.ExecutionSetup;
import com.nekkora.agent.obs.ActivityEvent;
import com.nekkora.agent.obs.ActivityExport;
import com.nekkora.agent.obs.ActivityView;
import com.nekkora.agent.exec.PythonRuntime;
import com.nekkora.agent.os.ShortcutLauncher;
import com.nekkora.agent.os.ShortcutPolicy;
import com.nekkora.agent.os.TrayController;
import com.nekkora.agent.platform.Destination;
import com.nekkora.agent.platform.PlatformService;
import com.nekkora.agent.platform.PlatformStatus;
import com.nekkora.agent.storage.AgentConfig;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.ConfigStore;
import com.nekkora.tokens.NekkoraTheme;
import com.nekkora.tokens.NekkoraThemes;
import com.nekkora.ui.Nekkora;
import com.nekkora.ui.brand.NekkoraLogo;
import com.nekkora.ui.core.Severity;
import com.nekkora.ui.dialog.NekkoraConfirmationDialog;
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
    // OBS-001: leituras da atividade em linha própria (não atrasam as ações de preparação nem a EDT)
    private final ExecutorService obsIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nekkora-agent-atividade-tela");
        t.setDaemon(true);
        return t;
    });
    // AGENT-PACOTES-001: leitura do ZIP e empacotamento em linha própria (nunca na EDT nem na linha das ações de execução)
    private final ExecutorService importIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nekkora-agent-importacao");
        t.setDaemon(true);
        return t;
    });
    private com.nekkora.agent.app.ImportFlow importFlow;
    private ImportDialog importDialog;
    private com.nekkora.agent.app.ImportFlow.Stage loggedImportStage;
    private final AtomicBoolean exiting = new AtomicBoolean();
    private MainWindow window;
    private TrayController tray;
    private JDialog activeDialog;
    private int initialNotices = -1;
    // DESKTOP-001: escolhas da próxima execução (só em memória) e o diálogo aberto
    private ExecutionSetup setup = ExecutionSetup.empty();
    private ExecutionDialog execDialog;
    private boolean execBusy;
    private String lastFinished;
    // AGENT-PLATFORM-001: autorização escolhida para a próxima execução (null = só local) e o último retrato desenhado
    private java.util.UUID chosenAuthorization;
    private PlatformStatus shownPlatform;

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
        ctx.execution().addListener(snap -> SwingUtilities.invokeLater(this::renderExecution));
        ctx.activity().addListener(id -> SwingUtilities.invokeLater(() -> {
            if (execDialog != null && execDialog.isDisplayable()) execDialog.activityPanel().changed(id);
        }));
        renderExecution();
        ctx.platform().addListener(() -> SwingUtilities.invokeLater(() -> {
            renderPlatform();
            // AGENT-PACOTES-001: vínculo mudou (desvínculo, recusa, novo pareamento): o assistente aberto descarta o que tinha
            if (importFlow != null) importFlow.platformChanged();
        }));
        renderPlatform();
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
            public void execution() {
                openExecution();
            }

            @Override
            public void importProject() {
                openImport();
            }

            @Override
            public void exit() {
                AgentApp.this.exit();
            }

            @Override
            public void pair() {
                openPairing(PairingDialog.Mode.PAIR);
            }

            @Override
            public void rotate() {
                openPairing(PairingDialog.Mode.ROTATE);
            }

            @Override
            public void removeLink() {
                AgentApp.this.removeLink();
            }

            @Override
            public void blocked() {
                openBlocked();
            }
        };
    }

    // ----- Platform (AGENT-PLATFORM-001) -----

    /** Card "Platform" e linha de envio do diálogo de execução; redesenha só quando o retrato mudou. Na EDT. */
    void renderPlatform() {
        PlatformStatus st = ctx.platform().status();
        if (st.equals(shownPlatform)) return;
        shownPlatform = st;
        if (window != null) window.renderPlatform(st);
        if (execDialog != null && execDialog.isDisplayable()) renderExecution();
    }

    void openPairing(PairingDialog.Mode mode) {
        if (exiting.get() || bringActiveDialogToFront()) return;
        String url = ctx.config().current().platformUrl();
        String host = mode == PairingDialog.Mode.ROTATE ? ctx.platform().status().host() : Destination.parse(url).map(Destination::host).orElse(null);
        PairingDialog[] holder = new PairingDialog[1];
        holder[0] = new PairingDialog(window, mode, host, ctx.platform().status().secretStoreAvailable(), code -> background(
                () -> mode == PairingDialog.Mode.PAIR ? ctx.platform().pair(url, code) : ctx.platform().rotate(code),
                a -> {
                    if (holder[0].isDisplayable()) holder[0].finished(a.problem());
                    renderPlatform();
                }));
        activeDialog = holder[0];
        holder[0].showDialog();
        activeDialog = null;
    }

    /**
     * T06: "Desvincular esta máquina". Fora da EDT: confere execução em andamento e consulta a Platform; na EDT: as
     * consequências e a confirmação; fora da EDT de novo: o desvínculo (conferindo outra vez que nada começou).
     */
    void removeLink() {
        if (exiting.get() || bringActiveDialogToFront() || unlinkBusy) return;
        com.nekkora.agent.app.MachineUnlink unlink = ctx.unlink();
        setUnlinkBusy(true);
        background(() -> guarded(unlink::plan), plan -> {
            setUnlinkBusy(false);
            if (plan == null) {
                toast(Severity.ERROR, "Não foi possível desvincular", PlatformText.problem(ErrorCode.UNEXPECTED) + " Nada foi alterado.");
                return;
            }
            if (exiting.get() || bringActiveDialogToFront()) return;
            if (plan.running()) {
                showUnlinkBlocked();
                return;
            }
            if (!plan.preview().linked()) {
                renderPlatform();
                return;
            }
            UnlinkDialog d = new UnlinkDialog(window, plan.preview());
            activeDialog = d;
            boolean confirmed = d.showDialog() == com.nekkora.ui.dialog.NekkoraDialog.Result.CONFIRMED;
            activeDialog = null;
            if (!confirmed) return; // cancelar / aguardar envios: nada muda
            setUnlinkBusy(true);
            background(() -> guarded(() -> unlink.confirm(plan, plan.preview().pendingRuns() > 0)), r -> {
                setUnlinkBusy(false);
                unlinkFinished(plan.preview(), r != null ? r : new PlatformService.UnlinkResult(false, false, ErrorCode.UNEXPECTED, 0, 0));
            });
        });
    }

    /** Uma falha inesperada volta como {@code null} (a tela sempre recebe resposta e o botão nunca fica ocupado). */
    private <T> T guarded(Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            ctx.log().error(LogEvent.UNEXPECTED_ERROR, ErrorCode.UNEXPECTED, Field.exception(e));
            return null;
        }
    }

    private boolean unlinkBusy;

    private void setUnlinkBusy(boolean busy) {
        unlinkBusy = busy;
        if (window != null) window.unlinkBusy(busy);
    }

    private void unlinkFinished(PlatformService.UnlinkPreview p, PlatformService.UnlinkResult r) {
        renderPlatform();
        if (r.done()) {
            // T13: o desfecho dito pela Platform (confirmado, já encerrado, ou Platform sem suporte: só local)
            boolean ok = r.remote() == PlatformStatus.RemoteOutcome.CONFIRMED || r.remote() == PlatformStatus.RemoteOutcome.ENDED_BY_ADMINISTRATION
                    || r.remote() == PlatformStatus.RemoteOutcome.ENDED_BY_MACHINE;
            toast(ok && (r.problem() == null) ? Severity.SUCCESS : Severity.WARNING, PlatformText.unlinkTitle(r),
                    PlatformText.unlinkOutcome(r, p.host(), p.organizationId()));
        } else if (r.pending()) {
            // confirmado e guardado; a Platform ainda não confirmou (nunca "Worker liberado")
            toast(Severity.WARNING, PlatformText.unlinkTitle(r), PlatformText.unlinkOutcome(r, p.host(), p.organizationId()));
        } else if (r.queued()) {
            toast(Severity.INFO, "Desvínculo em andamento", "A linha de comunicação com a Platform está ocupada; o desvínculo é aplicado em seguida.");
        } else if (r.problem() == ErrorCode.PLT_UNLINK_RUNNING) {
            showUnlinkBlocked();
        } else if (r.problem() == ErrorCode.PLT_UNLINK_PENDING) {
            // apareceu envio pendente depois da confirmação: a pessoa decide de novo, vendo os números
            toast(Severity.WARNING, "Envios pendentes", PlatformText.problem(r.problem()));
            removeLink();
        } else {
            toast(Severity.ERROR, "Não foi possível desvincular", PlatformText.problem(r.problem()) + " O vínculo continua como estava.");
        }
    }

    private void showUnlinkBlocked() {
        FittedDialog d = new FittedDialog(window != null && window.isShowing() ? window : null, "Desvincular esta máquina");
        d.setDescription(PlatformText.UNLINK_RUNNING);
        d.setDialogSize(com.nekkora.ui.dialog.NekkoraDialog.Size.SMALL);
        com.nekkora.ui.button.NekkoraButton back = d.addSecondary("Voltar");
        back.setMnemonic(java.awt.event.KeyEvent.VK_V);
        d.getRootPane().setDefaultButton(back);
        activeDialog = d;
        d.showDialog();
        activeDialog = null;
    }

    void openBlocked() {
        if (exiting.get() || bringActiveDialogToFront()) return;
        BlockedDeliveriesDialog d = new BlockedDeliveriesDialog(window, ctx.platform().status().blocked(), id -> {
            if (!NekkoraConfirmationDialog.confirmDanger(activeDialog, "Declarar a perda?", "O número em que o envio parou passa a ser perda definitiva: "
                    + "a Platform mostrará o histórico desta execução como incompleto. A decisão não pode ser desfeita.", "Declarar perda")) {
                return;
            }
            ctx.platform().abandonBlocked(id);
        });
        activeDialog = d;
        d.showDialog();
        activeDialog = null;
    }

    /** O que o diálogo de execução mostra do canal (sem rede: cache de autorizações e progresso em memória). */
    private ExecutionDialog.PlatformRow platformRow(java.util.Optional<ExecutionController.Snapshot> snap) {
        PlatformStatus st = ctx.platform().status();
        ExecutionPackage pk = setup.pkg();
        java.util.List<PlatformService.AuthorizationChoice> choices = pk == null || !st.paired() ? java.util.List.of()
                : ctx.platform().authorizationsFor(pk.fingerprint());
        if (chosenAuthorization != null && choices.stream().noneMatch(c -> c.usable() && c.authorizationId().equals(chosenAuthorization))) {
            chosenAuthorization = null;
        }
        return new ExecutionDialog.PlatformRow(st.paired(), choices, chosenAuthorization,
                snap.map(x -> ctx.platform().delivery(x.record().id())).orElse(com.nekkora.agent.platform.DeliveryView.notLinked()));
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

    // ----- importação de projeto (AGENT-PACOTES-001) -----

    /** Assistente "Importar projeto (.zip)": vínculo confirmado online, ZIP lido numa pasta privada, pacote gerado. Na EDT. */
    void openImport() {
        if (exiting.get() || bringActiveDialogToFront()) return;
        java.nio.file.Path data = ctx.dataDir().root();
        importDialog = new ImportDialog(window, importActions());
        loggedImportStage = null;
        importFlow = new com.nekkora.agent.app.ImportFlow(new com.nekkora.agent.app.ImportFlow.Link() {
            @Override
            public com.nekkora.agent.platform.PlatformService.LinkCheck confirm() {
                com.nekkora.agent.platform.PlatformService.LinkCheck c = ctx.platform().confirmLink();
                ctx.log().info(LogEvent.IMPORT_LINK_CHECKED, Field.of("outcome", c.outcome()),
                        Field.of("catalog", c.catalog().available() ? String.valueOf(c.catalog().items().size()) : "indisponivel"));
                return c;
            }

            @Override
            public java.util.Optional<com.nekkora.agent.platform.PlatformService.LinkRef> current() {
                return ctx.platform().currentLink();
            }
        }, new com.nekkora.agent.pack.ProjectImporter(com.nekkora.agent.pack.ProjectImporter.defaultRoot(data), com.nekkora.agent.pack.ZipLimits.DEFAULT),
                new com.nekkora.agent.pack.PackageHistory(com.nekkora.agent.pack.PackageHistory.defaultFile(data), AtomicWriter.DEFAULT), ctx.clock(), importIo,
                SwingUtilities::invokeLater, v -> {
                    logImport(v);
                    if (importDialog != null && importDialog.isDisplayable()) importDialog.render(v);
                });
        activeDialog = importDialog;
        importFlow.start();
        importDialog.showDialog();
        activeDialog = null;
        // fechar descarta a pasta da operação (o pacote gerado fica onde a pessoa escolheu); nada remoto é desfeito ou criado
        importFlow.cancel();
        importFlow = null;
        importDialog = null;
    }

    /** Só códigos no log: estado, formato, motivo. Nunca nome de arquivo, projeto, organização ou caminho. */
    private void logImport(com.nekkora.agent.app.ImportFlow.View v) {
        if (v.stage() == loggedImportStage && v.problem() == null) return;
        loggedImportStage = v.stage();
        switch (v.stage()) {
            case CONFIGURE -> {
                if (v.problem() != null) ctx.log().warn(LogEvent.IMPORT_REFUSED, Field.of("code", v.problem().code().code()));
                else if (v.project() != null) ctx.log().info(LogEvent.IMPORT_ZIP_READ, Field.of("kind", v.project().kind()), Field.of("files", v.project().files().size()));
            }
            case READY -> {
                if (v.problem() != null) ctx.log().warn(LogEvent.IMPORT_REFUSED, Field.of("code", v.problem().code().code()));
            }
            case DONE -> ctx.log().info(LogEvent.IMPORT_PACKAGE_BUILT, Field.of("files", v.built() == null ? 0 : v.built().files()));
            case INVALIDATED -> ctx.log().warn(LogEvent.IMPORT_INVALIDATED);
            default -> {
                // demais estágios não vão ao log
            }
        }
    }

    private ImportDialog.Actions importActions() {
        return new ImportDialog.Actions() {
            @Override
            public void retry() {
                if (importFlow != null) importFlow.start();
            }

            @Override
            public void chooseZip() {
                if (importFlow == null) return;
                NekkoraFileDialogs.openFile(importDialog, ImportText.TITLE, "Projeto compactado (ZIP)", "zip").ifPresent(importFlow::chooseZip);
            }

            @Override
            public void generate(com.nekkora.agent.pack.PackagePlan plan, com.nekkora.agent.app.ImportFlow.ProjectMode mode, java.util.UUID automationId) {
                if (importFlow == null || importFlow.view().project() == null) return;
                try {
                    com.nekkora.agent.pack.Packager.preview(importFlow.view().project(), plan); // erros de escolha antes de pedir a pasta
                } catch (com.nekkora.agent.pack.ImportProblem e) {
                    NekkoraToast.show(importDialog, Severity.ERROR, "Pacote não gerado", e.message());
                    return;
                }
                java.nio.file.Path out = NekkoraFileDialogs.selectFolder(importDialog, "Pasta onde o pacote será gravado").orElse(null);
                if (out == null) return;
                boolean overwrite = false;
                if (com.nekkora.agent.pack.Packager.exists(out, plan)) {
                    if (!NekkoraConfirmationDialog.confirmDanger(importDialog, "Substituir o arquivo existente?", "Já existe "
                            + com.nekkora.agent.pack.Packager.fileName(plan) + " nesta pasta. Substituir troca o arquivo inteiro pelo pacote novo. O ZIP de "
                            + "origem nunca é alterado.", "Substituir")) {
                        return;
                    }
                    overwrite = true;
                }
                importFlow.build(plan, mode, automationId, out, overwrite);
            }

            @Override
            public void openFolder(java.nio.file.Path zip) {
                try {
                    java.awt.Desktop.getDesktop().open(zip.getParent().toFile());
                } catch (Exception e) {
                    NekkoraToast.show(importDialog, Severity.WARNING, "Não foi possível abrir a pasta", "Abra a pasta pelo Explorador de Arquivos.");
                }
            }

            @Override
            public com.nekkora.agent.app.ImportFlow.Suggestion suggest(com.nekkora.agent.app.ImportFlow.ProjectMode mode, String name, java.util.UUID automationId,
                                                                        com.nekkora.agent.pack.PackagePlan draft) {
                return importFlow == null ? new com.nekkora.agent.app.ImportFlow.Suggestion(java.util.Optional.empty(), "", java.util.Optional.empty(),
                        java.util.Optional.empty()) : importFlow.suggest(mode, name, automationId, draft);
            }

            @Override
            public java.util.List<com.nekkora.agent.app.ImportFlow.CatalogProject> catalog() {
                return importFlow == null ? java.util.List.of() : importFlow.catalog();
            }
        };
    }

    ImportDialog importDialog() {
        return importDialog;
    }

    com.nekkora.agent.app.ImportFlow importFlow() {
        return importFlow;
    }

    // ----- execução local (DESKTOP-001) -----

    void openExecution() {
        if (exiting.get() || bringActiveDialogToFront()) return;
        execDialog = new ExecutionDialog(window, executionActions(), activityData());
        activeDialog = execDialog;
        refreshSession();
        renderExecution();
        execDialog.showDialog();
        activeDialog = null;
        execDialog = null;
    }

    /** Dados da aba "Atividade": tudo fora da EDT, só o envelope do Agent. */
    private ActivityPanel.Data activityData() {
        return new ActivityPanel.Data() {
            @Override
            public void history(java.util.function.Consumer<com.nekkora.agent.exec.RunStore.Listing> onEdt) {
                activityBackground(() -> ctx.activity().history(), onEdt, com.nekkora.agent.exec.RunStore.Listing::failure);
            }

            @Override
            public void view(com.nekkora.agent.exec.ExecutionId id, long after, java.util.function.Consumer<ActivityPanel.Loaded> onEdt) {
                activityBackground(() -> ActivityPanel.Loaded.of(ctx.activity().view(id, after)), onEdt, ActivityPanel.Loaded::failure);
            }

            @Override
            public void export(com.nekkora.agent.exec.ExecutionId id, java.util.List<ActivityEvent> events, ActivityView.Completeness completeness) {
                exportActivity(id, events, completeness);
            }

            @Override
            public com.nekkora.agent.platform.DeliveryView delivery(com.nekkora.agent.exec.ExecutionId id) {
                return ctx.platform().delivery(id);
            }
        };
    }

    /**
     * Exportação manual: primeiro a prévia dos campos (o que pode sair no arquivo), depois o destino escolhido pela
     * pessoa. Só envelopes saneados; nunca o arquivo bruto do Framework, a saída do processo ou valores.
     */
    void exportActivity(com.nekkora.agent.exec.ExecutionId id, java.util.List<ActivityEvent> events, ActivityView.Completeness completeness) {
        if (execDialog == null) return;
        String preview = events.size() + " evento(s) da execução " + id.value() + ". " + ActivityText.exportIntegrity(completeness)
                + " Campos que podem aparecer: " + String.join(", ", ActivityExport.FIELDS)
                + ". Não há valores de entrada ou saída, textos de página, caminhos, credenciais nem a saída do processo.";
        if (!NekkoraConfirmationDialog.confirm(execDialog, "Exportar a atividade?", preview, "Escolher destino…")) return;
        String name = "nekkora-agent-atividade-" + id.value() + ".jsonl";
        Path target = NekkoraFileDialogs.saveFile(execDialog, "Exportar atividade", name, "jsonl").orElse(null);
        if (target == null) return;
        background(() -> {
            try {
                AtomicWriter.DEFAULT.write(target, ActivityExport.jsonl(events));
                return null;
            } catch (Exception e) {
                return e.getClass().getSimpleName();
            }
        }, failureType -> {
            if (failureType == null) {
                ctx.log().info(LogEvent.ACTIVITY_EXPORTED, Field.of("events", events.size()));
                toast(Severity.SUCCESS, "Atividade exportada", "Arquivo " + target.getFileName() + " gravado.");
            } else {
                ErrorCode code = ErrorCode.OBS_EXPORT_FAILED;
                ctx.log().error(LogEvent.ACTIVITY_EXPORT_FAILED, code, Field.of("exception", failureType));
                toast(Severity.ERROR, "Não foi possível exportar", code.description() + " (" + code.code() + ").");
            }
        });
    }

    /** Leitura da atividade fora da EDT; uma falha inesperada chega à tela como {@code onFailure} (nunca fica sem resposta). */
    private <T> void activityBackground(Supplier<T> work, java.util.function.Consumer<T> onEdt, Supplier<T> onFailure) {
        if (obsIo.isShutdown()) return;
        try {
            obsIo.execute(() -> {
                T value = null;
                boolean ok = false;
                try {
                    value = work.get();
                    ok = true;
                } catch (RuntimeException e) {
                    ctx.log().error(LogEvent.UNEXPECTED_ERROR, ErrorCode.UNEXPECTED, Field.exception(e));
                } finally {
                    // a tela SEMPRE recebe uma resposta (até com Error, que segue adiante depois daqui)
                    T result = ok ? value : onFailure.get();
                    SwingUtilities.invokeLater(() -> {
                        if (!exiting.get()) onEdt.accept(result);
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // encerrando
        }
    }

    private ExecutionDialog.Actions executionActions() {
        return new ExecutionDialog.Actions() {
            @Override
            public void choosePackage() {
                NekkoraFileDialogs.selectFolder(execDialog, "Pasta do pacote da automação").ifPresent(AgentApp.this::selectPackage);
            }

            @Override
            public void approve() {
                ExecutionPackage pk = setup.pkg();
                if (pk == null) return;
                String what = ExecutionSetup.kindLabel(pk) + " “" + pk.name() + "” · " + ExecutionSetup.originLabel(pk) + " · " + ExecutionSetup.modeLabel(pk) + " · impressão digital "
                        + pk.shortFingerprint() + (pk.origins().isEmpty() ? "" : " · sites declarados: " + String.join(", ", pk.origins()));
                if (!NekkoraConfirmationDialog.confirm(execDialog, "Aprovar este pacote?", what + ". Ao aprovar, ESTE conteúdo exato poderá ser "
                        + "executado por você nesta máquina; qualquer mudança nos arquivos exige nova aprovação. Aprovar não inicia nada.", "Aprovar")) {
                    return;
                }
                approveConfirmed();
            }

            @Override
            public void choosePython() {
                NekkoraFileDialogs.openFile(execDialog, "Python do ambiente do projeto", "Python", ctx.windows() ? new String[] {"exe"} : new String[0])
                        .ifPresent(AgentApp.this::selectPython);
            }

            @Override
            public void chooseInput() {
                NekkoraFileDialogs.openFile(execDialog, "Entrada da automação", "Lista ou entrada", "csv", "xlsx", "json")
                        .ifPresent(AgentApp.this::selectInput);
            }

            @Override
            public void chooseOutput() {
                NekkoraFileDialogs.selectFolder(execDialog, "Pasta de saída (uma subpasta por execução)").ifPresent(AgentApp.this::selectOutput);
            }

            @Override
            public void start() {
                busy(() -> {
                    ExecutionSetup s = refreshedSession(setup);
                    java.util.UUID auth = chosenAuthorization;
                    s.request(ExecutionController.DEFAULT_TIMEOUT).ifPresent(req -> {
                        // AGENT-PLATFORM-001: a autorização escolhida é conferida e gravada ANTES do início; recusada, nada começa
                        // (a pessoa escolhe de novo, inclusive "só local") — nunca vira execução só local sem ela saber
                        if (auth != null && s.pkg() != null) {
                            PlatformService.Action a = ctx.platform().prepare(auth, s.pkg().fingerprint());
                            if (!a.accepted()) {
                                ctx.log().warn(LogEvent.EXEC_BLOCKED, a.problem());
                                SwingUtilities.invokeLater(() -> toast(Severity.WARNING, "Autorização da Platform não vale mais",
                                        PlatformText.problem(a.problem()) + " Escolha outra ou “Não registrar”."));
                                return;
                            }
                        }
                        try {
                            ExecutionController.Snapshot snap = ctx.execution().start(req);
                            ctx.log().info(LogEvent.EXEC_STARTED, Field.of("kind", snap.record().kind()), Field.of("pid", snap.record().owner().pid()));
                            if (auth != null) ctx.platform().link(snap.record().id());
                        } catch (ExecRefused e) {
                            ctx.log().warn(LogEvent.EXEC_BLOCKED, e.code());
                            if (auth != null) ctx.platform().discardIntent();
                        }
                    });
                    return s;
                });
            }

            @Override
            public void selectAuthorization(java.util.UUID authorizationId) {
                chosenAuthorization = authorizationId;
                renderExecution();
            }

            @Override
            public void cancelRun() {
                if (!ctx.execution().running()) return;
                if (!NekkoraConfirmationDialog.confirmDanger(execDialog, "Cancelar a execução?", "O processo e os que ele abriu são encerrados. "
                        + "O que já foi feito no sistema não é desfeito; a execução fica registrada como cancelada.", "Cancelar execução")) {
                    return;
                }
                if (ctx.execution().cancel()) ctx.log().info(LogEvent.EXEC_CANCEL_REQUESTED);
            }
        };
    }

    /** Pacote escolhido: inspecionado (hashes) fora da EDT; mostra se já está aprovado nesta máquina. */
    void selectPackage(Path dir) {
        busy(() -> {
            try {
                ExecutionPackage pk = ExecutionPackage.inspect(dir);
                ctx.log().info(LogEvent.EXEC_PACKAGE_INSPECTED, Field.of("kind", pk.kind()), Field.of("mode", pk.mode()));
                // AGENT-PLATFORM-001: só uma autorização válida para ESTA impressão = pré-escolhida; mais de uma = a pessoa escolhe
                chosenAuthorization = PlatformService.preselected(ctx.platform().authorizationsFor(pk.fingerprint())).orElse(null);
                return setup.withPackage(pk, null, ctx.execution().approvals().approved(pk));
            } catch (ExecRefused e) {
                ctx.log().warn(LogEvent.EXEC_PACKAGE_INSPECTED, e.code());
                return setup.withPackage(null, e.code(), false);
            }
        });
    }

    /** Aprovação já confirmada pela pessoa (o diálogo de confirmação fica em {@code approve}). */
    void approveConfirmed() {
        ExecutionPackage pk = setup.pkg();
        if (pk == null) return;
        busy(() -> {
            try {
                ctx.execution().approvals().approve(pk, ctx.clock().instant());
                ctx.log().info(LogEvent.EXEC_PACKAGE_APPROVED, Field.of("kind", pk.kind()));
                return setup.withApproved(true);
            } catch (ExecRefused e) {
                ctx.log().error(LogEvent.EXEC_PACKAGE_APPROVED, e.code());
                return setup;
            }
        });
    }

    void selectPython(Path py) {
        busy(() -> {
            try {
                PythonRuntime.Probe probe = PythonRuntime.probe(py, ctx.dataDir().root().resolve("execucao").resolve("sonda"));
                ctx.log().info(LogEvent.EXEC_PYTHON_CHECKED, Field.of("python", probe.pythonVersion()), Field.of("framework", probe.framework().orElse("none")));
                return setup.withPython(probe, null);
            } catch (ExecRefused e) {
                ctx.log().warn(LogEvent.EXEC_PYTHON_CHECKED, e.code());
                return setup.withPython(null, e.code());
            }
        });
    }

    void selectInput(Path in) {
        busy(() -> setup.withInput(in));
    }

    void selectOutput(Path out) {
        busy(() -> setup.withOutput(out));
    }

    boolean executionBusy() {
        return execBusy;
    }

    /** Ação de preparação fora da EDT; o diálogo fica ocupado até o resultado voltar. */
    private void busy(Supplier<ExecutionSetup> work) {
        execBusy = true;
        renderExecution();
        background(work, next -> {
            execBusy = false;
            setup = next;
            renderExecution();
        });
    }

    private void refreshSession() {
        background(() -> refreshedSession(setup), next -> {
            setup = next;
            renderExecution();
        });
    }

    private ExecutionSetup refreshedSession(ExecutionSetup s) {
        return s.withSession(ctx.execution().session().probe());
    }

    /** Janela principal (linha de estado) e diálogo, a partir do controlador. Na EDT. */
    void renderExecution() {
        java.util.Optional<ExecutionController.Snapshot> snap = ctx.execution().current();
        if (window != null) window.renderExecution(executionLine(snap));
        if (execDialog != null && execDialog.isDisplayable()) execDialog.render(setup, snap, execBusy, platformRow(snap));
        snap.filter(x -> !x.running() && x.record().state().terminal()).ifPresent(x -> {
            ExecutionRecord r = x.record();
            if (r.id().value().equals(lastFinished) || r.owner() == null) return;
            lastFinished = r.id().value();
            ctx.log().info(LogEvent.EXEC_FINISHED, Field.of("state", r.state()), Field.of("code", r.code() == null ? "none" : r.code().code()),
                    Field.of("suspensions", r.suspensions()), Field.of("adopted", r.adopted()));
        });
    }

    static String executionLine(java.util.Optional<ExecutionController.Snapshot> snap) {
        if (snap.isEmpty()) return "Nenhuma nesta sessão";
        ExecutionRecord r = snap.get().record();
        StringBuilder b = new StringBuilder(r.state().label());
        if (snap.get().running()) {
            b.append(" · ").append(ExecutionSetup.duration(snap.get().elapsedMillis()));
            if (r.owner() != null) b.append(" · PID ").append(r.owner().pid());
        } else if (r.code() != null) {
            b.append(" · ").append(r.code().code());
        }
        // A03: parcial nunca é apresentado como conclusão
        if (!snap.get().running() && r.progress() != null && r.progress().available()) {
            b.append(" · progresso parcial: ").append(r.progress().transactions().getOrDefault("SUCCEEDED", 0L)).append(" de ").append(r.progress().total())
                    .append(" concluídas");
        }
        if (!snap.get().recordSaved()) b.append(" · registro não gravado (").append(com.nekkora.agent.core.ErrorCode.EXEC_STORAGE_FAILED.code()).append(')');
        return b.toString();
    }

    // ----- saída -----

    /** Encerra: remove o ícone, fecha janelas e recursos, libera a trava e termina o processo. Idempotente. */
    public void exit() {
        if (exiting.get()) return;
        // execução em andamento: sair a cancela (só a árvore possuída) — a pessoa confirma antes
        if (ctx.execution().running() && !confirmExitWhileRunning()) return;
        if (!exiting.compareAndSet(false, true)) return;
        ctx.log().info(LogEvent.LIFE_EXIT_REQUESTED);
        if (tray != null) {
            tray.remove();
            ctx.log().info(LogEvent.TRAY_REMOVED);
        }
        for (Window w : Window.getWindows()) w.dispose();
        io.shutdownNow();
        obsIo.shutdownNow();
        importIo.shutdownNow();
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

    /**
     * Confirmação de sair com execução ativa. "Voltar" é o padrão (Enter/Esc não encerram nada); os dois botões têm atalho
     * de teclado (Alt+V, Alt+E).
     */
    private boolean confirmExitWhileRunning() {
        FittedDialog d = new FittedDialog(window != null && window.isShowing() ? window : null, EXIT_TITLE);
        d.setDescription("Há uma execução local em andamento. Sair encerra o processo dela (e os que ele abriu) e a registra como "
                + "cancelada; o que já foi feito no sistema não é desfeito.");
        d.setDialogSize(com.nekkora.ui.dialog.NekkoraDialog.Size.SMALL);
        com.nekkora.ui.button.NekkoraButton back = d.addSecondary("Voltar");
        back.setMnemonic(java.awt.event.KeyEvent.VK_V);
        com.nekkora.ui.button.NekkoraButton go = d.addDanger("Encerrar execução e sair", () -> true);
        go.setMnemonic(java.awt.event.KeyEvent.VK_E);
        d.getRootPane().setDefaultButton(back);
        activeDialog = d;
        boolean confirmed = d.showDialog() == com.nekkora.ui.dialog.NekkoraDialog.Result.CONFIRMED;
        activeDialog = null;
        return confirmed;
    }

    static final String EXIT_TITLE = "Sair do Nekkora Agent?";

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

    ExecutionDialog executionDialog() {
        return execDialog;
    }

    ExecutionSetup executionSetup() {
        return setup;
    }

    AgentContext context() {
        return ctx;
    }

    ShortcutState studioState() {
        return ctx.studioView(ctx.config().current()).state();
    }
}
