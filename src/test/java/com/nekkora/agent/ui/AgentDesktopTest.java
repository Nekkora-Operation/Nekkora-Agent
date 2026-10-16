package com.nekkora.agent.ui;

import static com.nekkora.agent.ui.DesktopHarness.capture;
import static com.nekkora.agent.ui.DesktopHarness.find;
import static com.nekkora.agent.ui.DesktopHarness.texts;
import static com.nekkora.agent.ui.DesktopHarness.trayIconCount;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.os.TrayController;
import com.nekkora.agent.storage.InstanceLock;
import com.nekkora.ui.data.NekkoraStatusBadge;
import java.awt.AWTException;
import java.awt.Component;
import java.awt.FocusTraversalPolicy;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.MenuItem;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Homologação em desktop Windows real, dentro da JVM de teste: janela, bandeja do sistema, teclado,
 * diálogos e arquivos reais. Os atalhos usam registradores (o pacote é homologado à parte, com
 * executável real). Rodar com {@code ./mvnw -Pdesktop verify}.
 */
@Tag("desktop")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentDesktopTest {
    static final Map<String, String> ENV = Map.of("COMPUTERNAME", "LAB-AGENT-01");

    @TempDir
    Path tmp;

    private DesktopHarness h;

    @BeforeEach
    void noLeftovers() {
        assertEquals(0, trayIconCount(), "nenhum ícone de teste anterior na bandeja");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (h != null) h.close();
        assertEquals(0, trayIconCount(), "sair remove o ícone");
    }

    @Test
    @Order(1)
    void a02FirstStartShowsCompactWindowOneTrayIconAndHonestStates() throws Exception {
        h = DesktopHarness.start(tmp.resolve("a02"), ENV, new String[0], TrayController.SYSTEM);
        h.waitFor(() -> h.app.tray() != null, "bandeja instalada");
        assertEquals(1, trayIconCount(), "um único ícone");
        MainWindow w = h.window();
        Rectangle usable = h.edt(() -> usable(w));
        Rectangle b = h.edt(w::getBounds);
        assertTrue(usable.contains(b), "janela dentro da área útil: " + b + " em " + usable);
        assertTrue(b.width <= 560 && b.height <= 720, "janela compacta: " + b);
        assertEquals("Nekkora Agent", h.edt(w::getTitle));
        List<String> t = h.edt(() -> texts(w.getRootPane()));
        for (String expected : List.of("LAB-AGENT-01", "0.1.0", "Não pareado", "Não vinculado", "Sem conexão com a Platform",
                "Não verificada", "Execução indisponível nesta versão")) {
            assertTrue(t.stream().anyMatch(s -> s.contains(expected)), "falta '" + expected + "' em " + t);
        }
        assertTrue(t.stream().anyMatch(s -> s.contains("ainda não está disponível nesta versão")));
        h.settle();
        capture(w, "A02-janela-inicial");
        String log = log();
        assertTrue(log.contains(" INFO TRAY_INSTALLED"), log);
        assertTrue(log.matches("(?s).* INFO LIFE_READY startup_ms=\\d+.*"), log);

        h.run(() -> w.exitButton().doClick());
        h.waitFor(() -> !h.exits.isEmpty(), "saída");
        assertEquals(List.of(0), h.exits);
        assertEquals(0, trayIconCount());
        assertFalse(h.edt(w::isDisplayable), "janela descartada");
        InstanceLock.Acquired again = assertInstanceOf(InstanceLock.Acquired.class, InstanceLock.tryAcquire(h.dataDir.resolve("agent.lock")));
        again.lock().close();
        assertTrue(log().contains("LIFE_EXIT status=0"));
    }

    @Test
    @Order(2)
    void a03CloseAndRestoreRepeatedlyKeepsOneIconAndTheProcess() throws Exception {
        h = DesktopHarness.start(tmp.resolve("a03"), ENV, new String[0], TrayController.SYSTEM);
        MainWindow w = h.window();
        TrayIcon icon = SystemTray.getSystemTray().getTrayIcons()[0];
        assertEquals("Nekkora Agent", icon.getToolTip());
        List<String> menu = new ArrayList<>();
        for (int i = 0; i < icon.getPopupMenu().getItemCount(); i++) menu.add(icon.getPopupMenu().getItem(i).getLabel());
        assertEquals(List.of("Abrir o Nekkora Agent", "Configurações…", "Diagnóstico…", "-", "Sair"), menu);

        // 1º fechamento: clique real no botão de fechar da barra de título
        boolean robotClick = clickTitleClose(w);
        h.waitFor(() -> !w.isVisible(), "janela recolhida pelo clique em fechar");
        int cycles = 25;
        for (int i = 0; i < cycles; i++) {
            if (i > 0) {
                h.run(() -> w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)));
                h.waitFor(() -> !w.isVisible(), "janela recolhida " + i);
            }
            assertEquals(1, trayIconCount(), "um ícone enquanto oculta");
            assertFalse(h.app.isExiting());
            assertTrue(h.exits.isEmpty(), "processo continua");
            if (i % 2 == 0) fire(icon.getActionListeners(), icon); // duplo clique no ícone
            else fire(item(icon, "Abrir o Nekkora Agent").getActionListeners(), icon); // menu "Abrir"
            h.waitFor(w::isShowing, "janela restaurada " + i);
            assertEquals(1, trayIconCount());
        }
        assertFalse(h.edt(() -> h.app.tray().notifyOnce("x", "y")), "o aviso da bandeja já foi mostrado uma única vez");
        h.settle();
        String log = log();
        assertEquals(cycles, count(log, "LIFE_WINDOW_HIDDEN to=tray"));
        assertEquals(cycles, count(log, "LIFE_WINDOW_RESTORED"));
        System.out.println("[A03] primeiro fechamento por clique real do Robot: " + robotClick);

        // Sair pelo menu da bandeja
        fire(item(icon, "Sair").getActionListeners(), icon);
        h.waitFor(() -> !h.exits.isEmpty(), "saída pelo menu");
        assertEquals(0, trayIconCount());
    }

    @Test
    @Order(3)
    void a07WithoutTrayTheWindowStaysReachableAndExitIsExplicit() throws Exception {
        h = DesktopHarness.start(tmp.resolve("a07"), ENV, new String[] {"--sem-bandeja"}, TrayController.SYSTEM);
        assertEquals(0, trayIconCount());
        MainWindow w = h.window();
        List<String> t = h.edt(() -> texts(w.getRootPane()));
        assertTrue(t.stream().anyMatch(s -> s.contains("Bandeja desativada")), t.toString());
        assertTrue(t.stream().anyMatch(s -> s.contains("barra de tarefas")));
        // regressão: o aviso chega depois do dimensionamento inicial; a janela se reajusta e não precisa rolar
        h.waitFor(() -> w.scrollPane().getViewport().getView().getHeight() <= w.scrollPane().getViewport().getHeight(),
                "janela reajustada ao aviso, sem rolagem");
        capture(w, "A07-sem-bandeja");
        h.run(() -> w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)));
        h.waitFor(() -> (w.getExtendedState() & Frame.ICONIFIED) != 0, "minimizada");
        assertTrue(h.edt(w::isVisible), "continua na barra de tarefas, não some");
        assertTrue(h.exits.isEmpty());
        h.run(h.app::restore);
        h.waitFor(() -> (w.getExtendedState() & Frame.ICONIFIED) == 0, "restaurada");
        h.run(() -> w.exitButton().doClick());
        h.waitFor(() -> !h.exits.isEmpty(), "saída explícita");
    }

    @Test
    @Order(4)
    void a07TrayInstallFailureIsExplained() throws Exception {
        TrayController.Port failing = new TrayController.Port() {
            @Override
            public boolean supported() {
                return true;
            }

            @Override
            public TrayController.Handle add(IntFunction<Image> icon, String tooltip, TrayController.Actions a) throws AWTException {
                throw new AWTException("falha simulada da bandeja");
            }
        };
        h = DesktopHarness.start(tmp.resolve("a07b"), ENV, new String[0], failing);
        MainWindow w = h.window();
        h.waitFor(() -> texts(w.getRootPane()).stream().anyMatch(s -> s.contains("Bandeja do sistema indisponível")), "aviso");
        assertTrue(texts(w.getRootPane()).stream().anyMatch(s -> s.contains("AGT-TRAY-002")));
        capture(w, "A07-falha-bandeja-simulada");
        h.run(() -> w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)));
        h.waitFor(() -> (w.getExtendedState() & Frame.ICONIFIED) != 0, "minimizada");
        h.settle();
        assertTrue(log().contains("TRAY_UNAVAILABLE code=AGT-TRAY-002 exception=AWTException"), log());
    }

    @Test
    @Order(5)
    void a08CorruptedConfigIsWarnedAndOnlyReplacedAfterExplicitPreserve() throws Exception {
        Path dir = tmp.resolve("a08");
        byte[] broken = "{ \"format\": \"nekkora-agent/config\", \"version\": 1, ".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("config"));
        Files.write(dir.resolve("config").resolve("agent-config.json"), broken);
        h = DesktopHarness.start(dir, ENV, new String[0], TrayController.SYSTEM);
        MainWindow w = h.window();
        h.waitFor(() -> texts(w.getRootPane()).stream().anyMatch(s -> s.contains("Configuração não carregada")), "aviso de configuração");
        assertTrue(texts(w.getRootPane()).stream().anyMatch(s -> s.contains("AGT-CFG-002") && s.contains("preservado")));
        capture(w, "A08-configuracao-corrompida");

        SettingsDialog d = openSettings();
        assertEquals("Guardar cópia e salvar", h.edt(() -> d.getPrimaryButton().getText()));
        assertTrue(h.edt(() -> texts(d.getRootPane())).stream().anyMatch(s -> s.contains("Arquivo de configuração incompatível")));
        capture(d, "A08-configuracoes-arquivo-incompativel");
        h.later(() -> d.getPrimaryButton().doClick());
        h.waitFor(() -> !d.isDisplayable(), "salvo e fechado");
        Path config = dir.resolve("config");
        Path copy;
        try (Stream<Path> s = Files.list(config)) {
            copy = s.filter(p -> p.getFileName().toString().startsWith("agent-config.json.incompativel-")).findFirst().orElseThrow();
        }
        assertArrayEquals(broken, Files.readAllBytes(copy), "cópia preservada byte a byte");
        assertTrue(Files.readString(config.resolve("agent-config.json")).contains("\"version\": 1"));
        h.waitFor(() -> texts(w.getRootPane()).stream().noneMatch(s -> s.contains("Configuração não carregada")), "aviso some após salvar");
    }

    @Test
    @Order(6)
    void a08WriteFailureKeepsDialogOpenWithoutFalseConfirmation() throws Exception {
        Path dir = tmp.resolve("a08b");
        h = DesktopHarness.start(dir, ENV, new String[0], TrayController.SYSTEM);
        Path file = dir.resolve("config").resolve("agent-config.json");
        SettingsDialog first = openSettings();
        h.later(() -> first.getPrimaryButton().doClick());
        h.waitFor(() -> !first.isDisplayable(), "primeira gravação");
        byte[] before = Files.readAllBytes(file);
        DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
        dos.setReadOnly(true);
        try {
            SettingsDialog d = openSettings();
            h.run(() -> d.platformUrlField().setText("https://platform.exemplo.test"));
            h.later(() -> d.getPrimaryButton().doClick());
            h.waitFor(() -> d.failureAlert().isVisible(), "falha visível");
            // regressão: o alerta ficava abaixo da área visível do formulário (encontrado na captura A08)
            h.waitFor(() -> d.failureAlert().getHeight() > 0
                    && d.failureAlert().getVisibleRect().height == d.failureAlert().getHeight(), "alerta inteiro na área visível");
            assertTrue(h.edt(d::isDisplayable), "diálogo continua aberto");
            String msg = h.edt(() -> String.join(" ", texts(d.failureAlert())));
            assertTrue(msg.contains("AGT-CFG-005") && msg.contains("anterior continua em uso"), msg);
            capture(d, "A08-falha-de-gravacao");
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals(null, h.ctx.config().current().platformUrl(), "configuração em uso não mudou");
            h.run(d::cancel);
        } finally {
            dos.setReadOnly(false);
        }
        h.settle();
        assertTrue(log().contains("CONFIG_SAVE_FAILED code=AGT-CFG-005"));
    }

    @Test
    @Order(7)
    void a09MissingMachineNameIsShownExplicitly() throws Exception {
        h = DesktopHarness.start(tmp.resolve("a09"), Map.of(), new String[0], TrayController.SYSTEM);
        MainWindow w = h.window();
        assertTrue(h.edt(() -> texts(w.getRootPane())).contains("Não disponível"));
        capture(w, "A09-nome-indisponivel");
        h.settle();
        assertTrue(log().contains("MACHINE_NAME_UNAVAILABLE code=AGT-HOST-001"));
    }

    @Test
    @Order(8)
    void a10ShortcutsOpenOnlyAfterClickAndOnlyTheValidatedTarget() throws Exception {
        Path dir = tmp.resolve("a10");
        Path exe = Files.createDirectories(tmp.resolve("Programas").resolve("Nekkora Studio")).resolve("Nekkora Studio.exe");
        Files.write(exe, new byte[] {'M', 'Z'});
        DesktopHarness.writeConfig(dir, "{\"format\": \"nekkora-agent/config\", \"version\": 1, \"theme\": \"porcelain-light\", "
                + "\"shortcuts\": {\"platformUrl\": \"https://platform.exemplo.test/app\", \"studioExecutable\": " + com.nekkora.agent.storage.StrictJson.quote(exe.toString()) + "}}");
        h = DesktopHarness.start(dir, ENV, new String[0], TrayController.SYSTEM);
        MainWindow w = h.window();
        h.waitFor(() -> w.studioButton().isEnabled() && w.platformButton().isEnabled(), "atalhos habilitados");
        h.settle();
        assertTrue(h.startedCommands().isEmpty() && h.browsedUris().isEmpty(), "nada abre sozinho");
        capture(w, "A10-atalhos-validos-tema-claro");
        h.run(() -> w.studioButton().doClick());
        h.waitFor(() -> h.startedCommands().size() == 1, "Studio iniciado");
        assertEquals(List.of(exe.toString()), h.startedCommands().get(0), "só o executável, sem argumentos");
        h.run(() -> w.platformButton().doClick());
        h.waitFor(() -> h.browsedUris().size() == 1, "Platform aberta");
        assertEquals("https://platform.exemplo.test/app", h.browsedUris().get(0).toString());

        // configuração inválida digitada: recusada no formulário, nada gravado
        byte[] before = Files.readAllBytes(dir.resolve("config").resolve("agent-config.json"));
        SettingsDialog d = openSettings();
        h.run(() -> {
            d.platformUrlField().setText("https://platform.exemplo.test/?token=abc");
            d.studioExecutableField().setText("C:\\Windows\\System32\\cmd.exe");
        });
        h.run(() -> d.getPrimaryButton().doClick());
        assertTrue(h.edt(() -> d.platformFormField().hasError() && d.studioFormField().hasError()));
        capture(d, "A10-destinos-invalidos-recusados");
        h.run(d::cancel);
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("config").resolve("agent-config.json")));
        h.settle();
        String log = log();
        assertFalse(log.contains("platform.exemplo") || log.contains("Programas"), "destinos não vão para o log");
        assertTrue(log.contains("SHORTCUT_STUDIO_STARTED") && log.contains("SHORTCUT_PLATFORM_OPENED"));
    }

    @Test
    @Order(9)
    void a10InvalidAndMissingTargetsShowReasonAndStayDisabled() throws Exception {
        Path dir = tmp.resolve("a10b");
        DesktopHarness.writeConfig(dir, "{\"format\": \"nekkora-agent/config\", \"version\": 1, \"theme\": \"sakura-dark\", "
                + "\"shortcuts\": {\"platformUrl\": \"https://platform.exemplo.test/?token=abc\", \"studioExecutable\": \"C:\\\\nao\\\\existe\\\\Studio.exe\"}}");
        h = DesktopHarness.start(dir, ENV, new String[0], TrayController.SYSTEM);
        MainWindow w = h.window();
        assertFalse(h.edt(() -> w.studioButton().isEnabled() || w.platformButton().isEnabled()));
        List<String> t = h.edt(() -> texts(w.getRootPane()));
        assertTrue(t.stream().anyMatch(s -> s.contains("o arquivo não existe")), t.toString());
        assertTrue(t.stream().anyMatch(s -> s.contains("não pode conter parâmetros")), t.toString());
        capture(w, "A10-atalhos-invalidos");
        assertTrue(h.startedCommands().isEmpty() && h.browsedUris().isEmpty());
    }

    @Test
    @Order(10)
    void a11DiagnosticsPreviewIsExactAndMachineNameIsOptIn() throws Exception {
        h = DesktopHarness.start(tmp.resolve("a11"), ENV, new String[0], TrayController.SYSTEM);
        h.later(h.app::openDiagnostics);
        h.waitFor(() -> h.app.activeDialog() instanceof DiagnosticsDialog dd && dd.isShowing(), "diagnóstico aberto");
        DiagnosticsDialog d = (DiagnosticsDialog) h.edt(h.app::activeDialog);
        assertFalse(h.edt(d::previewText).contains("LAB-AGENT-01"), "omitido por padrão");
        assertFalse(h.edt(() -> d.includeNameBox().isSelected()));
        capture(d, "A11-diagnostico-previa");
        h.run(() -> d.includeNameBox().doClick());
        assertTrue(h.edt(d::previewText).contains("\"nome\": \"LAB-AGENT-01\""));
        h.run(() -> d.includeNameBox().doClick());
        String expected = h.edt(d::previewText);

        Path out = tmp.resolve("export").resolve("diagnostico.json");
        Files.createDirectories(out.getParent());
        h.later(() -> d.getPrimaryButton().doClick());
        h.waitFor(() -> chooser() != null, "seletor de arquivo");
        h.run(() -> {
            JFileChooser fc = chooser();
            fc.setSelectedFile(out.toFile());
            fc.approveSelection();
        });
        h.waitFor(() -> !d.isDisplayable(), "exportado");
        assertEquals(expected, Files.readString(out), "o arquivo é exatamente a prévia");
        assertFalse(Files.readString(out).contains("LAB-AGENT-01"));
        h.settle();
        assertTrue(log().contains("DIAGNOSTIC_EXPORTED machine_name=false"));
    }

    @Test
    @Order(11)
    void a12KeyboardReachesEveryActionAndMnemonicsWork() throws Exception {
        h = DesktopHarness.start(tmp.resolve("a12"), ENV, new String[0], TrayController.SYSTEM);
        MainWindow w = h.window();
        List<String> order = h.edt(() -> {
            FocusTraversalPolicy p = w.getFocusTraversalPolicy();
            List<String> names = new ArrayList<>();
            Component first = p.getFirstComponent(w);
            Component c = first;
            for (int i = 0; i < 40 && c != null; i++) {
                names.add(c instanceof AbstractButton b ? b.getText() : c.getClass().getSimpleName());
                c = p.getComponentAfter(w, c);
                if (c == first) break;
            }
            return names;
        });
        assertTrue(order.containsAll(List.of("Configurações", "Diagnóstico", "Sair")), order.toString());
        assertFalse(order.contains("Abrir Studio"), "atalho indisponível fora do Tab (motivo está em texto)");
        System.out.println("[A12] ordem de Tab: " + order);

        // teclado real: foco inicial em Configurações, Tab vai para Diagnóstico, Alt+C abre Configurações
        Robot robot = new Robot();
        robot.setAutoDelay(40);
        h.run(() -> {
            w.toFront();
            w.settingsButton().requestFocusInWindow();
        });
        h.waitFor(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == w.settingsButton(), "foco em Configurações");
        key(robot, KeyEvent.VK_TAB);
        h.waitFor(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == w.diagnosticsButton(), "Tab → Diagnóstico");
        capture(w, "A12-foco-teclado");
        robot.keyPress(KeyEvent.VK_ALT);
        key(robot, KeyEvent.VK_C);
        robot.keyRelease(KeyEvent.VK_ALT);
        h.waitFor(() -> h.app.activeDialog() instanceof SettingsDialog sd && sd.isShowing(), "Alt+C abre Configurações");
        key(robot, KeyEvent.VK_ESCAPE);
        h.waitFor(() -> h.app.activeDialog() == null, "Esc fecha");
    }

    @Test
    @Order(12)
    void a13HiddenWindowIsIdle() throws Exception {
        h = DesktopHarness.start(tmp.resolve("a13"), ENV, new String[0], TrayController.SYSTEM);
        MainWindow w = h.window();
        h.run(() -> w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)));
        h.waitFor(() -> !w.isVisible(), "oculta");
        h.settle();
        Thread.sleep(1500); // deixa terminar o que a ação de ocultar disparou (balão da bandeja)
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        Map<Long, Long> before = cpu(mx);
        long wall = System.nanoTime();
        Thread.sleep(10_000);
        wall = System.nanoTime() - wall;
        Map<Long, Long> after = cpu(mx);
        long used = 0;
        Map<String, Long> perThread = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> e : after.entrySet()) {
            long d = e.getValue() - before.getOrDefault(e.getKey(), 0L);
            if (d <= 0) continue;
            used += d;
            var info = mx.getThreadInfo(e.getKey());
            perThread.put(info == null ? "?" : info.getThreadName(), d / 1_000_000);
        }
        double pct = 100.0 * used / wall;
        System.out.printf(Locale.ROOT, "[A13] CPU de todas as threads da JVM de teste com a janela oculta: %d ms em %d ms (%.3f%%) %s%n",
                used / 1_000_000, wall / 1_000_000, pct, perThread);
        List<String> timers = runningTimers();
        System.out.println("[A13] javax.swing.Timer ativos com a janela oculta: " + timers);
        assertTrue(pct < 1.0, "trabalho periódico com a janela oculta: " + perThread);
        assertEquals(1, trayIconCount());
    }

    @Test
    @Order(13)
    void a15NoScreenSuggestsWorkingIntegration() throws Exception {
        h = DesktopHarness.start(tmp.resolve("a15"), ENV, new String[0], TrayController.SYSTEM);
        List<String> all = new ArrayList<>(h.edt(() -> texts(h.window().getRootPane())));
        SettingsDialog s = openSettings();
        all.addAll(h.edt(() -> texts(s.getRootPane())));
        h.run(s::cancel);
        h.later(h.app::openDiagnostics);
        h.waitFor(() -> h.app.activeDialog() instanceof DiagnosticsDialog dd && dd.isShowing(), "diagnóstico");
        JDialog d = h.edt(h.app::activeDialog);
        all.addAll(h.edt(() -> texts(d.getRootPane())));
        h.run(d::dispose);
        for (TrayIcon icon : SystemTray.getSystemTray().getTrayIcons()) {
            all.add(icon.getToolTip());
            for (int i = 0; i < icon.getPopupMenu().getItemCount(); i++) all.add(icon.getPopupMenu().getItem(i).getLabel());
        }
        List<String> offending = new ArrayList<>();
        for (String text : all) {
            String t = text.toLowerCase(Locale.ROOT);
            if (t.matches("(?s).*\\bpronto\\b.*") || (t.contains("pareado") && !t.contains("não pareado") && !t.contains("nao_pareado"))
                    || (t.contains("conectado") && !t.contains("não conectado")) || t.contains("licença ativa")
                    || t.matches("(?s).*execução (disponível|habilitada|ativa).*") || t.contains("agent_id") || t.contains("installation_id")) {
                offending.add(text);
            }
        }
        assertTrue(offending.isEmpty(), offending.toString());
        // estados nunca dependem só de cor: todo selo tem texto
        for (NekkoraStatusBadge b : h.edt(() -> find(h.window().getRootPane(), NekkoraStatusBadge.class))) {
            assertNotNull(b.getAccessibleContext().getAccessibleName());
        }
    }

    // ----- utilidades -----

    private SettingsDialog openSettings() throws Exception {
        h.later(h.app::openSettings);
        h.waitFor(() -> h.app.activeDialog() instanceof SettingsDialog d && d.isShowing(), "Configurações aberto");
        return (SettingsDialog) h.edt(h.app::activeDialog);
    }

    private String log() throws Exception {
        h.ctx.log().flush(5000);
        Path f = h.dataDir.resolve("logs").resolve("agent.log");
        return Files.exists(f) ? Files.readString(f) : "";
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }

    private void fire(ActionListener[] listeners, Object source) throws Exception {
        h.run(() -> {
            for (ActionListener l : listeners) l.actionPerformed(new ActionEvent(source, ActionEvent.ACTION_PERFORMED, ""));
        });
    }

    private static MenuItem item(TrayIcon icon, String label) {
        for (int i = 0; i < icon.getPopupMenu().getItemCount(); i++) {
            if (label.equals(icon.getPopupMenu().getItem(i).getLabel())) return icon.getPopupMenu().getItem(i);
        }
        throw new AssertionError("item ausente: " + label);
    }

    /** Clica com o mouse real no "X" da barra de título; devolve {@code false} se precisou cair no evento. */
    private boolean clickTitleClose(MainWindow w) throws Exception {
        AbstractButton close = h.edt(() -> find(w.getRootPane(), AbstractButton.class).stream()
                .filter(b -> "Close".equals(b.getAccessibleContext().getAccessibleName())).findFirst().orElse(null));
        if (close == null) {
            h.run(() -> w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)));
            return false;
        }
        h.run(w::toFront);
        Point p = h.edt(() -> {
            Point s = close.getLocationOnScreen();
            return new Point(s.x + close.getWidth() / 2, s.y + close.getHeight() / 2);
        });
        // coordenadas lógicas → físicas para o Robot
        GraphicsConfiguration gc = h.edt(w::getGraphicsConfiguration);
        double sc = gc.getDefaultTransform().getScaleX();
        Rectangle gb = gc.getBounds();
        Robot r = new Robot();
        r.setAutoDelay(30);
        r.mouseMove((int) Math.round(gb.x + (p.x - gb.x) * sc), (int) Math.round(gb.y + (p.y - gb.y) * sc));
        r.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        r.mouseMove(gb.x + 5, gb.y + gb.height / 2); // longe das janelas: capturas seguintes sem destaque do mouse
        return true;
    }

    private static void key(Robot r, int code) {
        r.keyPress(code);
        r.keyRelease(code);
    }

    private static JFileChooser chooser() {
        for (Window win : Window.getWindows()) {
            if (win instanceof JDialog d && d.isShowing()) {
                List<JFileChooser> fc = find(d.getContentPane(), JFileChooser.class);
                if (!fc.isEmpty()) return fc.get(0);
            }
        }
        return null;
    }

    /** Timers do Swing na fila (reflexão; requer --add-opens java.desktop/javax.swing no perfil desktop). */
    private static List<String> runningTimers() {
        List<String> out = new ArrayList<>();
        try {
            Class<?> tq = Class.forName("javax.swing.TimerQueue");
            var shared = tq.getDeclaredMethod("sharedInstance");
            shared.setAccessible(true);
            Object queue = shared.invoke(null);
            var f = tq.getDeclaredField("queue");
            f.setAccessible(true);
            for (Object delayed : ((java.util.Collection<?>) f.get(queue))) {
                var gt = delayed.getClass().getDeclaredMethod("getTimer");
                gt.setAccessible(true);
                javax.swing.Timer t = (javax.swing.Timer) gt.invoke(delayed);
                List<String> listeners = new ArrayList<>();
                for (ActionListener l : t.getActionListeners()) listeners.add(l.getClass().getName());
                out.add("delay=" + t.getDelay() + " repete=" + t.isRepeats() + " " + listeners);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            out.add("indisponível: " + e.getClass().getSimpleName());
        }
        return out;
    }

    private static Map<Long, Long> cpu(ThreadMXBean mx) {
        Map<Long, Long> m = new LinkedHashMap<>();
        for (long id : mx.getAllThreadIds()) {
            long t = mx.getThreadCpuTime(id);
            if (t >= 0) m.put(id, t);
        }
        return m;
    }

    static Rectangle usable(Window w) {
        GraphicsConfiguration gc = w.getGraphicsConfiguration();
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(b.x + in.left, b.y + in.top, b.width - in.left - in.right, b.height - in.top - in.bottom);
    }
}
