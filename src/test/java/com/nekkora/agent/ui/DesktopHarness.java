package com.nekkora.agent.ui;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.nekkora.agent.app.AgentBootstrap;
import com.nekkora.agent.app.AgentContext;
import com.nekkora.agent.app.AgentOptions;
import com.nekkora.agent.core.MainViewModel;
import com.nekkora.agent.os.ShortcutLauncher;
import com.nekkora.agent.os.ShortcutPolicy;
import com.nekkora.agent.os.TrayController;
import com.nekkora.agent.storage.AgentDataDir;
import com.nekkora.agent.storage.AtomicWriter;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * Sobe o Agent real (bootstrap, janela, bandeja do Windows) dentro da JVM de teste, com diretório de
 * dados descartável. Atalhos usam registradores (nada é aberto); o encerramento é registrado em vez de
 * chamar System.exit. Capturas pintam somente a janela (sem a área de trabalho).
 */
final class DesktopHarness implements AutoCloseable {
    static final Path EVIDENCE = Path.of(System.getProperty("agent.basedir", "."), "target", "desktop-evidence",
            "escala-" + Math.round(scale() * 100));

    final Path dataDir;
    final AgentContext ctx;
    final AgentApp app;
    final List<Integer> exits = Collections.synchronizedList(new ArrayList<>());

    private DesktopHarness(Path dataDir, AgentContext ctx, TrayController.Port port) {
        this.dataDir = dataDir;
        this.ctx = ctx;
        this.app = new AgentApp(ctx, port, exits::add);
    }

    static DesktopHarness start(Path dataDir, Map<String, String> env, String[] args, TrayController.Port port) throws Exception {
        List<List<String>> startedRef = Collections.synchronizedList(new ArrayList<>());
        List<URI> browsedRef = Collections.synchronizedList(new ArrayList<>());
        ShortcutLauncher launcher = new ShortcutLauncher((cmd, dir) -> startedRef.add(List.copyOf(cmd)), new ShortcutLauncher.Browser() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void browse(URI uri) {
                browsedRef.add(uri);
            }
        }, ShortcutPolicy.FileProbe.DEFAULT, true);
        AgentBootstrap.Environment e = new AgentBootstrap.Environment(new AgentDataDir.Location(dataDir, AgentDataDir.Kind.OVERRIDE), env,
                Clock.systemUTC(), Optional.of(Instant.now()), launcher, ShortcutPolicy.FileProbe.DEFAULT, true, AtomicWriter.DEFAULT);
        AgentContext ctx = assertInstanceOf(AgentBootstrap.Ready.class, AgentBootstrap.start(AgentOptions.parse(args), e)).context();
        DesktopHarness h = new DesktopHarness(dataDir, ctx, port);
        // registradores do lançador: o que seria iniciado/aberto
        h.startedSink = startedRef;
        h.browsedSink = browsedRef;
        MainViewModel initial = ctx.viewModel();
        h.run(() -> h.app.start(initial));
        h.waitFor(() -> h.app.window() != null && h.app.window().isShowing(), "janela visível");
        return h;
    }

    private List<List<String>> startedSink;
    private List<URI> browsedSink;

    List<List<String>> startedCommands() {
        return startedSink;
    }

    List<URI> browsedUris() {
        return browsedSink;
    }

    MainWindow window() {
        return app.window();
    }

    /** {@link #edt} sem aplicativo (componentes testados isoladamente). */
    static <T> T onEdt(Callable<T> c) throws InterruptedException, InvocationTargetException {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ref.set(c.call());
            } catch (Exception ex) {
                err.set(ex);
            }
        });
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }

    <T> T edt(Callable<T> c) throws InterruptedException, InvocationTargetException {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ref.set(c.call());
            } catch (Exception ex) {
                err.set(ex);
            }
        });
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }

    void run(Runnable r) throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(r);
    }

    /** Dispara sem esperar (diálogos modais bloqueiam a chamada até fecharem). */
    void later(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    void waitFor(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (true) {
            AtomicReference<Boolean> ok = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> ok.set(condition.getAsBoolean()));
            if (ok.get()) return;
            if (System.nanoTime() > deadline) throw new AssertionError("tempo esgotado esperando: " + what);
            Thread.sleep(20);
        }
    }

    /** Espera a fila de E/S do app e o EDT ficarem ociosos (duas voltas). */
    void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            ctx.log().flush(5000);
            run(() -> { });
            Thread.sleep(30);
        }
    }

    static int trayIconCount() {
        return SystemTray.isSupported() ? SystemTray.getSystemTray().getTrayIcons().length : 0;
    }

    static double scale() {
        if (GraphicsEnvironment.isHeadless()) return 1;
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform().getScaleX();
    }

    /** Pinta somente o conteúdo da janela (inclui a barra de título do FlatLaf) na resolução real da escala. */
    static Path capture(Window w, String name) throws Exception {
        return capture(w, EVIDENCE, name);
    }

    /** Idem, numa pasta de evidências escolhida (homologação física: funciona com a tela bloqueada). */
    static Path capture(Window w, Path dir, String name) throws Exception {
        AtomicReference<BufferedImage> img = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JRootPane rp = SwingUtilities.getRootPane(w);
            double s = scale();
            BufferedImage b = new BufferedImage((int) Math.ceil(rp.getWidth() * s), (int) Math.ceil(rp.getHeight() * s), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = b.createGraphics();
            g.scale(s, s);
            rp.paint(g);
            g.dispose();
            img.set(b);
        });
        Files.createDirectories(dir);
        Path out = dir.resolve(name + ".png");
        ImageIO.write(img.get(), "png", out.toFile());
        return out;
    }

    /** Todos os textos visíveis de uma árvore de componentes. */
    static List<String> texts(Container root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Component c, List<String> out) {
        if (!c.isVisible()) return;
        if (c instanceof JLabel l && l.getText() != null && !l.getText().isBlank()) out.add(l.getText());
        else if (c instanceof AbstractButton b && b.getText() != null && !b.getText().isBlank()) out.add(b.getText());
        else if (c instanceof JTextComponent t && t.getText() != null && !t.getText().isBlank()) out.add(t.getText());
        if (c instanceof JComponent jc && jc.getAccessibleContext() != null && jc.getAccessibleContext().getAccessibleName() != null
                && c.getClass().getName().contains("Badge")) {
            out.add(jc.getAccessibleContext().getAccessibleName());
        }
        if (c instanceof Container ct) for (Component child : ct.getComponents()) collect(child, out);
    }

    static <T extends Component> List<T> find(Container root, Class<T> type) {
        List<T> out = new ArrayList<>();
        findInto(root, type, out);
        return out;
    }

    private static <T extends Component> void findInto(Component c, Class<T> type, List<T> out) {
        if (type.isInstance(c)) out.add(type.cast(c));
        if (c instanceof Container ct) for (Component child : ct.getComponents()) findInto(child, type, out);
    }

    @Override
    public void close() throws Exception {
        if (!app.isExiting()) run(app::exit);
        ctx.close();
    }

    static void writeConfig(Path dataDir, String json) throws IOException {
        Files.createDirectories(dataDir.resolve("config"));
        Files.writeString(dataDir.resolve("config").resolve("agent-config.json"), json);
    }
}
