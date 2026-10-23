package com.nekkora.agent.obs;

import com.nekkora.agent.exec.JsonReader;
import com.nekkora.agent.obs.ActivityEvent.Category;
import com.nekkora.agent.obs.ActivityEvent.Level;
import com.nekkora.agent.obs.ActivityEvent.Source;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Normalização do {@code eventos.jsonl} que o supervisor do Agent já grava (sem mudar esse arquivo nem o seu
 * significado): ciclo de vida, sessão bloqueada, suspensão, reconciliação/adoção, encerramento e desfecho. Lista fechada
 * de nomes e de campos ({@code code}, {@code exit}, {@code output_bytes}, {@code gap_ms}, {@code processes},
 * {@code protocol}); o PID, a sessão e o tipo do pacote ficam de fora (o registro da execução já os tem).
 */
final class AgentEvents {
    private AgentEvents() {}

    static final int MAX_LINE = 2048;
    /** Marcador que o {@code RunStore} grava como última linha quando o limite de 500 eventos é atingido. */
    static final String LIMIT_MARKER = com.nekkora.agent.exec.RunStore.LIMIT_MARKER;

    private static final Set<String> KNOWN = Set.of("STARTING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "BLOCKED", "OUTCOME_UNKNOWN",
            "SUSPENSION_DETECTED", "SESSION_LOCKED", "SESSION_UNLOCKED", "TERMINATED", "RECONCILE", "ADOPTED", LIMIT_MARKER);

    record Mapped(ActivityEvent event, String rejected, boolean limit, int unknown) {}

    static Mapped map(String line, String agentExecutionId, Instant observed, long position) {
        Map<String, Object> m;
        try {
            m = JsonReader.object(line);
        } catch (JsonReader.Invalid | RuntimeException e) {
            return new Mapped(null, "MALFORMED_JSON", false, 0);
        }
        Instant at;
        try {
            at = Instant.parse((String) m.get("at"));
        } catch (RuntimeException e) {
            return new Mapped(null, "FIELD_INVALID", false, 0);
        }
        if (!(m.get("event") instanceof String name) || !ActivityEvent.CODE.matcher(name).matches()) return new Mapped(null, "FIELD_INVALID", false, 0);
        int unknown = KNOWN.contains(name) ? 0 : 1;
        if (LIMIT_MARKER.equals(name)) {
            return new Mapped(ActivityEvent.of(agentExecutionId, observed, Source.AGENT, "EVENT_GAP", Level.WARNING, Category.TELEMETRY).occurred(at)
                    .error(com.nekkora.agent.core.ErrorCode.OBS_AGENT_EVENTS_LIMIT.code()).position(position).build(), null, true, 0);
        }
        Level level = switch (name) {
            case "FAILED", "TIMED_OUT" -> Level.ERROR;
            case "BLOCKED", "CANCELLED", "OUTCOME_UNKNOWN", "SUSPENSION_DETECTED", "SESSION_LOCKED", "TERMINATED", "RECONCILE" -> Level.WARNING;
            default -> Level.INFO;
        };
        Category category = "FAILED".equals(name) || "TIMED_OUT".equals(name) ? Category.FAILURE : Category.LIFECYCLE;
        ActivityEvent.Builder b = ActivityEvent.of(agentExecutionId, observed, Source.AGENT, name, level, category).occurred(at).position(position);
        if (Set.of("STARTING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "BLOCKED", "OUTCOME_UNKNOWN").contains(name)) b.status(name);
        if (m.get("state") instanceof String s && ActivityEvent.STATUS.matcher(s).matches()) b.status(s);
        if (m.get("code") instanceof String c && c.matches("AGT-[A-Z]{2,5}-[0-9]{3}")) b.error(c);
        number(m, "exit", b, "exit_code");
        number(m, "output_bytes", b, "output_bytes");
        number(m, "gap_ms", b, "gap_ms");
        number(m, "processes", b, "processes");
        if (m.get("protocol") instanceof Boolean p) b.count("protocol_result", p ? 1 : 0);
        for (String k : m.keySet()) {
            if (!Set.of("at", "event", "state", "code", "exit", "output_bytes", "gap_ms", "processes", "protocol", "pid", "session", "kind", "mode").contains(k)) unknown++;
        }
        return new Mapped(b.build(), null, false, unknown);
    }

    private static void number(Map<String, Object> m, String k, ActivityEvent.Builder b, String as) {
        if (m.get(k) instanceof Long n && n >= 0 && n < ActivityEvent.MAX_NUMBER) b.count(as, n);
    }
}
