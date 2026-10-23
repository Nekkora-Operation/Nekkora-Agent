package com.nekkora.agent.obs;

import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.obs.ActivityEvent.Category;
import com.nekkora.agent.obs.ActivityEvent.Level;
import com.nekkora.agent.obs.ActivityEvent.Source;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * O que a tela mostra de uma execução: o registro do SUPERVISOR (fonte do estado e do desfecho), os eventos de
 * atividade (todos ou só os novos depois de uma sequência) e a saúde da coleta — sem Swing e sem o formato do
 * Framework (a tela só conhece o envelope do Agent).
 */
public record ActivityView(ExecutionRecord record, List<ActivityEvent> events, Health health) {

    public ActivityView {
        events = List.copyOf(events);
    }

    /** Estado da leitura dos eventos do Framework. */
    public enum FrameworkState { NOT_APPLICABLE, WAITING, READING, UNAVAILABLE, UNSAFE, REPLACED }

    /** Saúde da coleta: nada disto é escondido da pessoa. */
    public record Health(boolean live, boolean completed, boolean frameworkExpected, FrameworkState frameworkState, long dropped,
                         long invalid, long excludedFields, long unknownFields, long unknownCodes, boolean capped, boolean sequenceGap, int unreadableLines,
                         boolean purged, boolean storeFailed, int restarts, String frameworkExecutionId, boolean stepCatalogTrusted) {

        /** Estado da origem do Framework como texto estável (para a tela e os testes). */
        public String frameworkStateName() {
            return frameworkState.name();
        }
    }

    /** Situação derivada dos eventos (fase, etapa, transações, última falha). Nunca decide o desfecho. */
    public record Progress(String phase, ActivityEvent currentStep, int transactionsStarted, int transactionsSucceeded, int transactionsFailed,
                           int transactionsRejected, int transactionsSkipped, ActivityEvent lastFailure, Set<String> phasesSeen) {}

    public static Progress progress(List<ActivityEvent> events) {
        String phase = null;
        ActivityEvent step = null;
        ActivityEvent failure = null;
        Set<String> started = new LinkedHashSet<>();
        Set<String> phases = new LinkedHashSet<>();
        int ok = 0;
        int failed = 0;
        int rejected = 0;
        int skipped = 0;
        for (ActivityEvent e : events) {
            if (e.source() != Source.FRAMEWORK) {
                if (e.severity() == Level.ERROR && e.category() == Category.FAILURE) failure = e;
                continue;
            }
            if (e.phase() != null) {
                phase = e.phase();
                phases.add(e.phase());
            }
            if (e.stepId() != null && e.code().startsWith("ACTION_")) step = e;
            switch (e.code()) {
                case "TRANSACTION_STARTED" -> {
                    if (e.transactionRef() != null) started.add(e.transactionRef());
                }
                case "TRANSACTION_COMPLETED" -> {
                    if ("BUSINESS_REJECTED".equals(e.status())) rejected++;
                    else ok++;
                }
                case "TRANSACTION_FAILED" -> {
                    if (e.category() == Category.BUSINESS) rejected++;
                    else failed++;
                }
                case "TRANSACTION_SKIPPED" -> skipped++;
                default -> {
                    // demais códigos não mudam a contagem
                }
            }
            if (e.category() == Category.FAILURE || e.category() == Category.BUSINESS) failure = e;
        }
        return new Progress(phase, step, started.size(), ok, failed, rejected, skipped, failure, phases);
    }

    /** Situação da linha do tempo: só {@link #COMPLETE} pode ser apresentada como completa. */
    public enum Integrity { COMPLETE, IN_PROGRESS, PENDING, INCOMPLETE }

    /** Por que a linha do tempo não está completa (cada motivo com o código que a pessoa pode consultar). */
    public enum Gap {
        /** Limite de eventos/tamanho da execução: eventos descartados e contados. */
        RETENTION_LIMIT("AGT-OBS-005"),
        /** O supervisor parou de gravar eventos no limite de 500. */
        SUPERVISOR_LIMIT("AGT-OBS-006"),
        /** Linhas recusadas (parciais, corrompidas, fora do contrato ou de outra execução do Framework). */
        REJECTED("AGT-OBS-002"),
        /** Lacuna de sequência ou linha ilegível no próprio histórico do Agent. */
        SEQUENCE_GAP("AGT-OBS-013"),
        /** O histórico não pôde ser gravado durante a coleta. */
        STORE_FAILED("AGT-OBS-009"),
        /** A atividade detalhada foi removida pela retenção. */
        PURGED("AGT-OBS-010"),
        /** A leitura das etapas do Framework parou (arquivo trocado, truncado ou recusado). */
        FRAMEWORK_STOPPED("AGT-OBS-004"),
        /** As etapas do Framework não puderam ser lidas. */
        FRAMEWORK_UNAVAILABLE("AGT-OBS-001"),
        /** O registro do supervisor deixou de ser lido (ausente, trocado ou recusado). */
        SUPERVISOR_SOURCE("AGT-OBS-001");

