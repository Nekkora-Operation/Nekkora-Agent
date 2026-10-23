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
