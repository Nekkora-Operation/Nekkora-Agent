package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A02: o Agent caiu, o processo terminou sozinho e publicou o resultado; na reabertura, {@code reconcile()} lê o
 * protocolo CONFERIDO em vez de descartá-lo. Base REAL: a saída do cenário a5 da compat-001 (Framework 0.3.0 publicou
 * {@code STOPPED}, 11 SUCCEEDED e 1 FAILED_TECHNICAL, e o Agent anterior registrou só {@code OUTCOME_UNKNOWN}) e o pacote
 * real gerado pelo Studio. Cada evidência incompleta ou incoerente continua {@code OUTCOME_UNKNOWN}, com o motivo; nada é
 * relançado e nenhuma evidência incompleta vira sucesso. Sem Python: só arquivos e o reconcile.
 */
class ProtocolRecoveryTest {
    @TempDir
    Path tmp;

    Path agentDir;
    Path out;
    RunStore runs;
    ExecutionController controller;

    @BeforeEach
    void setUp() throws Exception {
        agentDir = tmp.resolve("agent");
        out = Files.createDirectories(tmp.resolve("saida"));
        runs = new RunStore(agentDir.resolve("execucoes"), AtomicWriter.DEFAULT);
        controller = new ExecutionController(runs, new ApprovalStore(agentDir.resolve("aprovacoes.json"), AtomicWriter.DEFAULT), new ExecFixtures.FakeSession(),
                Clock.systemUTC(), agentDir, Duration.ofMillis(100), Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        controller.close();
    }

    static Path resource(String rel) throws URISyntaxException {
        return Path.of(ProtocolRecoveryTest.class.getResource("real/" + rel).toURI());
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Uma execução como o Agent anterior deixou no a5: registro RUNNING com a posse de um processo que JÁ terminou (aqui:
     * o PID desta JVM com outro instante de início, ou seja, "não é mais o mesmo processo"), cópia conferida do pacote, a
     * pasta de saída anotada e a saída real do Framework. {@code tweak} altera a saída antes da reconciliação.
     */
    ExecutionId crashedRun(Consumer<Path> tweak) throws Exception {
        ExecutionId id = ExecutionId.random();
        Path copy = runs.dir(id).resolve("pacote");
        copyTree(resource("pacote-studio"), copy);
        ExecutionPackage p = ExecutionPackage.inspect(copy);
        assertEquals("sha256:5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d", p.fingerprint(), "o mesmo pacote do a5");
        Instant launched = Instant.now().minusSeconds(30);
        ExecutionRecord r = new ExecutionRecord(id, p.kind(), "pacote " + p.shortFingerprint(), p.fingerprint(), ExecutionState.READY, null,
                launched.minusSeconds(1), null, null, new ProcessOwner(ProcessHandle.current().pid(), 1L, "sha256:" + "f".repeat(64)), null, Map.of(), 0, 0,
                false, 0).moveTo(ExecutionState.STARTING, null, launched).moveTo(ExecutionState.RUNNING, null, launched);
        runs.save(r);
        runs.event(id, launched, "RUNNING", Map.of("pid", r.owner().pid()));
        Path output = Files.createDirectories(out.resolve(id.value()));
        Files.writeString(runs.dir(id).resolve(ExecutionController.OUTPUT_REF), output.toString(), StandardCharsets.UTF_8);
        Path exec = Files.createDirectories(output.resolve("execucao"));
        for (String f : List.of("summary.json", "events.jsonl", "checkpoint.json")) {
            Files.copy(resource("a5-execucao/" + f), exec.resolve(f));
            touch(exec.resolve(f), Instant.now()); // gravado pelo processo depois do lançamento (a cópia do Windows preserva a data antiga)
        }
        tweak.accept(exec);
        return id;
    }

    ExecutionRecord reconciled(ExecutionId id) {
        assertEquals(1, controller.reconcile());
        assertFalse(controller.running(), "nada é adotado nem relançado");
        return runs.record(id).orElseThrow();
    }

    String events(ExecutionId id) {
        return String.join("\n", runs.events(id));
    }

    static void rewrite(Path f, String from, String to) {
        try {
            String s = Files.readString(f, StandardCharsets.UTF_8);
            assertTrue(s.contains(from), from);
            Files.writeString(f, s.replace(from, to), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Remove as últimas {@code n} linhas do diário (o fim que o Framework grava por último). */
    static void dropLastLines(Path events, int n) {
        try {
            List<String> l = Files.readAllLines(events, StandardCharsets.UTF_8);
            Files.write(events, l.subList(0, l.size() - n), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Sucesso de verdade: resumo COMPLETED com a planilha COMMITTED e o ENGINE_COMPLETED coerente. */
    static void asSuccess(Path exec) {
        rewrite(exec.resolve("summary.json"), "\"status\": \"STOPPED\"", "\"status\": \"COMPLETED\"");
        rewrite(exec.resolve("summary.json"), "\"stop_code\": \"TARGET_NOT_FOUND\"", "\"stop_code\": null");
        rewrite(exec.resolve("summary.json"), "\"status\": \"WITHHELD\"", "\"status\": \"COMMITTED\"");
        rewrite(exec.resolve("events.jsonl"), "\"outcome\":\"STOPPED\"", "\"outcome\":\"COMPLETED\"");
    }

    @Test
    @DisplayName("a5 REAL: STOPPED publicado antes da reabertura → FAILED/AGT-EXE-022 com as contagens do protocolo, recuperado, sem código de saída")
    void a5RealViraFalhaInformada() throws Exception {
        Path output;
        ExecutionId id = crashedRun(exec -> { });
        output = out.resolve(id.value());
        Map<String, byte[]> before = snapshot(output);
        ExecutionRecord r = reconciled(id);
        assertEquals(ExecutionState.FAILED, r.state(), "o protocolo diz STOPPED: falha informada, não desfecho desconhecido");
        assertEquals(ErrorCode.EXEC_REPORTED_FAILURE, r.code());
        assertEquals(new TreeMap<>(Map.of("SUCCEEDED", 11L, "FAILED_TECHNICAL", 1L)), new TreeMap<>(r.counts()));
        assertTrue(r.recovered(), "marcado como lido do protocolo depois do reinício");
        assertNull(r.exitCode(), "o fim do processo não foi observado: nenhum código de saída inventado");
        assertFalse(r.adopted());
        String ev = events(id);
        assertTrue(ev.contains("\"RECONCILE\"") && ev.contains("\"event\": \"FAILED\"") && ev.contains("\"recovered\": true"), ev);
        Map<String, byte[]> after = snapshot(output);
        assertEquals(before.keySet(), after.keySet(), "a saída da automação não foi tocada");
        before.forEach((k, v) -> assertTrue(java.util.Arrays.equals(v, after.get(k)), k));
    }

    @Test
    @DisplayName("protocolo de sucesso completo e coerente (COMPLETED + COMMITTED + ENGINE_COMPLETED) → SUCCEEDED recuperado")
    void sucessoCompleto() throws Exception {
        ExecutionRecord r = reconciled(crashedRun(ProtocolRecoveryTest::asSuccess));
        assertEquals(ExecutionState.SUCCEEDED, r.state());
        assertNull(r.code());
        assertTrue(r.recovered());
        assertNull(r.exitCode());
    }

    @Test
    @DisplayName("COMPLETED sem a planilha COMMITTED (pacote de lista) → FAILED, nunca sucesso")
    void semConsolidacaoNaoESucesso() throws Exception {
        ExecutionRecord r = reconciled(crashedRun(exec -> {
            asSuccess(exec);
            rewrite(exec.resolve("summary.json"), "\"status\": \"COMMITTED\"", "\"status\": \"OPEN\"");
        }));
        assertEquals(ExecutionState.FAILED, r.state());
        assertEquals(ErrorCode.EXEC_REPORTED_FAILURE, r.code());
    }

    /** Evidência incompleta ou incoerente: OUTCOME_UNKNOWN/AGT-EXE-025, motivo no eventos.jsonl, contagens não registradas. */
    void unknown(Consumer<Path> tweak, ProtocolEvidence.Rejection why) throws Exception {
        ExecutionId id = crashedRun(tweak);
        ExecutionRecord r = reconciled(id);
        assertEquals(ExecutionState.OUTCOME_UNKNOWN, r.state(), why.name());
        assertEquals(ErrorCode.EXEC_AGENT_RESTARTED, r.code(), why.name());
        assertFalse(r.recovered(), why.name());
        assertEquals(Map.of(), r.counts(), why.name() + ": contagens de protocolo não conferido não entram");
        assertTrue(events(id).contains("\"event\": \"PROTOCOL_REJECTED\", \"reason\": \"" + why.name() + "\""), why + ": " + events(id));
    }

    @Test
    @DisplayName("sem resumo, resumo truncado ou fora do contrato, estado não final → OUTCOME_UNKNOWN com o motivo")
    void resumoAusenteOuInvalido() throws Exception {
        unknown(exec -> delete(exec.resolve("summary.json")), ProtocolEvidence.Rejection.NO_PROTOCOL);
        unknown(exec -> truncate(exec.resolve("summary.json")), ProtocolEvidence.Rejection.PROTOCOL_INVALID);
        unknown(exec -> rewrite(exec.resolve("summary.json"), "nekkora-rpa/run-summary", "outro/contrato"), ProtocolEvidence.Rejection.PROTOCOL_INVALID);
        unknown(exec -> rewrite(exec.resolve("summary.json"), "\"version\": \"1.2.0\"", "\"version\": \"2.0.0\""), ProtocolEvidence.Rejection.PROTOCOL_INVALID);
        unknown(exec -> rewrite(exec.resolve("summary.json"), "\"status\": \"STOPPED\"", "\"status\": \"RUNNING\""), ProtocolEvidence.Rejection.NOT_FINAL);
    }

    @Test
    @DisplayName("resumo anterior ao lançamento (sobra de outra coisa) → OUTCOME_UNKNOWN")
    void resumoAntigo() throws Exception {
        unknown(exec -> touch(exec.resolve("summary.json"), Instant.now().minus(Duration.ofHours(2))), ProtocolEvidence.Rejection.OLDER_THAN_LAUNCH);
    }

    @Test
    @DisplayName("diário sem ENGINE_COMPLETED no fim (cortado, ou última linha pela metade) → OUTCOME_UNKNOWN mesmo com resumo de sucesso")
    void diarioIncompleto() throws Exception {
        unknown(exec -> {
            asSuccess(exec);
            dropLastLines(exec.resolve("events.jsonl"), 1);
        }, ProtocolEvidence.Rejection.NO_ENGINE_COMPLETED);
        unknown(exec -> {
            asSuccess(exec);
            try {
                String s = Files.readString(exec.resolve("events.jsonl"), StandardCharsets.UTF_8).stripTrailing();
                Files.writeString(exec.resolve("events.jsonl"), s.substring(0, s.length() - 40), StandardCharsets.UTF_8); // linha final sem quebra
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, ProtocolEvidence.Rejection.NO_ENGINE_COMPLETED);
        unknown(exec -> {
            asSuccess(exec);
            delete(exec.resolve("events.jsonl"));
        }, ProtocolEvidence.Rejection.NO_ENGINE_COMPLETED);
    }

    @Test
    @DisplayName("resumo e diário incoerentes (outro desfecho, outra execução do Framework) → OUTCOME_UNKNOWN, nunca sucesso")
    void incoerente() throws Exception {
        unknown(exec -> {
            asSuccess(exec);
            rewrite(exec.resolve("events.jsonl"), "\"outcome\":\"COMPLETED\"", "\"outcome\":\"STOPPED\"");
        }, ProtocolEvidence.Rejection.ENGINE_MISMATCH);
        unknown(exec -> {
            asSuccess(exec);
            rewrite(exec.resolve("summary.json"), "\"execution_id\": \"ex-9542a4f62e624cbfb253\"", "\"execution_id\": \"ex-00000000000000000000\"");
        }, ProtocolEvidence.Rejection.ENGINE_MISMATCH);
    }

    @Test
    @DisplayName("cópia do pacote alterada, ou pasta de saída anotada inválida → OUTCOME_UNKNOWN")
    void copiaOuSaidaInvalida() throws Exception {
        ExecutionId a = crashedRun(ProtocolRecoveryTest::asSuccess);
        Files.writeString(runs.dir(a).resolve("pacote").resolve("main.py"), "# alterado depois\n", StandardOpenOption.APPEND);
        ExecutionRecord ra = reconciled(a);
        assertEquals(ExecutionState.OUTCOME_UNKNOWN, ra.state());
        assertTrue(events(a).contains("NO_PACKAGE_COPY"), events(a));
        ExecutionId b = crashedRun(ProtocolRecoveryTest::asSuccess);
        Files.writeString(runs.dir(b).resolve(ExecutionController.OUTPUT_REF), tmp.resolve("outra").toString(), StandardCharsets.UTF_8);
        ExecutionRecord rb = reconciled(b);
        assertEquals(ExecutionState.OUTCOME_UNKNOWN, rb.state());
        assertTrue(events(b).contains("NO_OUTPUT_FOLDER"), events(b));
    }

    static void delete(Path f) {
        try {
            Files.delete(f);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static void truncate(Path f) {
        try {
            String s = Files.readString(f, StandardCharsets.UTF_8);
            Files.writeString(f, s.substring(0, s.length() / 2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static void touch(Path f, Instant t) {
        try {
            Files.setLastModifiedTime(f, FileTime.from(t));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static Map<String, byte[]> snapshot(Path dir) throws IOException {
        Map<String, byte[]> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) m.put(dir.relativize(p).toString(), Files.readAllBytes(p));
        }
        return m;
    }
}