        private final String code;

        Gap(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Resultado da conferência de completude. */
    public record Completeness(Integrity integrity, List<Gap> gaps) {
        public Completeness {
            gaps = List.copyOf(gaps);
        }

        public boolean complete() {
            return integrity == Integrity.COMPLETE;
        }
    }

    /**
     * Confere se a linha do tempo está completa: nenhuma perda, recusa, lacuna ou origem interrompida, e a coleta
     * encerrada. Usa a saúde (contagens) e os próprios eventos (marcadores de lacuna e de origem gravados no
     * histórico). Na dúvida, nunca diz "completa".
     */
    public static Completeness completeness(Health h, List<ActivityEvent> events) {
        if (h == null) return new Completeness(Integrity.PENDING, List.of());
        Set<Gap> gaps = new LinkedHashSet<>();
        if (h.purged()) gaps.add(Gap.PURGED);
        if (h.capped() || h.dropped() > 0) gaps.add(Gap.RETENTION_LIMIT);
        if (h.invalid() > 0) gaps.add(Gap.REJECTED);
        if (h.sequenceGap() || h.unreadableLines() > 0) gaps.add(Gap.SEQUENCE_GAP);
        if (h.storeFailed()) gaps.add(Gap.STORE_FAILED);
        if (h.frameworkExpected()) {
            if (h.frameworkState() == FrameworkState.REPLACED || h.frameworkState() == FrameworkState.UNSAFE) gaps.add(Gap.FRAMEWORK_STOPPED);
            if (h.frameworkState() == FrameworkState.UNAVAILABLE) gaps.add(Gap.FRAMEWORK_UNAVAILABLE);
        }
        boolean supervisorUnavailable = false;
        for (ActivityEvent e : events) {
            if ("EVENT_GAP".equals(e.code())) {
                if ("AGT-OBS-006".equals(e.errorCode())) gaps.add(Gap.SUPERVISOR_LIMIT);
                else gaps.add(Gap.RETENTION_LIMIT);
            }
            if ("EVENT_INVALID".equals(e.code())) gaps.add(Gap.REJECTED);
            if (e.source() == Source.COLLECTOR && "AGENT_SOURCE".equals(e.status())) {
                switch (e.code()) {
                    case "SOURCE_UNSAFE", "SOURCE_REPLACED" -> gaps.add(Gap.SUPERVISOR_SOURCE);
                    case "SOURCE_UNAVAILABLE" -> supervisorUnavailable = true;
                    case "SOURCE_AVAILABLE" -> supervisorUnavailable = false;
                    default -> {
                        // demais avisos não indicam lacuna
                    }
                }
            }
            if ("HISTORY_PURGED".equals(e.code())) gaps.add(Gap.PURGED);
        }
        if (supervisorUnavailable && !h.live()) gaps.add(Gap.SUPERVISOR_SOURCE);
        Integrity i;
        if (!gaps.isEmpty()) i = Integrity.INCOMPLETE;
        else if (h.completed()) i = Integrity.COMPLETE;
        else if (h.live()) i = Integrity.IN_PROGRESS;
        else i = Integrity.PENDING;
        return new Completeness(i, List.copyOf(gaps));
    }

    /** O último evento de etapa antes de uma falha (OBS-06): a etapa observável em que a falha ocorreu. */
    public static Optional<ActivityEvent> stepBefore(List<ActivityEvent> events, ActivityEvent failure) {
        if (failure == null) return Optional.empty();
        if (failure.stepId() != null) return Optional.of(failure);
        ActivityEvent last = null;
        for (ActivityEvent e : events) {
            if (e.sequence() >= failure.sequence()) break;
            if (e.source() == Source.FRAMEWORK && e.stepId() != null) last = e;
        }
        return Optional.ofNullable(last);
    }
}
