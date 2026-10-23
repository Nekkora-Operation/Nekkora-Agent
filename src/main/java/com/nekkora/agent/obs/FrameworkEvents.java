package com.nekkora.agent.obs;

import com.nekkora.agent.exec.JsonReader;
import com.nekkora.agent.obs.ActivityEvent.Category;
import com.nekkora.agent.obs.ActivityEvent.Level;
import com.nekkora.agent.obs.ActivityEvent.Source;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalização dos eventos do Nekkora RPA Framework ({@code nekkora-rpa/events} 1.x, publicados pelo pacote em
 * {@code <saída>/<execução>/execucao/events.jsonl}) para o envelope do Agent, por LISTA DE PERMISSÃO:
 * <ul>
 *   <li>mantidos e validados: {@code timestamp_utc}, {@code execution_id} (só como correlação), {@code event_code},
 *       {@code phase}, {@code transaction_id} (re-pseudonimizado), {@code step_id}, {@code action}, {@code attempt},
 *       {@code elapsed_ms}, {@code outcome} → {@code status}, {@code error_code}, {@code count};</li>
 *   <li>descartados mesmo quando válidos (contados): {@code detail}, {@code cause_type}, {@code key},
 *       {@code strategy_index}, {@code delay_ms}, {@code selector_registry_revision}, {@code framework_version};</li>
 *   <li>qualquer outro campo é desconhecido: descartado e contado (o NOME não é guardado — poderia ser um dado).</li>
 * </ul>
 * Versão maior diferente de 1 → recusa da linha ({@code SCHEMA_UNSUPPORTED}); campo mantido com formato errado →
 * campo descartado e contado. Nenhum valor recusado é ecoado.
 */
final class FrameworkEvents {
    private FrameworkEvents() {}

    static final String SCHEMA = "nekkora-rpa/events";
    static final int MAX_LINE = 8192;
    /** Profundidade máxima de aninhamento aceita numa linha (o evento do Framework é plano). */
    static final int MAX_NESTING = 2;

