package com.nekkora.agent.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.diagnostics.AgentLog.Field;
import com.nekkora.agent.diagnostics.DiagnosticReport;
import com.nekkora.agent.diagnostics.LogEvent;
import com.nekkora.agent.os.ShortcutLauncher;
import com.nekkora.agent.storage.AgentConfig;
import com.nekkora.agent.storage.AgentDataDir;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.ConfigStore;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A11: valores sintéticos sensíveis não aparecem em logs nem no diagnóstico exportado; o controle
 * positivo prova que o detector acusaria o vazamento.
 */
class SensitiveDataLeakTest {
    static final String MACHINE = "MAQUINA-SIGILOSA-7Q2";
    static final String PERSON = "PessoaSigilosa7Q2";
    static final String HOST = "plataforma-sigilosa-7q2.example";
    static final String TOKEN = "tok_SIGILOSO_7Q2";
    static final List<String> MARKERS = List.of(MACHINE, PERSON, HOST, TOKEN, "7Q2", "7q2");

    @TempDir
    Path tmp;

    @Test
    void logsAndDefaultExportContainNoSyntheticSensitiveValue() throws Exception {
        Path data = tmp.resolve("Users").resolve(PERSON).resolve("AppData").resolve("Local").resolve("Nekkora").resolve("Agent");
        // configuração com destinos sensíveis (um deles inválido, com token na consulta)
        Files.createDirectories(data.resolve("config"));
        Files.writeString(data.resolve("config").resolve("agent-config.json"), ConfigStoreAccess.encode(
                new AgentConfig("sakura-dark", "https://" + HOST + "/painel?token=" + TOKEN, "C:\\Users\\" + PERSON + "\\Studio\\Studio.exe")));

        List<String> launched = new ArrayList<>();
        ShortcutLauncher launcher = new ShortcutLauncher((cmd, dir) -> {
            launched.add(String.join(" ", cmd));
            throw new IOException("CreateProcess error=2 em " + cmd.get(0) + " para " + MACHINE);
        }, new ShortcutLauncher.Browser() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void browse(URI uri) throws IOException {
                throw new IOException("navegador falhou em " + uri);
            }
        }, p -> true, true);
        AtomicWriter failingWriter = (target, bytes) -> {
            throw new IOException("acesso negado: " + target + " " + TOKEN);
        };
        Map<String, String> env = Map.of("COMPUTERNAME", MACHINE, "USERPROFILE", "C:\\Users\\" + PERSON, "NEKKORA_TOKEN", TOKEN);
        AgentBootstrap.Environment e = new AgentBootstrap.Environment(new AgentDataDir.Location(data, AgentDataDir.Kind.OVERRIDE), env,
                Clock.systemUTC(), Optional.of(Instant.now()), launcher, p -> true, true, failingWriter);
        AgentBootstrap.Ready ready = assertInstanceOf(AgentBootstrap.Ready.class, AgentBootstrap.start(AgentOptions.parse(
                new String[] {"--token=" + TOKEN, "C:\\Users\\" + PERSON}), e));
        AgentContext ctx = ready.context();
        try {
            // exercita os caminhos de erro com mensagens de exceção carregadas de dados sensíveis
            ShortcutLauncher.Result studio = ctx.launcher().openStudio(ctx.config().current().studioExecutable());
            ShortcutLauncher.Failed f = assertInstanceOf(ShortcutLauncher.Failed.class, studio);
            ctx.log().error(LogEvent.SHORTCUT_STUDIO_FAILED, f.code(), Field.of("exception", f.exceptionType()));
            ShortcutLauncher.Refused r = assertInstanceOf(ShortcutLauncher.Refused.class, ctx.launcher().openPlatform(ctx.config().current().platformUrl()));
            ctx.log().warn(LogEvent.SHORTCUT_REJECTED, ErrorCode.PLATFORM_INVALID, Field.of("target", "Platform"), Field.of("reason", r.reason().name()));
            ConfigStore.SaveFailed sf = assertInstanceOf(ConfigStore.SaveFailed.class,
                    ctx.config().save(new AgentConfig("sage-light", "https://" + HOST, "C:\\Users\\" + PERSON + "\\x.exe")));
            ctx.log().error(LogEvent.CONFIG_SAVE_FAILED, sf.code(), Field.of("exception", sf.exceptionType()));
            ctx.log().error(LogEvent.UNEXPECTED_ERROR, ErrorCode.UNEXPECTED, Field.exception(new IllegalStateException(TOKEN + " " + PERSON)));
            ctx.log().flush(5000);
            assertEquals(1, launched.size(), "o executável foi de fato tentado (caminho sensível passou pelo código)");

            String logs = readAll(ctx.dataDir().logsDir());
            assertTrue(logs.contains("SHORTCUT_STUDIO_FAILED code=AGT-SC-003 exception=IOException"), logs);
            assertNoMarker("logs", logs);

            String export = DiagnosticReport.toJson(ctx.diagnosticFacts(), false, Instant.now());
            assertTrue(export.contains("AGT-SC-003"));
            assertNoMarker("diagnóstico", export);

            // controle positivo 1: a exportação com a opção marcada contém o nome — o detector acusa
            String withName = DiagnosticReport.toJson(ctx.diagnosticFacts(), true, Instant.now());
            assertTrue(findMarker(withName).isPresent(), "o detector precisa acusar o nome incluído por opção");

            // controle positivo 2: uma linha vazada gravada no mesmo diretório é detectada
            Path leaked = ctx.dataDir().logsDir().resolve("agent.3.log");
            Files.writeString(leaked, "2026-10-16T00:00:00Z INFO VAZAMENTO path=C:\\Users\\" + PERSON + "\n");
            assertTrue(findMarker(readAll(ctx.dataDir().logsDir())).isPresent(), "o detector precisa acusar o vazamento simulado");
        } finally {
            ctx.close();
        }
    }

    static void assertNoMarker(String where, String text) {
        Optional<String> found = findMarker(text);
        assertFalse(found.isPresent(), () -> "valor sensível em " + where + ": " + found.get());
    }

    static Optional<String> findMarker(String text) {
        return MARKERS.stream().filter(text::contains).findFirst();
    }

    static String readAll(Path dir) throws IOException {
        StringBuilder b = new StringBuilder();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) b.append(Files.readString(p, StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    /** Acesso ao codificador do formato (pacote do armazenamento). */
    static final class ConfigStoreAccess {
        static String encode(AgentConfig c) throws IOException {
            Path t = Files.createTempFile("cfg", ".json");
            try {
                ConfigStore s = new ConfigStore(t, AtomicWriter.DEFAULT, Clock.systemUTC());
                Files.delete(t);
                s.load();
                s.save(c);
                return Files.readString(t);
            } finally {
                Files.deleteIfExists(t);
            }
        }
    }
}
