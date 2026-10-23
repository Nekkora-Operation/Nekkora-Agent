package com.nekkora.agent.obs;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.obs.ActivityEvent.Category;
import com.nekkora.agent.obs.ActivityEvent.Level;
import com.nekkora.agent.obs.ActivityEvent.Source;
import com.nekkora.agent.obs.ActivityView.FrameworkState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Coleta de UMA execução: lê, a partir da última posição gravada, o {@code eventos.jsonl} do supervisor e — só em
 * pacote do Studio que chegou a ser lançado — o {@code events.jsonl} do Framework na pasta que o Agent criou para a
 * execução; normaliza e grava no {@link ActivityLog}. Roda só na linha do {@link ActivityService} (nunca na EDT e
 * nunca na linha do supervisor). Qualquer falha aqui vira um aviso de TELEMETRIA: a automação não é tocada.
 *
 * <p>Termina ({@code COLLECTION_COMPLETED}) só depois que o registro da execução ficou terminal, as duas origens foram
 * lidas até o fim e o evento final do supervisor foi visto (ou passou a carência de {@link #COMPLETE_GRACE}). O
 * desfecho continua sendo o do supervisor: o último evento lido nunca decide nada.
 */
final class ActivityCollector {
    static final String AGENT_ORIGIN = "AGENT_SOURCE";
    static final String FRAMEWORK_ORIGIN = "FRAMEWORK_SOURCE";
    /** Recusas gravadas uma a uma; além disso só contadas (o total vai para o resumo e para a tela). */
    static final int MAX_INDIVIDUAL_INVALID = 20;
    static final Duration COMPLETE_GRACE = Duration.ofSeconds(10);
    static final Duration NOT_BEFORE_SLACK = Duration.ofMinutes(2);
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "BLOCKED", "OUTCOME_UNKNOWN");

    private final RunStore store;
    private final ExecutionId id;
    private final Clock clock;
    private final ActivityLog log;
    private final SourceFile agent;
    private SourceFile framework;
    private boolean frameworkExpected;
    private boolean frameworkStopped;
    private boolean agentStopped;
    private boolean frameworkUnreadableReported;
    private boolean agentUnreadableReported;
    private FrameworkState frameworkState;
    private StepCatalog catalog;
    private String frameworkId;
    private Instant notBefore;
    private boolean agentTerminalSeen;
    private Instant terminalSince;
    private boolean completed;
    private int individualInvalid;
    private long invalid;
    private long excluded;
    private long unknownFields;
    private long unknownCodes;
    private int restarts;
    private long frameworkEvents;

    private ActivityCollector(RunStore store, ExecutionId id, Clock clock, ActivityLog log) {
        this.store = store;
        this.id = id;
        this.clock = clock;
        this.log = log;
        this.agent = new SourceFile(store.dir(id), List.of("eventos.jsonl"), AgentEvents.MAX_LINE, log.position(Source.AGENT));
    }

    /** Abre (ou retoma, depois de um reinício) a coleta; vazio se a execução não tem registro legível. */
    static Optional<ActivityCollector> open(RunStore store, ExecutionId id, Clock clock) {
        Optional<ExecutionRecord> rec = store.record(id);
        if (rec.isEmpty()) return Optional.empty();
        ExecutionRecord r = rec.get();
        ActivityCollector c = new ActivityCollector(store, id, clock, ActivityLog.open(store.dir(id), id.value()));
        c.completed = c.log.has("COLLECTION_COMPLETED") || c.log.has("HISTORY_PURGED") || c.log.tooLarge();
        if (c.completed) return Optional.of(c);
        c.frameworkExpected = r.kind() == ExecutionPackage.Kind.STUDIO_PYTHON;
        c.frameworkState = c.frameworkExpected ? FrameworkState.WAITING : FrameworkState.NOT_APPLICABLE;
        c.notBefore = r.created().minus(NOT_BEFORE_SLACK);
        c.frameworkId = c.log.firstFrameworkExecution();
        c.individualInvalid = c.log.count("EVENT_INVALID");
        c.invalid = c.individualInvalid;
        for (String p : List.of("SOURCE_UNSAFE", "SOURCE_REPLACED")) {
            if (c.log.hasSourceProblem(p, FRAMEWORK_ORIGIN)) {
                c.frameworkStopped = true;
                c.frameworkState = "SOURCE_UNSAFE".equals(p) ? FrameworkState.UNSAFE : FrameworkState.REPLACED;
            }
            if (c.log.hasSourceProblem(p, AGENT_ORIGIN)) c.agentStopped = true;
        }
        for (ActivityEvent e : c.log.all()) {
            if (e.source() == Source.AGENT && e.status() != null && TERMINAL.contains(e.status())) c.agentTerminalSeen = true;
            if (e.source() == Source.FRAMEWORK) c.frameworkEvents++;
        }
        if (c.frameworkEvents > 0 && !c.frameworkStopped) c.frameworkState = FrameworkState.READING;
        Instant now = clock.instant();
        if (c.log.size() == 0) {
            c.log.append(List.of(ActivityEvent.of(id.value(), now, Source.COLLECTOR, "COLLECTION_STARTED", Level.INFO, Category.TELEMETRY)
                    .count("framework_source", c.frameworkExpected ? 1 : 0).build()), now);
        } else {
            c.restarts = c.log.count("COLLECTION_RESUMED") + 1;
            c.log.append(List.of(ActivityEvent.of(id.value(), now, Source.COLLECTOR, "COLLECTION_RESUMED", Level.INFO, Category.TELEMETRY)
                    .count("restarts", c.restarts).build()), now);
        }
        return Optional.of(c);
    }

    boolean completed() {
        return completed;
    }

    ActivityLog log() {
        return log;
    }

    /**
     * Uma rodada de leitura (no máximo {@code budget} bytes por origem). Devolve se algo novo foi gravado.
     */
    boolean poll(int budget) {
        if (completed) {
            log.flush(clock.instant()); // fechamento ainda pendente no disco: só a gravação é tentada de novo
            return false;
        }
        Instant now = clock.instant();
        Optional<ExecutionRecord> rec = store.record(id);
        if (rec.isEmpty()) return false; // registro em gravação ou apagado: a próxima rodada tenta de novo
        ExecutionRecord r = rec.get();
        boolean terminal = r.state().terminal();
        List<ActivityEvent> batch = new ArrayList<>();
        boolean more = false;

        if (frameworkExpected && !frameworkStopped) {
            if (framework == null) store.output(id).ifPresent(o -> framework = new SourceFile(o, List.of("execucao", "events.jsonl"), FrameworkEvents.MAX_LINE,
                    log.position(Source.FRAMEWORK)));
            if (framework != null) {
                SourceFile.Batch b = framework.read(budget, terminal);
                more |= b.more();
                if (b.problem() != null) {
                    frameworkProblem(b.problem(), r, terminal, now, batch);
                } else {
                    if (frameworkUnreadableReported) {
                        frameworkUnreadableReported = false;
                        batch.add(collector(now, "SOURCE_AVAILABLE", Level.INFO, FRAMEWORK_ORIGIN, null, null));
                    }
                    if (!b.lines().isEmpty() || framework.position() > 0) frameworkState = FrameworkState.READING;
                    for (SourceFile.Line l : b.lines()) frameworkLine(l, now, batch);
                }
            } else if (terminal) {
                frameworkMissing(r, now, batch);
            }
        }
        if (!agentStopped) {
            SourceFile.Batch b = agent.read(budget, terminal);
            more |= b.more();
            if (b.problem() != null) {
                agentProblem(b.problem(), terminal, now, batch);
            } else {
                if (agentUnreadableReported) {
                    agentUnreadableReported = false;
                    batch.add(collector(now, "SOURCE_AVAILABLE", Level.INFO, AGENT_ORIGIN, null, null));
                }
                for (SourceFile.Line l : b.lines()) agentLine(l, now, batch);
            }
        }
        // as duas origens são lidas na mesma rodada: o lote entra na ordem de OCORRÊNCIA (ordenação estável; aviso do
        // coletor, sem horário de origem, fica no fim) — a sequência continua sendo a ordem de gravação
        batch.sort(java.util.Comparator.comparing((ActivityEvent e) -> e.occurred() != null ? e.occurred() : e.observed()));
        List<ActivityEvent> written = log.append(batch, now);
        if (terminal && !more) {
            if (terminalSince == null) terminalSince = now;
            if (agentTerminalSeen || agentStopped || Duration.between(terminalSince, now).compareTo(COMPLETE_GRACE) > 0) {
                complete(r, now);
                return true;
            }
        }
        return !written.isEmpty();
    }

    private void frameworkLine(SourceFile.Line l, Instant now, List<ActivityEvent> batch) {
        if (l.invalid() != null) {
            invalid(FRAMEWORK_ORIGIN, reason(l.invalid()), l.end(), now, batch);
            return;
        }
        if (catalog == null) catalog = store.record(id).map(r -> StepCatalog.load(store.dir(id).resolve("pacote"), r.fingerprint())).orElse(StepCatalog.EMPTY);
        FrameworkEvents.Mapped m = FrameworkEvents.map(l.text(), new FrameworkEvents.Context(id.value(), notBefore, catalog), now, l.end());
        if (m.event() == null) {
            invalid(FRAMEWORK_ORIGIN, m.rejected(), l.end(), now, batch);
            return;
        }
        String fw = m.event().frameworkExecutionId();
        if (frameworkId == null) {
            frameworkId = fw;
        } else if (!frameworkId.equals(fw)) {
            invalid(FRAMEWORK_ORIGIN, "CORRELATION_CONFLICT", l.end(), now, batch); // outra execução do Framework: nunca misturada
            return;
        }
        excluded += m.excluded();
        unknownFields += m.unknown();
        if (m.unknownCode()) unknownCodes++;
        frameworkEvents++;
        batch.add(m.event());
    }

    private void agentLine(SourceFile.Line l, Instant now, List<ActivityEvent> batch) {
        if (l.invalid() != null) {
            invalid(AGENT_ORIGIN, reason(l.invalid()), l.end(), now, batch);
            return;
        }
        AgentEvents.Mapped m = AgentEvents.map(l.text(), id.value(), now, l.end());
        if (m.event() == null) {
            invalid(AGENT_ORIGIN, m.rejected(), l.end(), now, batch);
            return;
        }
        unknownFields += m.unknown();
        if (m.event().status() != null && TERMINAL.contains(m.event().status())) agentTerminalSeen = true;
        batch.add(m.event());
    }

    private void invalid(String origin, String reason, long position, Instant now, List<ActivityEvent> batch) {
        invalid++;
        if (individualInvalid >= MAX_INDIVIDUAL_INVALID) return;
        individualInvalid++;
        batch.add(collector(now, "EVENT_INVALID", Level.WARNING, origin, reason, position));
    }

    private static String reason(SourceFile.Invalid i) {
        return switch (i) {
            case TOO_LONG -> "LINE_TOO_LONG";
            case ENCODING -> "ENCODING_INVALID";
            case PARTIAL -> "PARTIAL_LINE";
        };
    }

    private void frameworkProblem(SourceFile.Problem p, ExecutionRecord r, boolean terminal, Instant now, List<ActivityEvent> batch) {
        switch (p) {
            case MISSING -> {
                if (framework.position() > 0) { // já lido e sumiu: removido ou trocado
                    stopFramework(FrameworkState.REPLACED, "SOURCE_REPLACED", ErrorCode.OBS_SOURCE_REPLACED, now, batch);
                } else if (terminal) {
                    frameworkMissing(r, now, batch);
                }
            }
            case UNSAFE -> stopFramework(FrameworkState.UNSAFE, "SOURCE_UNSAFE", ErrorCode.OBS_SOURCE_UNSAFE, now, batch);
            case REPLACED -> stopFramework(FrameworkState.REPLACED, "SOURCE_REPLACED", ErrorCode.OBS_SOURCE_REPLACED, now, batch);
            case UNREADABLE -> {
                frameworkState = FrameworkState.UNAVAILABLE;
                if (!frameworkUnreadableReported) {
                    frameworkUnreadableReported = true;
                    batch.add(collector(now, "SOURCE_UNAVAILABLE", Level.WARNING, FRAMEWORK_ORIGIN, ErrorCode.OBS_SOURCE_UNAVAILABLE.code(), null));
                }
                if (terminal) frameworkStopped = true;
            }
        }
    }

    /** Execução terminada sem arquivo de eventos: aviso só se o processo chegou a ser lançado (bloqueada = nada esperado). */
    private void frameworkMissing(ExecutionRecord r, Instant now, List<ActivityEvent> batch) {
        frameworkStopped = true;
        if (r.owner() == null && r.started() == null) {
            frameworkState = FrameworkState.NOT_APPLICABLE;
            return;
        }
        frameworkState = FrameworkState.UNAVAILABLE;
        batch.add(collector(now, "SOURCE_UNAVAILABLE", Level.WARNING, FRAMEWORK_ORIGIN, ErrorCode.OBS_SOURCE_UNAVAILABLE.code(), null));
    }

    private void stopFramework(FrameworkState s, String code, ErrorCode why, Instant now, List<ActivityEvent> batch) {
        frameworkStopped = true;
        frameworkState = s;
        batch.add(collector(now, code, Level.WARNING, FRAMEWORK_ORIGIN, why.code(), framework.position()));
    }

    private void agentProblem(SourceFile.Problem p, boolean terminal, Instant now, List<ActivityEvent> batch) {
        switch (p) {
            case MISSING -> {
                if (agent.position() > 0 || terminal) {
                    agentStopped = true;
                    batch.add(collector(now, "SOURCE_UNAVAILABLE", Level.WARNING, AGENT_ORIGIN, ErrorCode.OBS_SOURCE_UNAVAILABLE.code(), null));
                }
            }
            case UNSAFE, REPLACED -> {
                agentStopped = true;
                ErrorCode why = p == SourceFile.Problem.UNSAFE ? ErrorCode.OBS_SOURCE_UNSAFE : ErrorCode.OBS_SOURCE_REPLACED;
                batch.add(collector(now, p == SourceFile.Problem.UNSAFE ? "SOURCE_UNSAFE" : "SOURCE_REPLACED", Level.WARNING, AGENT_ORIGIN, why.code(),
                        agent.position()));
            }
            case UNREADABLE -> {
                if (!agentUnreadableReported) {
                    agentUnreadableReported = true;
                    batch.add(collector(now, "SOURCE_UNAVAILABLE", Level.WARNING, AGENT_ORIGIN, ErrorCode.OBS_SOURCE_UNAVAILABLE.code(), null));
                }
                if (terminal) agentStopped = true;
            }
        }
    }

    private void complete(ExecutionRecord r, Instant now) {
        ActivityEvent.Builder b = ActivityEvent.of(id.value(), now, Source.COLLECTOR, "COLLECTION_COMPLETED", Level.INFO, Category.TELEMETRY)
                .status(r.state().name()).count("events", log.size() + 1L).count("framework_events", frameworkEvents).count("dropped", log.dropped())
                .count("invalid", invalid).count("excluded_fields", excluded).count("unknown_fields", unknownFields).count("unknown_codes", unknownCodes)
                .count("restarts", restarts);
        if (unknownFields + unknownCodes > 0) b.error(ErrorCode.OBS_UNKNOWN_CONTENT.code());
        log.append(List.of(b.build()), now);
        completed = true;
    }

    private ActivityEvent collector(Instant now, String code, Level level, String origin, String error, Long position) {
        return ActivityEvent.of(id.value(), now, Source.COLLECTOR, code, level, Category.TELEMETRY).status(origin).error(error).position(position).build();
    }

    /** Fotografia para a tela (contadores desde a abertura desta coleta). */
    ActivityView.Health health(boolean live) {
        return new ActivityView.Health(live, completed, frameworkExpected, frameworkState == null ? FrameworkState.NOT_APPLICABLE : frameworkState,
                log.dropped(), invalid, excluded, unknownFields, unknownCodes, log.capped(), log.sequenceGap(), log.unreadable(), log.has("HISTORY_PURGED"),
                log.storeFailed(), log.pendingCount(), restarts, frameworkId, catalog != null && catalog.trusted());
    }
}
