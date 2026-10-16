package com.nekkora.agent.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.core.ErrorJournal;
import com.nekkora.agent.diagnostics.AgentLog.Field;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A11 (limites): rotação, limite total, falha sem laço nem crescimento e linhas sem texto livre. */
class AgentLogTest {
    @TempDir
    Path dir;

    private final ErrorJournal journal = new ErrorJournal(Clock.systemUTC());

    @Test
    void rotationKeepsEveryFileAndTheTotalWithinLimits() throws IOException {
        Path logs = dir.resolve("logs");
        AgentLog log = AgentLog.open(logs, Clock.systemUTC(), journal);
        // ~6 MiB de eventos: muitas vezes o limite total; em lotes para não estourar a fila
        for (int batch = 0; batch < 300; batch++) {
            for (int i = 0; i < 200; i++) log.info(LogEvent.LIFE_READY, Field.of("startup_ms", i), Field.of("pad", "x".repeat(60)));
            log.flush(5000);
        }
        log.close();
        List<Path> files = list(logs);
        assertTrue(files.size() <= AgentLog.MAX_FILES, "no máximo " + AgentLog.MAX_FILES + " arquivos: " + files);
        long total = 0;
        for (Path p : files) {
            long size = Files.size(p);
            assertTrue(size <= AgentLog.MAX_FILE_BYTES, p + " com " + size + " bytes");
            total += size;
        }
        assertTrue(total <= AgentLog.MAX_TOTAL_BYTES, "total " + total);
        assertTrue(total > AgentLog.MAX_TOTAL_BYTES * 3 / 4, "a rotação aproveita os arquivos");
        assertEquals(AgentLog.State.ACTIVE, log.state());
        assertFalse(journal.contains(ErrorCode.LOG_EVENTS_DROPPED));
    }

    @Test
    void reopeningRemovesRotationsBeyondTheLimit() throws IOException {
        Path logs = Files.createDirectories(dir.resolve("logs"));
        for (String n : new String[] {"agent.4.log", "agent.9.log", "agent.0.log"}) Files.writeString(logs.resolve(n), "antigo");
        Files.writeString(logs.resolve("outro.txt"), "não é nosso");
        AgentLog log = AgentLog.open(logs, Clock.systemUTC(), journal);
        log.info(LogEvent.LIFE_START);
        log.close();
        assertFalse(Files.exists(logs.resolve("agent.4.log")));
        assertFalse(Files.exists(logs.resolve("agent.9.log")));
        assertFalse(Files.exists(logs.resolve("agent.0.log")));
        assertTrue(Files.exists(logs.resolve("outro.txt")), "arquivos que não são do log ficam intactos");
    }

    @Test
    void unusableDirectoryDisablesFileLoggingWithoutThrowingOrRetrying() throws IOException {
        Path notADir = Files.writeString(dir.resolve("logs"), "arquivo no lugar da pasta");
        AgentLog log = AgentLog.open(notADir, Clock.systemUTC(), journal);
        for (int i = 0; i < 10_000; i++) log.info(LogEvent.LIFE_READY);
        log.flush(5000);
        assertEquals(AgentLog.State.UNAVAILABLE, log.state());
        assertTrue(journal.contains(ErrorCode.LOG_UNAVAILABLE));
        assertEquals("arquivo no lugar da pasta", Files.readString(notADir), "nada é gravado");
        log.close();
    }

    @Test
    void rotationFailureStopsWritingAndKeepsSizeBounded() throws IOException {
        Path logs = Files.createDirectories(dir.resolve("logs"));
        // agent.1.log como diretório não vazio: mover agent.log sobre ele falha
        Files.createDirectories(logs.resolve("agent.1.log"));
        Files.writeString(logs.resolve("agent.1.log").resolve("bloqueio"), "x");
        AgentLog log = AgentLog.open(logs, Clock.systemUTC(), journal);
        for (int batch = 0; batch < 40; batch++) {
            for (int i = 0; i < 200; i++) log.info(LogEvent.LIFE_READY, Field.of("pad", "y".repeat(60)));
            log.flush(5000);
        }
        log.close();
        assertEquals(AgentLog.State.UNAVAILABLE, log.state());
        assertTrue(journal.contains(ErrorCode.LOG_ROTATION_FAILED));
        long size = Files.exists(logs.resolve("agent.log")) ? Files.size(logs.resolve("agent.log")) : 0;
        assertTrue(size <= AgentLog.MAX_FILE_BYTES, "sem crescimento além do limite: " + size);
    }

    @Test
    void valuesCannotInjectLinesOrFreeText() throws IOException {
        Path logs = dir.resolve("logs");
        AgentLog log = AgentLog.open(logs, Clock.systemUTC(), journal);
        log.info(LogEvent.CONFIG_SAVED, Field.of("state", "ok\nFAKE_EVENT injetado C:\\Users\\Pessoa https://x.test/?token=1"));
        log.error(LogEvent.UNEXPECTED_ERROR, ErrorCode.UNEXPECTED, Field.exception(new IOException("C:\\Users\\Pessoa\\segredo.txt")));
        log.close();
        List<String> lines = Files.readAllLines(logs.resolve("agent.log"), StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "uma linha por evento: " + lines);
        assertTrue(lines.get(0).matches("\\S+ INFO CONFIG_SAVED state=[A-Za-z0-9._:+-]+"), lines.get(0));
        assertFalse(lines.get(0).contains(" FAKE_EVENT"));
        assertTrue(lines.get(1).endsWith("ERROR UNEXPECTED_ERROR code=AGT-APP-001 exception=IOException"), lines.get(1));
        // a mensagem da exceção nunca entra; o filtro de valores impede quebra de linha e separadores,
        // mas não "apaga" letras — por isso o código nunca passa caminhos, endereços ou nomes como valor
        assertFalse(String.join("\n", lines).contains("segredo"));
        assertFalse(String.join("\n", lines).contains("\\"), "sem barras invertidas");
        assertFalse(String.join("\n", lines).contains("?token="), "sem '?' nem '='");
    }

    @Test
    void fieldKeysAreFixedIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> Field.of("C:\\caminho", "x"));
        assertThrows(IllegalArgumentException.class, () -> Field.of("chave com espaço", "x"));
    }

    @Test
    void disabledLogNeverTouchesTheDisk() {
        AgentLog log = AgentLog.disabled(Clock.systemUTC(), journal);
        log.info(LogEvent.LIFE_START);
        log.error(LogEvent.UNEXPECTED_ERROR, ErrorCode.UNEXPECTED);
        assertEquals(AgentLog.State.DISABLED, log.state());
        assertTrue(journal.contains(ErrorCode.UNEXPECTED), "o código ainda vai para o diagnóstico");
        log.close();
    }

    private static List<Path> list(Path d) throws IOException {
        try (Stream<Path> s = Files.list(d)) {
            return s.toList();
        }
    }
}
