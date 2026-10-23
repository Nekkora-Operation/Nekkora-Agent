package com.nekkora.agent.obs;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Um evento de atividade no envelope canônico {@code nekkora-agent/observability} 1.0.0 — o mesmo modelo para a tela do
 * Agent, o histórico local e (no futuro) o transportador para a Platform. Todo campo é fechado: códigos, enums,
 * identificadores validados e números. Não existe campo livre (mensagem, detalhe, valor): por construção não há onde pôr
 * um dado do cliente. Opcionais ausentes são {@code null} e são omitidos no JSON.
 *
 * <p>Identidade: {@code agentExecutionId} (a execução local, sempre presente) + {@code sequence} (1, 2, 3… na ordem em
 * que o Agent OBSERVOU e gravou o evento; estável depois de reinício). {@code occurred} é o instante informado pela
 * origem (pode chegar atrasado); {@code observed}, quando o Agent o leu. {@code sourcePosition} é a posição (byte) logo
 * depois da linha lida na origem: chave de deduplicação/retomada por origem.
 */
public record ActivityEvent(String agentExecutionId, long sequence, Instant occurred, Instant observed, Source source, String code, Level severity,
                            Category category, String phase, String transactionRef, String stepId, String studioStepId, String action, Integer attempt,
                            Long elapsedMs, String status, String errorCode, Map<String, Long> counts, String frameworkExecutionId, Long sourcePosition) {

    public static final String SCHEMA = "nekkora-agent/observability";
    public static final String VERSION = "1.0.0";

    /** Quem produziu o fato: o supervisor do Agent, o Framework do pacote ou o próprio coletor (telemetria). */
    public enum Source { AGENT, FRAMEWORK, COLLECTOR }

    public enum Level { INFO, WARNING, ERROR }

    /**
     * Separação exigida no diagnóstico: ciclo de vida (supervisor), atividade (etapas), rejeição de negócio, falha de
     * execução (técnica) e falha de telemetria (o leitor de eventos — nunca muda o desfecho da automação).
     */
    public enum Category { LIFECYCLE, ACTIVITY, BUSINESS, FAILURE, TELEMETRY }

    // ----- formatos admitidos (os mesmos na escrita e na leitura do histórico) -----
    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    static final Pattern PHASE = Pattern.compile("INIT|TRANSFER_DATA_IN|PROCESS|TRANSFER_DATA_OUT|END");
    static final Pattern TX_REF = Pattern.compile("t-[0-9a-f]{16}");
    /** {@code search.fill_search_term}: minúsculas, pontos e sublinhados; nunca e-mail, caminho, URL, número solto. */
    static final Pattern STEP = Pattern.compile("[a-z][a-z0-9_]{0,63}(\\.[a-z0-9_]{1,63}){0,7}");
    static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    static final Pattern ACTION = Pattern.compile("[a-z][a-z_]{0,39}");
    static final Pattern STATUS = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    static final Pattern ERROR = Pattern.compile("[A-Z][A-Z0-9_]{1,63}|AGT-[A-Z]{2,5}-[0-9]{3}");
    static final Pattern COUNT_KEY = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    /** Identificador de execução do Framework 0.3.0 ({@code ex-} + 20 hex); outro formato não é correlacionado. */
    static final Pattern FW_EXECUTION = Pattern.compile("ex-[0-9a-f]{8,64}");
    /**
     * Salvaguarda adicional para textos de identificador: 4 ou mais dígitos seguidos (CPF, CNPJ, telefone, número de
     * pedido) nunca formam um identificador aceito, mesmo que o formato geral passe.
     */
    static final Pattern DIGIT_RUN = Pattern.compile("[0-9]{4,}");
    static final int MAX_COUNTS = 16;
    static final long MAX_NUMBER = 1_000_000_000_000L;

    public ActivityEvent {
        Objects.requireNonNull(agentExecutionId);
        Objects.requireNonNull(observed);
        Objects.requireNonNull(source);
        Objects.requireNonNull(severity);
        Objects.requireNonNull(category);
        counts = counts == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(counts));
        check(com.nekkora.agent.exec.ExecutionId.valid(agentExecutionId), "agent_execution_id");
        check(sequence >= 0, "sequence");
        check(code != null && CODE.matcher(code).matches(), "event_code");
        check(phase == null || PHASE.matcher(phase).matches(), "phase");
        check(transactionRef == null || TX_REF.matcher(transactionRef).matches(), "transaction_ref");
        check(stepId == null || stepId.length() <= 120 && STEP.matcher(stepId).matches() && identifier(stepId), "step_id");
        check(studioStepId == null || UUID.matcher(studioStepId).matches(), "studio_step_id");
        check(action == null || ACTION.matcher(action).matches(), "action");
        check(attempt == null || attempt >= 0 && attempt <= 10_000, "attempt");
        check(elapsedMs == null || elapsedMs >= 0 && elapsedMs < MAX_NUMBER, "elapsed_ms");
        check(status == null || STATUS.matcher(status).matches() && identifier(status), "status");
        check(errorCode == null || ERROR.matcher(errorCode).matches() && identifier(errorCode), "error_code");
        check(frameworkExecutionId == null || FW_EXECUTION.matcher(frameworkExecutionId).matches(), "framework_execution_id");
        check(sourcePosition == null || sourcePosition >= 0, "source_position");
        check(counts.size() <= MAX_COUNTS, "counts");
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            check(COUNT_KEY.matcher(e.getKey()).matches() && e.getValue() != null && e.getValue() >= 0 && e.getValue() < MAX_NUMBER, "counts");
        }
    }

    /** Sem sequência longa de dígitos (ver {@link #DIGIT_RUN}). */
    static boolean identifier(String s) {
        return !DIGIT_RUN.matcher(s).find();
    }

    private static void check(boolean ok, String field) {
        if (!ok) throw new IllegalArgumentException(field); // só o nome do campo: o valor recusado não entra na mensagem
    }

    /** O mesmo evento com outro número de sequência (atribuído ao gravar). */
    public ActivityEvent withSequence(long seq) {
        return new ActivityEvent(agentExecutionId, seq, occurred, observed, source, code, severity, category, phase, transactionRef, stepId, studioStepId,
                action, attempt, elapsedMs, status, errorCode, counts, frameworkExecutionId, sourcePosition);
    }

    /** Evento mínimo (só os campos obrigatórios); os opcionais entram pelo {@link Builder}. */
    public static Builder of(String agentExecutionId, Instant observed, Source source, String code, Level severity, Category category) {
        return new Builder(agentExecutionId, observed, source, code, severity, category);
    }

    /** Montagem dos campos opcionais; {@link #build()} valida tudo. */
    public static final class Builder {
        private final String id;
        private final Instant observed;
        private final Source source;
        private final String code;
        private final Level severity;
        private final Category category;
        private Instant occurred;
        private String phase, tx, step, studioStep, action, status, error, fwId;
        private Integer attempt;
        private Long elapsed, position;
        private final Map<String, Long> counts = new TreeMap<>();

        private Builder(String id, Instant observed, Source source, String code, Level severity, Category category) {
            this.id = id;
            this.observed = observed;
            this.source = source;
            this.code = code;
            this.severity = severity;
            this.category = category;
        }

        public Builder occurred(Instant v) { occurred = v; return this; }
        public Builder phase(String v) { phase = v; return this; }
        public Builder transaction(String v) { tx = v; return this; }
        public Builder step(String v) { step = v; return this; }
        public Builder studioStep(String v) { studioStep = v; return this; }
        public Builder action(String v) { action = v; return this; }
        public Builder attempt(Integer v) { attempt = v; return this; }
        public Builder elapsed(Long v) { elapsed = v; return this; }
        public Builder status(String v) { status = v; return this; }
        public Builder error(String v) { error = v; return this; }
        public Builder framework(String v) { fwId = v; return this; }
        public Builder position(Long v) { position = v; return this; }

        public Builder count(String k, long v) {
            counts.put(k, v);
            return this;
        }

        public ActivityEvent build() {
            return new ActivityEvent(id, 0, occurred, observed, source, code, severity, category, phase, tx, step, studioStep, action, attempt, elapsed,
                    status, error, counts, fwId, position);
        }
    }
}
