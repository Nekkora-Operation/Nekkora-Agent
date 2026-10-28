package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionRecord.PartialProgress;
import com.nekkora.agent.storage.AtomicWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A03: depois de um cancelamento (ou de qualquer fim sem resultado final), o progresso de negócio que já aconteceu fica
 * no registro e na tela, VERIFICADO pela integridade do checkpoint do Framework, e separado do desfecho: o estado continua
 * {@code CANCELLED} (ou {@code OUTCOME_UNKNOWN}), as contagens do protocolo continuam vazias, uma transação em andamento
 * continua de desfecho desconhecido e a planilha nunca é dada como consolidada. Base REAL: os checkpoints dos cenários
 * a2 (cancelamento: 7 SUCCEEDED, 1 IN_PROGRESS) e a5 da compat-001; e um processo real cancelado no meio, com o
 * {@code CheckpointStore} real do Framework 0.3.0.
 */
class PartialProgressTest {
    @TempDir
    Path tmp;

    Path python;
    Path agentDir;
    Path out;
    ApprovalStore approvals;
    RunStore runs;
    ExecutionController controller;

    @BeforeEach
    void setUp() throws Exception {
        python = ExecFixtures.python();
        agentDir = tmp.resolve("agent");
        out = Files.createDirectories(tmp.resolve("saida"));
        approvals = new ApprovalStore(agentDir.resolve("aprovacoes.json"), AtomicWriter.DEFAULT);
        runs = new RunStore(agentDir.resolve("execucoes"), AtomicWriter.DEFAULT);
        controller = new ExecutionController(runs, approvals, new ExecFixtures.FakeSession(), Clock.systemUTC(), agentDir, Duration.ofMillis(100),
                Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        if (controller != null) controller.close();
    }

    /** Uma pasta de saída com o checkpoint real do cenário {@code name}. */
    Path outputWith(String name) throws Exception {
        Path o = Files.createDirectories(tmp.resolve("o-" + name).resolve("execucao"));
        Files.copy(ProtocolRecoveryTest.resource(name + "-execucao/checkpoint.json"), o.resolve("checkpoint.json"));
        return o.getParent();
    }

    @Test
    @DisplayName("a2 REAL (cancelamento): 7 SUCCEEDED + 1 IN_PROGRESS conferidos; 7 entregues; texto diz parcial, desconhecido e sem consolidação")
    void a2Real() throws Exception {
        PartialProgress p = CheckpointProgress.read(outputWith("a2"));
        assertTrue(p.available(), String.valueOf(p.problem()));
        assertEquals(Map.of("SUCCEEDED", 7L, "IN_PROGRESS", 1L), p.transactions());
        assertEquals(7, p.delivered());
        assertEquals("7 concluídas · 1 interrompidas em andamento (desfecho desconhecido) · 7 entregues à saída. Planilha sem confirmação de consolidação.",
                ExecutionSetup.progressLabel(p));
        PartialProgress a5 = CheckpointProgress.read(outputWith("a5"));
        assertEquals(Map.of("SUCCEEDED", 11L, "FAILED_TECHNICAL", 1L), a5.transactions());
        assertEquals(12, a5.delivered());
    }

    @Test
    @DisplayName("checkpoint ausente, truncado, alterado sem refazer a integridade, de outra versão → indisponível com o motivo (nunca 'nada foi feito')")
    void naoConferivel() throws Exception {
        Path o = outputWith("a2");
        Path cp = CheckpointProgress.file(o);
        String real = Files.readString(cp, StandardCharsets.UTF_8);
        Files.writeString(cp, real.replaceFirst("\"IN_PROGRESS\"", "\"SUCCEEDED\""), StandardCharsets.UTF_8);
        assertEquals(CheckpointProgress.INTEGRITY, CheckpointProgress.read(o).problem(), "status trocado sem refazer a integridade");
        Files.writeString(cp, real.substring(0, real.length() / 2), StandardCharsets.UTF_8);
        assertEquals(CheckpointProgress.INVALID, CheckpointProgress.read(o).problem(), "truncado");
        Files.writeString(cp, "{\"body\": {}, \"integrity\": \"sha256:" + "0".repeat(64) + "\", \"extra\": 1}", StandardCharsets.UTF_8);
        assertEquals(CheckpointProgress.INVALID, CheckpointProgress.read(o).problem(), "fora do formato");
        Files.delete(cp);
        PartialProgress missing = CheckpointProgress.read(o);
        assertFalse(missing.available());
        assertEquals(CheckpointProgress.MISSING, missing.problem());
        assertTrue(ExecutionSetup.progressLabel(missing).contains("não significa que nada foi feito"));
        assertEquals(CheckpointProgress.NO_OUTPUT, CheckpointProgress.read(null).problem());
    }

    @Test
    @DisplayName("JSON canônico igual ao do Python: checkpoints gravados pelo CheckpointStore REAL com textos difíceis conferem; um byte a mais não")
    void canonicoIgualAoPython() throws Exception {
        Assumptions.assumeTrue(python != null, "sem Python neste ambiente");
        Path o = Files.createDirectories(tmp.resolve("py").resolve("execucao"));
        String script = String.join("\n",
                "import sys",
                "from pathlib import Path",
                "from nekkora_rpa.core.checkpoints import CheckpointRecord, CheckpointStore",
                "from nekkora_rpa.core.contracts import DeliveryStatus, TransactionStatus",
                "s = CheckpointStore(Path(sys.argv[1]), 'ação \"aspas\" \\\\ barra\\n\\t\\x01 ü € 𝄞 \\x7f')",
                "s.put('tx-00000000000000000001', CheckpointRecord(status=TransactionStatus.SUCCEEDED, delivery=DeliveryStatus.COMMITTED, attempts=1,"
                        + " detail='çã/\\u2028'))",
                "s.put('tx-00000000000000000002', CheckpointRecord(status=TransactionStatus.OUTCOME_UNKNOWN, side_effects=3, attempts=2))",
                "");
        Path py = tmp.resolve("gera.py");
        Files.writeString(py, script, StandardCharsets.UTF_8);
        Process p = new ProcessBuilder(python.toString(), "-I", "-X", "utf8", py.toString(), o.resolve("checkpoint.json").toString()).redirectErrorStream(true)
                .start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), log);
        PartialProgress pp = CheckpointProgress.read(o.getParent());
        assertTrue(pp.available(), "a integridade calculada pelo Python confere no Agent: " + pp.problem());
        assertEquals(Map.of("SUCCEEDED", 1L, "OUTCOME_UNKNOWN", 1L), pp.transactions());
        assertEquals(1, pp.delivered());
        Path cp = o.resolve("checkpoint.json");
        Files.writeString(cp, Files.readString(cp, StandardCharsets.UTF_8).replace("\"attempts\": 2", "\"attempts\": 3"), StandardCharsets.UTF_8);
        assertEquals(CheckpointProgress.INTEGRITY, CheckpointProgress.read(o.getParent()).problem());
    }

    @Test
    @DisplayName("reabertura sem resultado final (a5 sem summary.json): OUTCOME_UNKNOWN continua, com o progresso parcial conferido ao lado")
    void reconcileSemResultado() throws Exception {
        ProtocolRecoveryTest helper = new ProtocolRecoveryTest();
        helper.tmp = tmp;
        helper.setUp();
        try {
            ExecutionId id = helper.crashedRun(exec -> ProtocolRecoveryTest.delete(exec.resolve("summary.json")));
            ExecutionRecord r = helper.reconciled(id);
            assertEquals(ExecutionState.OUTCOME_UNKNOWN, r.state(), "o progresso não muda o desfecho");
            assertEquals(Map.of(), r.counts(), "sem contagens de resultado final");
            assertTrue(r.progress() != null && r.progress().available());
            assertEquals(Map.of("SUCCEEDED", 11L, "FAILED_TECHNICAL", 1L), r.progress().transactions());
            assertTrue(helper.events(id).contains("\"event\": \"PARTIAL_PROGRESS\", \"available\": true, \"transactions\": 12, \"delivered\": 12"),
                    helper.events(id));
        } finally {
            helper.tearDown();
        }
    }

    @Test
    @DisplayName("cancelamento REAL no meio (processo + CheckpointStore real): CANCELLED, contagens finais vazias, progresso parcial conferido")
    void cancelamentoReal() throws Exception {
        Assumptions.assumeTrue(python != null, "sem Python neste ambiente");
        ExecutionPackage p = ExecutionPackage.inspect(ObsStudioPackage.create(tmp.resolve("pkg"), "{\"mode\": \"slow\", \"items\": 40, \"delay_ms\": 150, "
                + "\"checkpoint\": true}"));
        approvals.approve(p, Clock.systemUTC().instant());
        ExecutionController.Snapshot s = controller.start(new ExecutionController.Request(p.dir(), p.fingerprint(), python, null, out, Duration.ofMinutes(3)));
        Path cp = CheckpointProgress.file(out.resolve(s.record().id().value()));
        waitFor(() -> CheckpointProgress.read(cp.getParent().getParent()).transactions().getOrDefault("SUCCEEDED", 0L) >= 3, Duration.ofSeconds(60),
                "três transações concluídas");
        assertTrue(controller.cancel());
        waitFor(() -> !controller.running(), Duration.ofSeconds(30), "cancelamento confirmado");
        ExecutionRecord r = runs.record(s.record().id()).orElseThrow();
        assertEquals(ExecutionState.CANCELLED, r.state());
        assertEquals(ErrorCode.EXEC_CANCELLED, r.code());
        assertEquals(Map.of(), r.counts(), "nenhum resultado final foi publicado");
        assertTrue(r.progress().available(), String.valueOf(r.progress().problem()));
        long ok = r.progress().transactions().getOrDefault("SUCCEEDED", 0L);
        assertTrue(ok >= 3 && ok < 40, "parcial: " + r.progress().transactions());
        assertTrue(r.progress().transactions().getOrDefault("IN_PROGRESS", 0L) <= 1);
        assertEquals(ok, r.progress().delivered());
        assertTrue(r.progress().total() < 40, "não é conclusão");
        assertFalse(Files.exists(cp.resolveSibling("summary.json")), "o processo não chegou ao fim");
        List<String> ev = runs.events(r.id());
        assertTrue(ev.stream().anyMatch(l -> l.contains("PARTIAL_PROGRESS")), ev.toString());
        assertTrue(ev.get(ev.size() - 1).contains("\"CANCELLED\""), ev.toString());
    }

    static void waitFor(BooleanSupplier c, Duration max, String what) throws Exception {
        long deadline = System.nanoTime() + max.toNanos();
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("tempo esgotado: " + what);
            Thread.sleep(50);
        }
    }
}
