package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionRecord.CancelOutcome;
import com.nekkora.agent.exec.ExecutionRecord.CancelSource;
import com.nekkora.agent.exec.ExecutionRecord.Cancellation;
import com.nekkora.agent.exec.ExecutionRecord.Origin;
import com.nekkora.agent.exec.ExecutionRecord.PartialProgress;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code estado.json} 1.1.0 (aditiva): origem, cancelamento pedido × confirmado, desfecho recuperado do protocolo e
 * progresso parcial. O leitor continua lendo os registros 1.0.0 gravados pelo Agent anterior (três registros REAIS da
 * compat-001) e recusa o que não conhece em vez de inventar um valor.
 */
class ExecutionRecordFormatTest {
    @TempDir
    Path tmp;

    static ExecutionRecord base() {
        Instant t = Instant.parse("2026-10-28T12:41:46.033Z");
        return new ExecutionRecord(new ExecutionId("ex-78bf12bed1f1b224278a3e1ada567f97"), ExecutionPackage.Kind.STUDIO_PYTHON, "pacote 5664f1a56556",
                "sha256:" + "5".repeat(64), ExecutionState.READY, null, t, null, null, new ProcessOwner(22188, 1793191306984L, "sha256:" + "f".repeat(64)),
                null, Map.of(), 0, 0, false, 0);
    }

    static ExecutionRecord read(String json) throws Exception {
        Path f = Files.createTempFile("estado", ".json");
        try {
            Files.writeString(f, json, StandardCharsets.UTF_8);
            return RunStore.load(f).orElse(null);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    @DisplayName("1.1.0: todos os campos novos fazem ida e volta; o arquivo declara a versão 1.1.0")
    void idaEVolta() throws Exception {
        Instant t = Instant.parse("2026-10-28T12:41:58.261Z");
        ExecutionRecord r = base().withOrigin(Origin.LOCAL_UI).moveTo(ExecutionState.STARTING, null, t).moveTo(ExecutionState.RUNNING, null, t)
                .withCancellation(new Cancellation(t, CancelSource.LOCAL_UI, CancelOutcome.CONFIRMED))
                .withProgress(new PartialProgress(true, Map.of("SUCCEEDED", 7L, "IN_PROGRESS", 1L), 7, null))
                .moveTo(ExecutionState.CANCELLED, ErrorCode.EXEC_CANCELLED, t);
        String json = RunStore.json(r);
        assertTrue(json.contains("\"version\": \"1.1.0\""), json);
        ExecutionRecord back = read(json);
        assertEquals(r, back);
        assertEquals(json, RunStore.json(back), "forma canônica estável");
        ExecutionRecord rec = base().withOrigin(Origin.LOCAL_UI).moveTo(ExecutionState.STARTING, null, t).moveTo(ExecutionState.RUNNING, null, t)
                .moveTo(ExecutionState.FAILED, ErrorCode.EXEC_REPORTED_FAILURE, t).recoveredFromProtocol()
                .withProgress(PartialProgress.unavailable("CHECKPOINT_INVALID"));
        assertEquals(rec, read(RunStore.json(rec)));
    }

    @Test
    @DisplayName("1.0.0 REAL (compat-001: sucesso, cancelamento, Agent derrubado): lido sem perder nada; origem UNKNOWN, sem cancelamento/progresso")
    void leRegistrosReais100() throws Exception {
        for (String name : List.of("a1-sucesso", "a2-cancelamento", "a5-agent-derrubado")) {
            String json;
            try (InputStream in = getClass().getResourceAsStream("real/estado-1.0.0-" + name + ".json")) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertTrue(json.contains("\"version\": \"1.0.0\""));
            ExecutionRecord r = read(json);
            assertTrue(r != null, name);
            assertEquals(Origin.UNKNOWN, r.origin(), name);
            assertNull(r.cancellation(), name);
            assertNull(r.progress(), name);
            assertFalse(r.recovered(), name);
            assertTrue(r.state().terminal(), name);
            // regravado, vira 1.1.0 com os mesmos campos 1.0.0
            ExecutionRecord again = read(RunStore.json(r));
            assertEquals(r, again, name);
        }
        ExecutionRecord a2 = read(new String(getClass().getResourceAsStream("real/estado-1.0.0-a2-cancelamento.json").readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(ExecutionState.CANCELLED, a2.state());
        assertEquals(ErrorCode.EXEC_CANCELLED, a2.code());
        assertEquals(Map.of(), a2.counts(), "o registro antigo realmente não tinha progresso (achado A03)");
    }

    @Test
    @DisplayName("versão desconhecida ou valor fora do enum: registro ilegível (nunca um valor inventado)")
    void recusaODesconhecido() throws Exception {
        Instant t = Instant.parse("2026-10-28T12:41:58.261Z");
        String ok = RunStore.json(base().withOrigin(Origin.LOCAL_UI).withCancellation(new Cancellation(t, CancelSource.LOCAL_UI, CancelOutcome.PENDING)));
        assertTrue(read(ok) != null);
        assertNull(read(ok.replace("\"1.1.0\"", "\"1.2.0\"")), "versão maior que a conhecida");
        assertNull(read(ok.replace("\"LOCAL_UI\"}", "\"PLATFORM\"}")), "origem fora do enum");
        assertNull(read(ok.replace("\"PENDING\"", "\"DONE\"")), "situação de cancelamento fora do enum");
        Optional<ExecutionRecord> none = RunStore.load(tmp.resolve("nao-existe.json"));
        assertTrue(none.isEmpty());
    }

    @Test
    @DisplayName("progresso parcial: só contagens por estado (nomes do contrato) e entregues; nada de identificador de transação ou valor")
    void progressoSoContagens() throws Exception {
        PartialProgress p = new PartialProgress(true, Map.of("SUCCEEDED", 7L, "IN_PROGRESS", 1L), 7, null);
        assertEquals(8, p.total());
        String json = RunStore.json(base().withProgress(p));
        assertTrue(json.contains("\"progress\": {\"available\": true, \"transactions\": {\"IN_PROGRESS\": 1, \"SUCCEEDED\": 7}, \"delivered\": 7, \"problem\": null}"),
                json);
        assertFalse(json.contains("tx-"), "nenhum identificador de transação");
    }
}
