package com.nekkora.agent.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nekkora.agent.core.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A08: configuração corrompida/incompatível e falha de escrita — aviso correto, sem falsa confirmação nem perda silenciosa. */
class ConfigStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T20:00:00Z"), ZoneOffset.UTC);
    private static final AgentConfig SAMPLE = new AgentConfig("midnight-dark", "https://platform.exemplo.test/app", "C:\\Programas\\Studio\\Studio.exe");

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("config").resolve("agent-config.json");
    }

    private ConfigStore store() {
        return new ConfigStore(file(), AtomicWriter.DEFAULT, CLOCK);
    }

    @Test
    void missingFileUsesDefaultsAndWritesNothing() {
        ConfigStore s = store();
        ConfigStore.Load load = s.load();
        assertEquals(ConfigStore.State.MISSING, load.state());
        assertEquals(AgentConfig.defaults(), load.config());
        assertFalse(Files.exists(file()));
        assertFalse(Files.exists(dir.resolve("config")), "o primeiro início não grava configuração");
    }

    @Test
    void saveThenLoadRoundTripsAllFields() throws IOException {
        ConfigStore s = store();
        s.load();
        assertInstanceOf(ConfigStore.Saved.class, s.save(SAMPLE));
        ConfigStore again = store();
        ConfigStore.Load load = again.load();
        assertEquals(ConfigStore.State.LOADED, load.state());
        assertEquals(SAMPLE, load.config());
        String text = Files.readString(file());
        assertTrue(text.contains("\"format\": \"nekkora-agent/config\""));
        assertTrue(text.contains("\"version\": 1"));
        assertFalse(text.toLowerCase().contains("token") || text.toLowerCase().contains("senha"));
        assertNoTempFiles();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "isto não é json",
        "{\"format\": \"nekkora-agent/config\", \"version\": 1, \"theme\": \"sakura-dark\"",
        "{\"format\": \"outro/formato\", \"version\": 1, \"theme\": \"sakura-dark\"}",
        "{\"format\": \"nekkora-agent/config\", \"version\": \"1\", \"theme\": \"sakura-dark\"}",
        "{\"format\": \"nekkora-agent/config\", \"version\": 1, \"theme\": \"sakura-dark\", \"token\": \"x\"}",
        "{\"format\": \"nekkora-agent/config\", \"version\": 1, \"theme\": \"../../x\"}",
        "{\"format\": \"nekkora-agent/config\", \"version\": 1, \"theme\": \"sakura-dark\", \"shortcuts\": {\"platformUrl\": 5}}",
        "{\"format\": \"nekkora-agent/config\", \"version\": 0, \"theme\": \"sakura-dark\"}",
        ""
    })
    void corruptedFileIsPreservedAndBlocksPlainSave(String content) throws IOException {
        byte[] original = content.getBytes(StandardCharsets.UTF_8);
        write(original);
        ConfigStore s = store();
        ConfigStore.Load load = s.load();
        assertEquals(ConfigStore.State.CORRUPTED, load.state());
        assertEquals(ErrorCode.CONFIG_CORRUPTED, load.state().code());
        assertEquals(AgentConfig.defaults(), load.config(), "sessão usa padrões");

        ConfigStore.SaveResult r = s.save(SAMPLE);
        ConfigStore.SaveFailed f = assertInstanceOf(ConfigStore.SaveFailed.class, r, "sem confirmação falsa");
        assertEquals(ErrorCode.CONFIG_CORRUPTED, f.code());
        assertArrayEquals(original, Files.readAllBytes(file()), "arquivo incompatível não é sobrescrito em silêncio");
        assertEquals(AgentConfig.defaults(), s.current());
    }

    @Test
    void invalidUtf8AndOversizedFilesAreCorrupted() throws IOException {
        write(new byte[] {'{', (byte) 0xC3, (byte) 0x28, '}'});
        assertEquals(ConfigStore.State.CORRUPTED, store().load().state());
        write(new byte[ConfigStore.MAX_BYTES + 1]);
        assertEquals(ConfigStore.State.CORRUPTED, store().load().state());
    }

    @Test
    void newerVersionIsUnsupportedAndPreserved() throws IOException {
        byte[] original = "{\"format\": \"nekkora-agent/config\", \"version\": 2, \"theme\": \"x\", \"novo\": true}".getBytes(StandardCharsets.UTF_8);
        write(original);
        ConfigStore s = store();
        assertEquals(ConfigStore.State.UNSUPPORTED_VERSION, s.load().state());
        assertInstanceOf(ConfigStore.SaveFailed.class, s.save(SAMPLE));
        assertArrayEquals(original, Files.readAllBytes(file()));
    }

    @Test
    void explicitPreserveKeepsACopyAndCreatesANewFile() throws IOException {
        byte[] original = "{ quebrado".getBytes(StandardCharsets.UTF_8);
        write(original);
        ConfigStore s = store();
        s.load();
        ConfigStore.Saved saved = assertInstanceOf(ConfigStore.Saved.class, s.preserveAndSave(SAMPLE));
        assertEquals("agent-config.json.incompativel-20261016T200000Z", saved.preservedAs());
        assertArrayEquals(original, Files.readAllBytes(file().resolveSibling(saved.preservedAs())), "cópia byte a byte");
        assertEquals(SAMPLE, store().load().config());
        assertEquals(ConfigStore.State.LOADED, s.state());

        // segunda preservação no mesmo segundo não sobrescreve a primeira cópia
        write("{ outro quebrado".getBytes(StandardCharsets.UTF_8));
        ConfigStore s2 = store();
        s2.load();
        ConfigStore.Saved saved2 = assertInstanceOf(ConfigStore.Saved.class, s2.preserveAndSave(SAMPLE));
        assertEquals("agent-config.json.incompativel-20261016T200000Z-2", saved2.preservedAs());
        assertArrayEquals(original, Files.readAllBytes(file().resolveSibling(saved.preservedAs())));
    }

    @Test
    void unreadableLocationIsReportedAndNotReplaced() throws IOException {
        Files.createDirectories(file()); // um diretório no lugar do arquivo
        ConfigStore s = store();
        assertEquals(ConfigStore.State.UNREADABLE, s.load().state());
        assertEquals(ErrorCode.CONFIG_UNREADABLE, s.state().code());
        assertInstanceOf(ConfigStore.SaveFailed.class, s.save(SAMPLE));
        assertTrue(Files.isDirectory(file()));
    }

    @Test
    void writeFailureKeepsPreviousConfigAndAnnouncesNothing() throws IOException {
        ConfigStore ok = store();
        ok.load();
        ok.save(SAMPLE);
        byte[] before = Files.readAllBytes(file());

        // escritor que grava metade em um temporário e falha antes de substituir o destino
        AtomicWriter failing = (target, data) -> {
            Path tmp = Files.createTempFile(target.getParent(), ".parcial.", ".tmp");
            try {
                Files.write(tmp, java.util.Arrays.copyOf(data, data.length / 2));
                throw new IOException("disco cheio (simulado)");
            } finally {
                Files.deleteIfExists(tmp);
            }
        };
        ConfigStore s = new ConfigStore(file(), failing, CLOCK);
        assertEquals(SAMPLE, s.load().config());
        AgentConfig next = SAMPLE.withTheme("sage-light");
        ConfigStore.SaveFailed f = assertInstanceOf(ConfigStore.SaveFailed.class, s.save(next));
        assertEquals(ErrorCode.CONFIG_WRITE_FAILED, f.code());
        assertEquals("IOException", f.exceptionType(), "só o nome da classe, nunca a mensagem");
        assertEquals(SAMPLE, s.current(), "última configuração válida continua em uso");
        assertArrayEquals(before, Files.readAllBytes(file()), "destino intacto, sem arquivo parcial");
        assertNoTempFiles();
    }

    @Test
    void realWriteFailureOnReadOnlyFileOnWindows() throws IOException {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "atributo somente leitura do Windows");
        ConfigStore s = store();
        s.load();
        s.save(SAMPLE);
        byte[] before = Files.readAllBytes(file());
        DosFileAttributeView dos = Files.getFileAttributeView(file(), DosFileAttributeView.class);
        dos.setReadOnly(true);
        try {
            ConfigStore.SaveFailed f = assertInstanceOf(ConfigStore.SaveFailed.class, s.save(SAMPLE.withTheme("sage-light")));
            assertEquals(ErrorCode.CONFIG_WRITE_FAILED, f.code());
            assertNotNull(f.exceptionType());
            assertArrayEquals(before, Files.readAllBytes(file()));
            assertEquals(SAMPLE, s.current());
            assertNoTempFiles();
        } finally {
            dos.setReadOnly(false);
        }
    }

    @Test
    void preserveFailureIsReportedWithoutLosingTheFile() throws IOException {
        byte[] original = "{ quebrado".getBytes(StandardCharsets.UTF_8);
        write(original);
        ConfigStore s = store();
        s.load();
        // ocupa todos os nomes de cópia possíveis
        String base = "agent-config.json.incompativel-20261016T200000Z";
        Files.createDirectories(file().resolveSibling(base));
        for (int n = 2; n <= 50; n++) Files.createDirectories(file().resolveSibling(base + "-" + n));
        ConfigStore.SaveFailed f = assertInstanceOf(ConfigStore.SaveFailed.class, s.preserveAndSave(SAMPLE));
        assertEquals(ErrorCode.CONFIG_PRESERVE_FAILED, f.code());
        assertNull(f.preservedAs());
        assertArrayEquals(original, Files.readAllBytes(file()));
    }

    @Test
    void encodedFileContainsOnlyTheClosedFieldSet() {
        String json = ConfigStore.encode(SAMPLE);
        List<String> keys = Stream.of(json.split("\n")).filter(l -> l.contains("\":"))
                .map(l -> l.strip().substring(1, l.strip().indexOf('"', 1))).toList();
        assertEquals(List.of("format", "version", "theme", "shortcuts", "platformUrl", "studioExecutable"), keys);
    }

    private void write(byte[] bytes) throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), bytes);
    }

    private void assertNoTempFiles() throws IOException {
        try (Stream<Path> s = Files.list(file().getParent())) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "nenhum temporário deixado para trás");
        }
    }
}
