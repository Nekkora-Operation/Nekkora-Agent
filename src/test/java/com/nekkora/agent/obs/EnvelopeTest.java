package com.nekkora.agent.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.obs.ActivityEvent.Category;
import com.nekkora.agent.obs.ActivityEvent.Level;
import com.nekkora.agent.obs.ActivityEvent.Source;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** OBS-03/OBS-04: envelope canônico, codec estrito e normalização por lista de permissão dos eventos do Framework. */
class EnvelopeTest {
    static final String ID = ExecutionId.random().value();
    static final Instant T0 = Instant.parse("2026-10-22T12:00:00Z");
    static final FrameworkEvents.Context CTX = new FrameworkEvents.Context(ID, T0.minusSeconds(120), StepCatalog.EMPTY);
    static final String FW = "ex-348ea538b07747d2812b";

    @Test
    void envelopeRoundTripsAndOmitsAbsentFields() {
        ActivityEvent e = ActivityEvent.of(ID, T0, Source.FRAMEWORK, "ACTION_COMPLETED", Level.INFO, Category.ACTIVITY).occurred(T0.minusMillis(5))
                .phase("PROCESS").transaction("t-0123456789abcdef").step("search.press_enter").action("press").attempt(1).elapsed(12L).status("OK")
                .framework(FW).position(812L).count("items", 2).build().withSequence(7);
        String line = ActivityCodec.encode(e);
        assertTrue(line.startsWith("{\"schema\":\"nekkora-agent/observability\",\"version\":\"1.0.0\""), line);
        assertFalse(line.contains("null"), "campos ausentes são omitidos");
        assertEquals(e, ActivityCodec.decode(line).orElseThrow());
        ActivityEvent min = ActivityEvent.of(ID, T0, Source.COLLECTOR, "COLLECTION_STARTED", Level.INFO, Category.TELEMETRY).build().withSequence(1);
        String m = ActivityCodec.encode(min);
        assertFalse(m.contains("step_id") || m.contains("correlation") || m.contains("counts") || m.contains("timestamp_utc"), m);
        assertEquals(min, ActivityCodec.decode(m).orElseThrow());
    }

    @Test
    void codecRejectsUnknownFieldsOrMajorVersionButAcceptsNewerMinor() {
        String ok = ActivityCodec.encode(ActivityEvent.of(ID, T0, Source.AGENT, "RUNNING", Level.INFO, Category.LIFECYCLE).build().withSequence(2));
        assertTrue(ActivityCodec.decode(ok).isPresent());
        assertTrue(ActivityCodec.decode(ok.replace("}", ",\"message\":\"x\"}")).isEmpty(), "1.0.x: campo fora da lista é recusado");
        assertTrue(ActivityCodec.decode(ok.replace("\"1.0.0\"", "\"1.1.0\"").replace("}", ",\"novo\":1}")).isPresent(), "1.1: campo novo ignorado");
        assertTrue(ActivityCodec.decode(ok.replace("\"1.0.0\"", "\"2.0.0\"")).isEmpty(), "versão maior desconhecida");
        assertTrue(ActivityCodec.decode(ok.replace("\"RUNNING\"", "\"running now\"")).isEmpty(), "código fora do formato");
        assertTrue(ActivityCodec.decode(ok.replace("\"sequence\":2", "\"sequence\":\"2\"")).isEmpty(), "tipo errado");
        assertTrue(ActivityCodec.decode("{".repeat(40) + "}".repeat(40)).isEmpty());
        assertTrue(ActivityCodec.decode("x".repeat(5000)).isEmpty(), "linha acima do limite");
    }

