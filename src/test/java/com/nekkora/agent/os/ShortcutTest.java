package com.nekkora.agent.os;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.core.ShortcutState;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A10: atalhos válidos, ausentes e inválidos — destino esperado, nenhum shell/comando injetado, nada automático. */
class ShortcutTest {

    private final List<List<String>> started = new ArrayList<>();
    private final List<URI> browsed = new ArrayList<>();
    private final ShortcutLauncher.Browser browser = new ShortcutLauncher.Browser() {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void browse(URI uri) {
            browsed.add(uri);
        }
    };
    private final ShortcutLauncher launcher = new ShortcutLauncher((cmd, dir) -> started.add(List.copyOf(cmd)), browser, p -> true, true);

    @ParameterizedTest
    @ValueSource(strings = {"https://platform.exemplo.test", "http://localhost:8080/app", "HTTPS://Platform.Exemplo.Test/a/b"})
    void validUrlsAreAccepted(String url) {
        assertInstanceOf(ShortcutPolicy.Valid.class, ShortcutPolicy.platformUrl(url));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "javascript:alert(1)|URL_SCHEME",
        "file:///C:/Windows/System32/calc.exe|URL_SCHEME",
        "ftp://servidor.test|URL_SCHEME",
        "platform.exemplo.test|URL_SCHEME",
        "https://usuario:senha@platform.test|URL_CREDENTIALS",
        "https://platform.test@evil.test|URL_CREDENTIALS",
        "https://platform.test/?token=abc|URL_PARAMETERS",
        "https://platform.test/#access_token=abc|URL_PARAMETERS",
        "https://plat form.test|URL_SYNTAX",
        "https:///sem-servidor|URL_HOST"
    })
    void invalidUrlsAreRejectedWithReason(String url, ShortcutPolicy.Reason reason) {
        ShortcutPolicy.Rejected<?> r = assertInstanceOf(ShortcutPolicy.Rejected.class, ShortcutPolicy.platformUrl(url));
        assertEquals(reason, r.reason());
        assertEquals(ShortcutState.INVALID, r.state());
    }

    @Test
    void blankDestinationsAreNotConfigured() {
        assertEquals(ShortcutState.NOT_CONFIGURED, ShortcutPolicy.platformUrl("  ").state());
        assertEquals(ShortcutState.NOT_CONFIGURED, ShortcutPolicy.studioExecutableSyntax(null, true).state());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "C:\\Programas\\Studio\\run.cmd|PATH_NOT_EXE",
        "C:\\Programas\\Studio\\run.bat|PATH_NOT_EXE",
        "C:\\Programas\\Studio\\run.ps1|PATH_NOT_EXE",
        "C:\\Programas\\Studio\\Studio.lnk|PATH_NOT_EXE",
        "C:\\Programas\\Studio\\Studio.exe --modo=x|PATH_NOT_EXE",
        "C:\\Windows\\System32\\cmd.exe /c calc|PATH_CHARACTERS",
        "Studio.exe|PATH_NOT_ABSOLUTE",
        "\\\\servidor\\share\\Studio.exe|PATH_NETWORK",
        "C:\\Programas\\Studio.exe\" & calc.exe|PATH_CHARACTERS",
        "C:\\Programas\\%APPDATA%\\Studio.exe|PATH_CHARACTERS",
        "C:\\Programas\\Studio.exe > saida.txt|PATH_CHARACTERS",
        "C:\\Programas\\Studio*.exe|PATH_CHARACTERS",
        "C:\\Windows\\System32\\cmd.exe|PATH_INTERPRETER",
        "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\PowerShell.exe|PATH_INTERPRETER",
        "C:\\Windows\\System32\\mshta.exe|PATH_INTERPRETER"
    })
    void invalidExecutablesAreRejectedWithReason(String path, ShortcutPolicy.Reason reason) {
        ShortcutPolicy.Rejected<?> r = assertInstanceOf(ShortcutPolicy.Rejected.class, ShortcutPolicy.studioExecutableSyntax(path, true));
        assertEquals(reason, r.reason());
    }

    @Test
    void studioStartsOnlyTheValidatedExecutableWithoutArgumentsOrShell() {
        String exe = "C:\\Programas\\Nekkora Studio\\Nekkora Studio.exe";
        assertInstanceOf(ShortcutLauncher.Opened.class, launcher.openStudio(exe));
        assertEquals(List.of(List.of(exe)), started, "um único elemento: o executável, sem argumentos");
    }

    @Test
    void invalidOrMissingDestinationsStartNothing() {
        ShortcutLauncher missing = new ShortcutLauncher((cmd, dir) -> started.add(cmd), browser, p -> false, true);
        assertEquals(ShortcutPolicy.Reason.PATH_MISSING,
                assertInstanceOf(ShortcutLauncher.Refused.class, missing.openStudio("C:\\nao\\existe.exe")).reason());
        assertInstanceOf(ShortcutLauncher.Refused.class, launcher.openStudio("C:\\x\\run.cmd"));
        assertInstanceOf(ShortcutLauncher.Refused.class, launcher.openStudio(null));
        assertInstanceOf(ShortcutLauncher.Refused.class, launcher.openPlatform("https://x.test/?token=1"));
        assertInstanceOf(ShortcutLauncher.Refused.class, launcher.openPlatform(null));
        assertTrue(started.isEmpty());
        assertTrue(browsed.isEmpty());
    }

    @Test
    void platformOpensOnlyTheValidatedAddress() {
        assertInstanceOf(ShortcutLauncher.Opened.class, launcher.openPlatform(" https://platform.exemplo.test/app "));
        assertEquals(List.of(URI.create("https://platform.exemplo.test/app")), browsed);
    }

    @Test
    void failuresCarryOnlyCodeAndExceptionClass() {
        ShortcutLauncher failing = new ShortcutLauncher((cmd, dir) -> {
            throw new IOException("CreateProcess error=5 em C:\\Users\\PessoaSigilosa\\Studio.exe");
        }, new ShortcutLauncher.Browser() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void browse(URI uri) throws IOException {
                throw new IOException("falhou ao abrir " + uri);
            }
        }, p -> true, true);
        ShortcutLauncher.Failed s = assertInstanceOf(ShortcutLauncher.Failed.class, failing.openStudio("C:\\x\\Studio.exe"));
        assertEquals(ErrorCode.STUDIO_LAUNCH_FAILED, s.code());
        assertEquals("IOException", s.exceptionType());
        ShortcutLauncher.Failed p = assertInstanceOf(ShortcutLauncher.Failed.class, failing.openPlatform("https://platform.test"));
        assertEquals(ErrorCode.PLATFORM_OPEN_FAILED, p.code());

        ShortcutLauncher noBrowser = new ShortcutLauncher((c, d) -> { }, new ShortcutLauncher.Browser() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public void browse(URI uri) {
                throw new AssertionError("não deveria abrir");
            }
        }, x -> true, true);
        assertEquals(ErrorCode.BROWSER_UNAVAILABLE,
                assertInstanceOf(ShortcutLauncher.Failed.class, noBrowser.openPlatform("https://platform.test")).code());
    }

    @Test
    void pipeIsRejected() {
        ShortcutPolicy.Rejected<?> r = assertInstanceOf(ShortcutPolicy.Rejected.class,
                ShortcutPolicy.studioExecutableSyntax("C:\\Programas\\Studio.exe | calc", true));
        assertEquals(ShortcutPolicy.Reason.PATH_CHARACTERS, r.reason());
    }

    @Test
    void validExecutableIsNormalized() {
        ShortcutPolicy.Valid<?> v = assertInstanceOf(ShortcutPolicy.Valid.class,
                ShortcutPolicy.studioExecutableSyntax("C:\\Programas\\.\\Studio\\..\\Studio\\S.exe", true));
        assertEquals(Path.of("C:\\Programas\\Studio\\S.exe"), v.target());
    }
}
