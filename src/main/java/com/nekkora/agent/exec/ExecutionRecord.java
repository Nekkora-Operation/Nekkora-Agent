package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import java.time.Instant;
import java.util.Map;

/**
 * O que o Agent registra de uma execução ({@code nekkora-agent/execution} 1.0.0): identificadores, tipo e nome curto do
 * pacote, impressão digital, estado, código do catálogo, tempos, posse do processo, código de saída, contagens publicadas
 * pelo protocolo e quantas suspensões/bloqueios foram vistos. Nenhum caminho de entrada, valor, saída do processo ou HTML.
 */
public record ExecutionRecord(ExecutionId id, ExecutionPackage.Kind kind, String packageName, String fingerprint, ExecutionState state, ErrorCode code,
                              Instant created, Instant started, Instant ended, ProcessOwner owner, Integer exitCode, Map<String, Long> counts,
                              int suspensions, int locks, boolean adopted, long outputBytes) {

    public static final String SCHEMA = "nekkora-agent/execution";
    public static final String VERSION = "1.0.0";

    public ExecutionRecord {
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }

    /** Nova versão no estado {@code next}; transição fora do ciclo de vida é recusada. */
    public ExecutionRecord moveTo(ExecutionState next, ErrorCode why, Instant at) {
        if (!state.canMoveTo(next)) throw new IllegalStateException("transição inválida: " + state + " → " + next);
        Instant s = next == ExecutionState.RUNNING ? at : started;
        Instant e = next.terminal() ? at : ended;
        return new ExecutionRecord(id, kind, packageName, fingerprint, next, why, created, s, e, owner, exitCode, counts, suspensions, locks, adopted, outputBytes);
    }

    public ExecutionRecord withOwner(ProcessOwner o) {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, o, exitCode, counts, suspensions, locks, adopted, outputBytes);
    }

    public ExecutionRecord withExit(Integer exit, Map<String, Long> c, long bytes) {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, owner, exit, c, suspensions, locks, adopted, bytes);
    }

    public ExecutionRecord withSuspension() {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, owner, exitCode, counts, suspensions + 1, locks, adopted,
                outputBytes);
    }

    public ExecutionRecord withLock() {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, owner, exitCode, counts, suspensions, locks + 1, adopted,
                outputBytes);
    }

    public ExecutionRecord adoptedAfterRestart() {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, owner, exitCode, counts, suspensions, locks, true,
                outputBytes);
    }
}