    @Test
    void envelopeRefusesValuesThatAreNotIdentifiers() {
        for (String step : List.of("maria@cliente.example", "C:\\Users\\x", "https://a.b/c?token=1", "abc 123", "a12345678909", "../../etc")) {
            assertThrows(IllegalArgumentException.class, () -> ActivityEvent.of(ID, T0, Source.FRAMEWORK, "ACTION_STARTED", Level.INFO, Category.ACTIVITY)
                    .step(step).build(), step);
        }
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ActivityEvent.of(ID, T0, Source.FRAMEWORK, "ACTION_STARTED", Level.INFO, Category.ACTIVITY).status("CPF12345678909").build());
        assertEquals("status", ex.getMessage(), "a mensagem traz só o nome do campo, nunca o valor");
    }

    @Test
    void realG1FrameworkEventsAreNormalizedByAllowList() throws Exception {
        List<String> lines = ObsFixtures.g1Events();
        FrameworkEvents.Context ctx = new FrameworkEvents.Context(ID, Instant.parse("2026-10-22T03:30:00Z"), StepCatalog.EMPTY);
        int excluded = 0;
        long pos = 0;
        for (String l : lines) {
            pos += l.length() + 1;
            FrameworkEvents.Mapped m = FrameworkEvents.map(l, ctx, T0, pos);
            assertNotNull(m.event(), l);
            assertEquals(0, m.unknown(), "o 1.2.0 real não tem campo desconhecido");
            assertFalse(m.unknownCode());
            excluded += m.excluded();
            String enc = ActivityCodec.encode(m.event().withSequence(1));
            for (String banned : List.of("detail", "key", "cause_type", "selector_registry_revision", "strategy_index", "delay_ms", "framework_version",
                    "detached_during_check", "process.search", "tx-")) {
                assertFalse(enc.contains(banned), banned + " em " + enc);
            }
            assertEquals(FW, m.event().frameworkExecutionId());
            assertEquals(pos, m.event().sourcePosition());
        }
        assertTrue(excluded > 200, "detail/key/selector_registry_revision/strategy_index/framework_version descartados e contados: " + excluded);
        FrameworkEvents.Mapped press = lines.stream().map(l -> FrameworkEvents.map(l, ctx, T0, 1)).filter(m -> "search.press_enter".equals(m.event().stepId())
                && "ACTION_COMPLETED".equals(m.event().code()) && "press".equals(m.event().action())).findFirst().orElseThrow();
        assertEquals(2, press.event().attempt(), "no run real a tecla foi repetida depois de TARGET_DETACHED");
        assertEquals("PROCESS", press.event().phase());
        assertEquals("OK", press.event().status());
        assertNotNull(press.event().elapsedMs());
        assertTrue(press.event().transactionRef().matches("t-[0-9a-f]{16}"));
        FrameworkEvents.Mapped end = FrameworkEvents.map(lines.get(lines.size() - 1), ctx, T0, 1);
        assertEquals("ENGINE_COMPLETED", end.event().code());
        assertEquals(Map.of("items", 2L), end.event().counts());
    }

    @Test
    void transactionTokenIsOpaqueAndScopedToTheLocalExecution() {
        String a = FrameworkEvents.transactionRef(ID, "tx-435482fe991caadcf79b");
        assertEquals(a, FrameworkEvents.transactionRef(ID, "tx-435482fe991caadcf79b"), "estável na execução");
        assertFalse(a.contains("435482"));
        assertFalse(a.equals(FrameworkEvents.transactionRef(ExecutionId.random().value(), "tx-435482fe991caadcf79b")), "não liga execuções diferentes");
    }

    @Test
    void sensitiveAndArbitraryFieldsAreDroppedAndCounted() {
        String line = ObsFixtures.fw(FW, T0, "ACTION_FAILED", "phase", "PROCESS", "step_id", "maria.sigilosa@cliente.example", "action", "fill", "attempt", 2,
                "error_code", "ACTION_TIMEOUT", "detail", "tok_SIGILOSO_OBS_9Z", "message", "Senha#Sigilosa9Z", "value", "VALOR-EXCEL-9Z-4471",
                "cause_type", "Traceback (most recent call last)", "outcome", "123.456.789-09", "url", "https://cliente.example/api?token=abc9Z");
        FrameworkEvents.Mapped m = FrameworkEvents.map(line, CTX, T0, 10);
        assertNotNull(m.event());
        String enc = ActivityCodec.encode(m.event().withSequence(1));
        assertNull(ObsFixtures.leak(enc), enc);
        assertNull(m.event().stepId(), "step_id com e-mail é descartado");
        assertNull(m.event().status(), "outcome com CPF é descartado");
        assertEquals("ACTION_TIMEOUT", m.event().errorCode());
        assertEquals(Category.FAILURE, m.event().category());
        assertEquals(Level.ERROR, m.event().severity());
        assertEquals(3, m.excluded(), "detail, cause_type e framework_version");
        assertEquals(5, m.unknown(), "message, value, url + step_id e outcome fora do formato");
    }

    @Test
    void invalidLinesAreRejectedWithClosedReasons() {
        assertEquals("MALFORMED_JSON", FrameworkEvents.map("{\"schema\":", CTX, T0, 1).rejected());
        assertEquals("SCHEMA_MISMATCH", FrameworkEvents.map("{\"schema\":\"outro\"}", CTX, T0, 1).rejected());
        assertEquals("SCHEMA_UNSUPPORTED", FrameworkEvents.map(ObsFixtures.fw(FW, T0, "ACTION_STARTED").replace("1.2.0", "2.0.0"), CTX, T0, 1).rejected());
        assertEquals("STALE_EVENT", FrameworkEvents.map(ObsFixtures.fw(FW, T0.minusSeconds(3600), "ACTION_STARTED"), CTX, T0, 1).rejected());
        assertEquals("CORRELATION_INVALID", FrameworkEvents.map(ObsFixtures.fw("maria@x", T0, "ACTION_STARTED"), CTX, T0, 1).rejected());
        assertEquals("FIELD_INVALID", FrameworkEvents.map(ObsFixtures.fw(FW, T0, "não é código"), CTX, T0, 1).rejected());
        assertEquals("NESTING_TOO_DEEP", FrameworkEvents.map(ObsFixtures.fw(FW, T0, "ACTION_STARTED", "x", new ObsFixtures.RawJson("{\"a\":{\"b\":[1]}}")),
                CTX, T0, 1).rejected());
        FrameworkEvents.Mapped future = FrameworkEvents.map(ObsFixtures.fw(FW, T0, "NEW_CODE_FROM_1_3"), CTX, T0, 1);
        assertTrue(future.unknownCode(), "código novo (versão menor nova) é mantido e contado");
        FrameworkEvents.Mapped biz = FrameworkEvents.map(ObsFixtures.fw(FW, T0, "TRANSACTION_FAILED", "outcome", "BUSINESS_REJECTED",
                "error_code", "BUSINESS_RULE_REJECTED"), CTX, T0, 1);
        assertEquals(Category.BUSINESS, biz.event().category(), "rejeição de negócio separada de falha técnica");
    }

    @Test
    void agentSupervisorEventsMapToLifecycleWithoutPidOrSession() {
        AgentEvents.Mapped m = AgentEvents.map("{\"at\": \"2026-10-22T03:35:24.681Z\", \"event\": \"RUNNING\", \"pid\": 36336}", ID, T0, 70);
        assertEquals("RUNNING", m.event().code());
        assertEquals(Category.LIFECYCLE, m.event().category());
        assertFalse(ActivityCodec.encode(m.event().withSequence(1)).contains("36336"));
        AgentEvents.Mapped end = AgentEvents.map("{\"at\": \"2026-10-22T03:35:28.605Z\", \"event\": \"FAILED\", \"exit\": 3, \"protocol\": true, "
                + "\"output_bytes\": 198, \"code\": \"AGT-EXE-022\"}", ID, T0, 200);
        assertEquals(Level.ERROR, end.event().severity());
        assertEquals("AGT-EXE-022", end.event().errorCode());
        assertEquals(Map.of("exit_code", 3L, "output_bytes", 198L, "protocol_result", 1L), end.event().counts());
        AgentEvents.Mapped limit = AgentEvents.map("{\"at\": \"2026-10-22T03:35:28.605Z\", \"event\": \"EVENTS_LIMIT_REACHED\"}", ID, T0, 300);
        assertTrue(limit.limit());
        assertEquals("EVENT_GAP", limit.event().code());
        assertEquals("AGT-OBS-006", limit.event().errorCode());
    }
}