    private static final Set<String> KEPT = Set.of("schema", "version", "timestamp_utc", "execution_id", "event_code", "phase", "transaction_id",
            "step_id", "action", "attempt", "elapsed_ms", "outcome", "error_code", "count");
    private static final Set<String> EXCLUDED = Set.of("detail", "cause_type", "key", "strategy_index", "delay_ms", "selector_registry_revision",
            "framework_version");
    private static final Set<String> KNOWN = Set.of("ENGINE_STARTED", "ENGINE_COMPLETED", "PHASE_STARTED", "PHASE_COMPLETED", "PHASE_FAILED",
            "TRANSACTION_STARTED", "TRANSACTION_COMPLETED", "TRANSACTION_FAILED", "TRANSACTION_SKIPPED", "TRANSACTION_RETRY_SCHEDULED",
            "TRANSACTION_RETRY_DENIED", "RECONCILIATION_STARTED", "RECONCILIATION_COMPLETED", "TARGET_RESOLVED", "TARGET_REJECTED", "ACTION_STARTED",
            "ACTION_COMPLETED", "ACTION_FAILED", "RETRY_SCHEDULED", "RETRY_DENIED", "OUTCOME_UNKNOWN", "SINK_COMMITTED", "SINK_UNCERTAIN", "SINK_FAILED",
            "CHECKPOINT_SAVED", "CHECKPOINT_LOADED", "SECURITY_BLOCKED", "CLEANUP_COMPLETED", "CLEANUP_FAILED", "CANCELLED", "OUTPUT_COMMITTED",
            "OUTPUT_WITHHELD", "OUTPUT_FAILED");
    private static final Set<String> FAILURES = Set.of("PHASE_FAILED", "TRANSACTION_FAILED", "ACTION_FAILED", "SINK_FAILED", "SECURITY_BLOCKED",
            "CLEANUP_FAILED", "OUTPUT_FAILED");
    private static final Set<String> UNCERTAIN = Set.of("OUTCOME_UNKNOWN", "SINK_UNCERTAIN");
    private static final Set<String> WARNINGS = Set.of("RETRY_SCHEDULED", "RETRY_DENIED", "TRANSACTION_RETRY_SCHEDULED", "TRANSACTION_RETRY_DENIED",
            "TARGET_REJECTED", "TRANSACTION_SKIPPED", "OUTPUT_WITHHELD", "CANCELLED");
    private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}");

    /** Contexto da execução local: identidade, início (anterior a ele = evento velho) e catálogo de passos. */
    record Context(String agentExecutionId, Instant notBefore, StepCatalog steps) {}

    /**
     * Resultado de uma linha: um evento normalizado OU um motivo de recusa (código fechado); campos descartados e
     * desconhecidos são contados; {@code unknownCode} = código de evento fora do catálogo 1.2.0 (mantido, contado).
     */
    record Mapped(ActivityEvent event, String rejected, int excluded, int unknown, boolean unknownCode) {
        static Mapped reject(String why) {
            return new Mapped(null, why, 0, 0, false);
        }
    }

    static Mapped map(String line, Context ctx, Instant observed, long position) {
        if (nesting(line) > MAX_NESTING) return Mapped.reject("NESTING_TOO_DEEP");
        Map<String, Object> m;
        try {
            m = JsonReader.object(line);
        } catch (JsonReader.Invalid | RuntimeException e) {
            return Mapped.reject("MALFORMED_JSON");
        }
        if (!SCHEMA.equals(m.get("schema"))) return Mapped.reject("SCHEMA_MISMATCH");
        if (!(m.get("version") instanceof String v) || !v.matches("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) return Mapped.reject("SCHEMA_MISMATCH");
        if (!v.startsWith("1.")) return Mapped.reject("SCHEMA_UNSUPPORTED");
        if (!(m.get("timestamp_utc") instanceof String ts) || !TIMESTAMP.matcher(ts).matches()) return Mapped.reject("FIELD_INVALID");
        Instant occurred;
        try {
            occurred = Instant.parse(ts);
        } catch (DateTimeParseException e) {
            return Mapped.reject("FIELD_INVALID");
        }
        if (occurred.isBefore(ctx.notBefore())) return Mapped.reject("STALE_EVENT");
        if (!(m.get("execution_id") instanceof String fw) || !ActivityEvent.FW_EXECUTION.matcher(fw).matches()) return Mapped.reject("CORRELATION_INVALID");
        if (!(m.get("event_code") instanceof String code) || !ActivityEvent.CODE.matcher(code).matches()) return Mapped.reject("FIELD_INVALID");

        int excluded = 0;
        int unknown = 0;
        for (String k : m.keySet()) {
            if (EXCLUDED.contains(k)) excluded++;
            else if (!KEPT.contains(k)) unknown++;
        }
        String phase = matching(m, "phase", ActivityEvent.PHASE);
        // o pseudônimo do Framework tem dígitos por natureza; só o formato é conferido, porque ele é re-pseudonimizado
        String tx = m.get("transaction_id") instanceof String t && TOKEN.matcher(t).matches() ? t : null;
        String step = matching(m, "step_id", ActivityEvent.STEP);
        if (step != null && step.length() > 120) step = null;
        String action = matching(m, "action", ActivityEvent.ACTION);
        String status = matching(m, "outcome", ActivityEvent.STATUS);
        String error = matching(m, "error_code", ActivityEvent.CODE);
        Integer attempt = m.get("attempt") instanceof Long a && a >= 0 && a <= 10_000 ? Math.toIntExact(a) : null;
        Long elapsed = m.get("elapsed_ms") instanceof Long e && e >= 0 && e < ActivityEvent.MAX_NUMBER ? e : null;
        Long count = m.get("count") instanceof Long c && c >= 0 && c < ActivityEvent.MAX_NUMBER ? c : null;
        // campos mantidos que vieram fora do formato: descartados e contados como desconhecidos
        for (Object[] f : new Object[][] {{"phase", phase}, {"transaction_id", tx}, {"step_id", step}, {"action", action}, {"outcome", status},
                {"error_code", error}, {"attempt", attempt}, {"elapsed_ms", elapsed}, {"count", count}}) {
            if (m.containsKey((String) f[0]) && f[1] == null) unknown++;
        }

        boolean business = "BUSINESS_REJECTED".equals(status) || "BUSINESS_RULE_REJECTED".equals(error);
        Level level;
        Category category;
        if (business) {
            level = Level.WARNING;
            category = Category.BUSINESS;
        } else if (FAILURES.contains(code)) {
            level = Level.ERROR;
            category = Category.FAILURE;
        } else if (UNCERTAIN.contains(code)) {
            level = Level.WARNING;
            category = Category.FAILURE;
        } else if (WARNINGS.contains(code) || "ENGINE_COMPLETED".equals(code) && status != null && !"COMPLETED".equals(status)) {
            level = Level.WARNING;
            category = Category.ACTIVITY;
        } else {
            level = Level.INFO;
            category = Category.ACTIVITY;
        }
        ActivityEvent.Builder b = ActivityEvent.of(ctx.agentExecutionId(), observed, Source.FRAMEWORK, code, level, category).occurred(occurred)
                .phase(phase).transaction(tx == null ? null : transactionRef(ctx.agentExecutionId(), tx)).step(step)
                .studioStep(step == null ? null : ctx.steps().studioStepId(step).orElse(null)).action(action).attempt(attempt).elapsed(elapsed)
                .status(status).error(error).framework(fw).position(position);
        if (count != null) b.count("items", count);
        return new Mapped(b.build(), null, excluded, unknown, !KNOWN.contains(code));
    }

    private static String matching(Map<String, Object> m, String k, Pattern p) {
        return m.get(k) instanceof String s && p.matcher(s).matches() && ActivityEvent.identifier(s) ? s : null;
    }

    /**
     * Token opaco da transação: SHA-256 de (execução local + identificador do Framework), 16 hex. Estável dentro da
     * execução; não liga execuções diferentes entre si nem revela o pseudônimo do Framework.
     */
    static String transactionRef(String agentExecutionId, String frameworkTx) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((agentExecutionId + "|" + frameworkTx).getBytes(StandardCharsets.UTF_8));
            return "t-" + HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Maior aninhamento de {@code {}}/{@code []} fora de textos (conta antes de interpretar). */
    static int nesting(String s) {
        int depth = 0;
        int max = 0;
        boolean str = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (str) {
                if (c == '\\') i++;
                else if (c == '"') str = false;
            } else if (c == '"') {
                str = true;
            } else if (c == '{' || c == '[') {
                max = Math.max(max, ++depth);
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return max;
    }
}
