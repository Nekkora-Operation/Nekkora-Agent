package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * AGENT-PYTHON-001: reconciliação das execuções do LANÇADOR ({@link ExecutionPackage.Kind#EXTERNAL_MONITORED}). O supervisor
 * delas é o processo do lançador; o Agent só as fecha quando esse processo já não existe (PID + início + executável não
 * conferem): READY → BLOCKED (nada foi lançado), STARTING/RUNNING → {@code OUTCOME_UNKNOWN} com {@code AGT-EXT-004}. Nada
 * é relançado e nenhum resultado é inventado — mesmo que o processo do cliente ainda esteja vivo, o fim dele não pode mais
 * ser observado por ninguém.
 */
public final class ExternalRuns {
    private ExternalRuns() {}

    /** Devolve quantas execuções foram fechadas agora. Seguro para chamar a qualquer momento (lançador vivo = intocado). */
    public static int reconcile(RunStore store, Clock clock) {
        int n = 0;
        for (ExecutionRecord r : store.all()) {
            if (r.kind() != ExecutionPackage.Kind.EXTERNAL_MONITORED || r.state().terminal()) continue;
            Optional<ProcessOwner> launcher = ExternalLaunch.launcherOf(store.dir(r.id()));
            if (launcher.isPresent() && launcher.get().alive()) continue;
            // relido DEPOIS de saber que o lançador morreu: a última gravação dele (o desfecho) já está no disco
            Optional<ExecutionRecord> fresh = store.record(r.id());
            if (fresh.isEmpty() || fresh.get().state().terminal()) continue;
            r = fresh.get();
            ExecutionState to = r.state() == ExecutionState.READY ? ExecutionState.BLOCKED : ExecutionState.OUTCOME_UNKNOWN;
            ErrorCode why = to == ExecutionState.BLOCKED ? ErrorCode.EXT_STORAGE_FAILED : ErrorCode.EXT_LAUNCHER_LOST;
            try {
                store.event(r.id(), clock.instant(), "RECONCILE", Map.of("state", r.state().name()));
                store.save(r.moveTo(to, why, clock.instant()));
                store.event(r.id(), clock.instant(), to.name(), Map.of("code", why.code()));
                n++;
            } catch (ExecRefused e) {
                // tentado de novo na próxima rodada
            }
        }
        return n;
    }
}
