package com.nekkora.agent.os;

import com.nekkora.agent.core.ErrorCode;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Abre os destinos dos atalhos — sempre a partir de uma ação humana e fora da EDT.
 *
 * <p>Studio: {@link ProcessBuilder} com UM único elemento (o caminho do {@code .exe} já validado),
 * sem shell, sem argumentos e sem composição de linha de comando; saída descartada. Platform:
 * {@link Desktop#browse} com o endereço validado; nenhuma consulta, login ou sessão presumida.
 */
public final class ShortcutLauncher {

    /** Início de processo (substituível em testes). */
    @FunctionalInterface
    public interface ProcessStarter {
        void start(List<String> command, Path workingDir) throws IOException;

        ProcessStarter DEFAULT = (command, dir) -> {
            Process p = new ProcessBuilder(command)
                    .directory(dir == null ? null : dir.toFile())
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.getOutputStream().close();
        };
    }

    /** Navegador padrão (substituível em testes). */
    public interface Browser {
        boolean available();

        void browse(URI uri) throws IOException;

        Browser DEFAULT = new Browser() {
            @Override
            public boolean available() {
                return !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
            }

            @Override
            public void browse(URI uri) throws IOException {
                Desktop.getDesktop().browse(uri);
            }
        };
    }

    /** Resultado de uma abertura. */
    public sealed interface Result permits Opened, Refused, Failed {}

    public record Opened() implements Result {}

    /** Destino ausente ou inválido: nada foi iniciado. */
    public record Refused(ShortcutPolicy.Reason reason) implements Result {}

    public record Failed(ErrorCode code, String exceptionType) implements Result {}

    private final ProcessStarter starter;
    private final Browser browser;
    private final ShortcutPolicy.FileProbe probe;
    private final boolean windows;

    public ShortcutLauncher(ProcessStarter starter, Browser browser, ShortcutPolicy.FileProbe probe, boolean windows) {
        this.starter = Objects.requireNonNull(starter);
        this.browser = Objects.requireNonNull(browser);
        this.probe = Objects.requireNonNull(probe);
        this.windows = windows;
    }

    public static ShortcutLauncher system() {
        return new ShortcutLauncher(ProcessStarter.DEFAULT, Browser.DEFAULT, ShortcutPolicy.FileProbe.DEFAULT, ShortcutPolicy.isWindows());
    }

    public Result openStudio(String configured) {
        ShortcutPolicy.Check<Path> check = ShortcutPolicy.studioExecutable(configured, windows, probe);
        if (check instanceof ShortcutPolicy.Rejected<Path> r) return new Refused(r.reason());
        Path exe = ((ShortcutPolicy.Valid<Path>) check).target();
        try {
            starter.start(List.of(exe.toString()), exe.getParent());
            return new Opened();
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return new Failed(ErrorCode.STUDIO_LAUNCH_FAILED, e.getClass().getSimpleName());
        }
    }

    public Result openPlatform(String configured) {
        ShortcutPolicy.Check<URI> check = ShortcutPolicy.platformUrl(configured);
        if (check instanceof ShortcutPolicy.Rejected<URI> r) return new Refused(r.reason());
        if (!browser.available()) return new Failed(ErrorCode.BROWSER_UNAVAILABLE, "SemNavegador");
        try {
            browser.browse(((ShortcutPolicy.Valid<URI>) check).target());
            return new Opened();
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return new Failed(ErrorCode.PLATFORM_OPEN_FAILED, e.getClass().getSimpleName());
        }
    }
}
