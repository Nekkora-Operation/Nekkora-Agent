package com.nekkora.agent.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.os.ShortcutLauncher;
import com.nekkora.agent.storage.AgentDataDir;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.ConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ordem de inicialização: a segunda instância não toca em nada; sem diretório, falha explícita. */
class AgentBootstrapTest {
    @TempDir
    Path tmp;

    static AgentBootstrap.Environment env(Path dir, Map<String, String> vars) {
        return new AgentBootstrap.Environment(new AgentDataDir.Location(dir, AgentDataDir.Kind.OVERRIDE), vars, Clock.systemUTC(),
                Optional.of(Instant.now()), new ShortcutLauncher((c, d) -> { }, ShortcutLauncher.Browser.DEFAULT, p -> false, true),
                p -> false, true, AtomicWriter.DEFAULT);
    }

    @Test
    void secondInstanceIsRefusedAndWritesNothing() throws Exception {
        Path dir = tmp.resolve("agent");
        AgentContext first = assertInstanceOf(AgentBootstrap.Ready.class,
                AgentBootstrap.start(AgentOptions.parse(new String[0]), env(dir, Map.of("COMPUTERNAME", "LAB-01")))).context();
        try {
            first.config().save(first.config().current().withTheme("sage-light"));
            first.log().flush(5000);
            byte[] config = Files.readAllBytes(dir.resolve("config").resolve("agent-config.json"));
            byte[] log = Files.readAllBytes(dir.resolve("logs").resolve("agent.log"));

            assertInstanceOf(AgentBootstrap.AlreadyRunning.class,
                    AgentBootstrap.start(AgentOptions.parse(new String[0]), env(dir, Map.of("COMPUTERNAME", "LAB-01"))));

            first.log().flush(5000);
            assertArrayEquals(config, Files.readAllBytes(dir.resolve("config").resolve("agent-config.json")), "configuração intocada");
            assertArrayEquals(log, Files.readAllBytes(dir.resolve("logs").resolve("agent.log")), "logs da primeira instância intocados");
        } finally {
            first.close();
        }
        // depois de sair, a trava está livre e a configuração preservada
        AgentContext again = assertInstanceOf(AgentBootstrap.Ready.class,
                AgentBootstrap.start(AgentOptions.parse(new String[0]), env(dir, Map.of()))).context();
        try {
            assertEquals("sage-light", again.config().current().themeId());
            assertEquals(ConfigStore.State.LOADED, again.config().state());
            assertTrue(again.machineName().isEmpty());
            assertTrue(again.journal().contains(ErrorCode.MACHINE_NAME_UNAVAILABLE));
        } finally {
            again.close();
        }
    }

    @Test
    void unusableDataDirectoryIsFatal() throws Exception {
        Path file = Files.writeString(tmp.resolve("nao-e-pasta"), "x");
        AgentBootstrap.Fatal f = assertInstanceOf(AgentBootstrap.Fatal.class,
                AgentBootstrap.start(AgentOptions.parse(new String[0]), env(file, Map.of())));
        assertEquals(ErrorCode.DATA_DIR_UNAVAILABLE, f.code());
    }

    @Test
    void dataDirectoryIsSeparateFromStudioAndExtension() {
        AgentDataDir.Location l = AgentDataDir.locate(null, Map.of("LOCALAPPDATA", "C:\\Users\\u\\AppData\\Local"), "Windows 11", "C:\\Users\\u");
        assertEquals(Path.of("C:\\Users\\u\\AppData\\Local\\Nekkora\\Agent"), l.path());
        assertEquals(AgentDataDir.Kind.DEFAULT, l.kind());
        assertFalse(l.path().toString().contains("Studio"));
        AgentDataDir.Location o = AgentDataDir.locate(null, Map.of("NEKKORA_AGENT_DATA_DIR", "D:\\lab\\agent"), "Windows 11", "C:\\Users\\u");
        assertEquals(AgentDataDir.Kind.OVERRIDE, o.kind());
        assertEquals(AgentDataDir.Kind.OVERRIDE, AgentDataDir.locate("E:\\x", Map.of(), "Windows 11", "C:\\Users\\u").kind());
    }

    @Test
    void optionsAcceptOnlyTheNoTrayFlag() {
        AgentOptions o = AgentOptions.parse(new String[] {"--sem-bandeja", "--outra", "C:\\arquivo"});
        assertTrue(o.trayDisabled());
        assertEquals(2, o.ignoredArguments());
    }
}
