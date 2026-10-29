package com.nekkora.agent.obs;

import com.nekkora.agent.exec.JsonReader;
import com.nekkora.agent.storage.StrictJson;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JSON do envelope {@code nekkora-agent/observability} (uma linha por evento). A leitura do histórico volta a validar
 * cada campo (o arquivo pode ter sido corrompido ou alterado): versão {@code 1.0.x} exige exatamente os campos
 * conhecidos; {@code 1.y} com {@code y > 0} é aceita ignorando campos novos; outra versão maior é recusada.
 */
public final class ActivityCodec {
    private ActivityCodec() {}

    static final int MAX_LINE = 4096;
    private static final Set<String> FIELDS = Set.of("schema", "version", "agent_execution_id", "sequence", "timestamp_utc", "observed_utc", "source",
            "event_code", "severity", "category", "phase", "transaction_ref", "step_id", "studio_step_id", "action", "attempt", "elapsed_ms", "status",
            "error_code", "counts", "correlation");

    /** Uma linha JSON (sem quebra de linha no fim). */
    public static String encode(ActivityEvent e) {
        StringBuilder b = new StringBuilder(256).append('{');
        text(b, "schema", ActivityEvent.SCHEMA);
        text(b, "version", ActivityEvent.VERSION);
        text(b, "agent_execution_id", e.agentExecutionId());
        num(b, "sequence", e.sequence());
        if (e.occurred() != null) text(b, "timestamp_utc", e.occurred().toString());
        text(b, "observed_utc", e.observed().toString());
        text(b, "source", e.source().name());
        text(b, "event_code", e.code());
        text(b, "severity", e.severity().name());
        text(b, "category", e.category().name());
        text(b, "phase", e.phase());
        text(b, "transaction_ref", e.transactionRef());
        text(b, "step_id", e.stepId());
        text(b, "studio_step_id", e.studioStepId());
        text(b, "action", e.action());
        num(b, "attempt", e.attempt());
        num(b, "elapsed_ms", e.elapsedMs());
        text(b, "status", e.status());
        text(b, "error_code", e.errorCode());
        if (!e.counts().isEmpty()) {
            b.append(",\"counts\":{");
            int n = 0;
            for (Map.Entry<String, Long> c : e.counts().entrySet()) b.append(n++ == 0 ? "" : ",").append(StrictJson.quote(c.getKey())).append(':').append(c.getValue());
            b.append('}');
        }
        if (e.frameworkExecutionId() != null || e.sourcePosition() != null) {
            b.append(",\"correlation\":{");
            boolean first = true;
            if (e.frameworkExecutionId() != null) {
                b.append("\"framework_execution_id\":").append(StrictJson.quote(e.frameworkExecutionId()));
                first = false;
            }
            if (e.sourcePosition() != null) b.append(first ? "" : ",").append("\"source_position\":").append(e.sourcePosition());
            b.append('}');
        }
        return b.append('}').toString();
    }

    private static void text(StringBuilder b, String k, String v) {
        if (v == null) return;
        b.append(b.length() > 1 ? "," : "").append(StrictJson.quote(k)).append(':').append(StrictJson.quote(v));
    }

    private static void num(StringBuilder b, String k, Number v) {
        if (v == null) return;
        b.append(b.length() > 1 ? "," : "").append(StrictJson.quote(k)).append(':').append(v);
    }

    /** Lê uma linha do histórico; vazio se ela não for um envelope válido (nada do conteúdo é ecoado). */
    public static Optional<ActivityEvent> decode(String line) {
        if (line == null || line.length() > MAX_LINE) return Optional.empty();
        try {
            Map<String, Object> m = JsonReader.object(line);
            if (!ActivityEvent.SCHEMA.equals(m.get("schema")) || !(m.get("version") instanceof String v) || !v.matches("1\\.[0-9]{1,3}\\.[0-9]{1,3}")) {
                return Optional.empty();
            }
            boolean exact = v.startsWith("1.0.");
            if (exact && !FIELDS.containsAll(m.keySet())) return Optional.empty();
            Map<String, Long> counts = new LinkedHashMap<>();
            Map<String, Object> c = JsonReader.obj(m, "counts");
            if (m.containsKey("counts") && c == null) return Optional.empty();
            if (c != null) for (Map.Entry<String, Object> x : c.entrySet()) {
                if (!(x.getValue() instanceof Long n)) return Optional.empty();
                counts.put(x.getKey(), n);
            }
            Map<String, Object> corr = JsonReader.obj(m, "correlation");
            if (m.containsKey("correlation") && corr == null) return Optional.empty();
            if (corr != null && exact && !Set.of("framework_execution_id", "source_position").containsAll(corr.keySet())) return Optional.empty();
            String occurred = str(m, "timestamp_utc");
            Long attempt = lng(m, "attempt");
            return Optional.of(new ActivityEvent(str(m, "agent_execution_id"), req(lng(m, "sequence")), occurred == null ? null : Instant.parse(occurred),
                    Instant.parse(str(m, "observed_utc")), ActivityEvent.Source.valueOf(str(m, "source")), str(m, "event_code"),
                    ActivityEvent.Level.valueOf(str(m, "severity")), ActivityEvent.Category.valueOf(str(m, "category")), str(m, "phase"),
                    str(m, "transaction_ref"), str(m, "step_id"), str(m, "studio_step_id"), str(m, "action"),
                    attempt == null ? null : Math.toIntExact(attempt), lng(m, "elapsed_ms"), str(m, "status"), str(m, "error_code"), counts,
                    corr == null ? null : str(corr, "framework_execution_id"), corr == null ? null : lng(corr, "source_position")));
        } catch (JsonReader.Invalid | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (!(v instanceof String s)) throw new IllegalArgumentException(k);
        return s;
    }

    private static Long lng(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (!(v instanceof Long n)) throw new IllegalArgumentException(k);
        return n;
    }

    private static long req(Long v) {
        if (v == null) throw new IllegalArgumentException("sequence");
        return v;
    }
}
