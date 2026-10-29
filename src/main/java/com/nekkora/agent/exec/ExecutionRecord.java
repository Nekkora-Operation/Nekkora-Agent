package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * O que o Agent registra de uma execução ({@code nekkora-agent/execution} 1.1.0): identificadores, tipo e nome curto do
 * pacote, impressão digital, estado, código do catálogo, tempos, posse do processo, código de saída, contagens publicadas
 * pelo protocolo e quantas suspensões/bloqueios foram vistos. Nenhum caminho de entrada, valor, saída do processo ou HTML.
 * <p>
 * 1.1.0 (aditiva; o leitor aceita 1.0.0): {@link #origin} (quem pediu), {@link #cancellation} (pedido × confirmação),
 * {@link #recovered} (desfecho lido do protocolo conferido depois de um reinício do Agent) e {@link #progress} (progresso
 * parcial verificado, que NUNCA é desfecho).
 */
public record ExecutionRecord(ExecutionId id, ExecutionPackage.Kind kind, String packageName, String fingerprint, ExecutionState state, ErrorCode code,
                              Instant created, Instant started, Instant ended, ProcessOwner owner, Integer exitCode, Map<String, Long> counts,
                              int suspensions, int locks, boolean adopted, long outputBytes, Origin origin, Cancellation cancellation, boolean recovered,
                              PartialProgress progress) {

    public static final String SCHEMA = "nekkora-agent/execution";
    public static final String VERSION = "1.1.0";
    /** Versões que o leitor aceita (a 1.0.0 não tem os campos novos: origem desconhecida, sem cancelamento nem progresso). */
    public static final java.util.Set<String> READABLE = java.util.Set.of("1.0.0", "1.1.0");

    /**
     * Quem pediu a execução (D-SA-05). É a origem AUTENTICADA DO PEDIDO no Agent, nunca a autoria do pacote: o pacote
     * continua "origem não verificada" até existir assinatura. {@code UNKNOWN}: registro 1.0.0, anterior ao campo.
     */
    public enum Origin {
        /** A pessoa operadora, pela janela do Agent. */
        LOCAL_UI,
        /** Reservado para o canal local (D-SA-01): ainda não produzido por esta versão. */
        STUDIO_LOCAL,
        UNKNOWN
    }

    /** Quem pediu o cancelamento. */
    public enum CancelSource { LOCAL_UI, AGENT_SHUTDOWN }

    /** Situação do pedido de cancelamento (D-SA-04): pedir não é confirmar. */
    public enum CancelOutcome {
        /** Pedido registrado; o supervisor ainda não encerrou a árvore do processo. */
        PENDING,
        /** O supervisor encerrou a árvore possuída: o estado é {@code CANCELLED}. */
        CONFIRMED,
        /** O processo terminou por conta própria antes do encerramento: o estado é o do protocolo, não {@code CANCELLED}. */
        NOT_EFFECTIVE
    }

    public record Cancellation(Instant requested, CancelSource source, CancelOutcome outcome) {
        public Cancellation {
            Objects.requireNonNull(requested);
            Objects.requireNonNull(source);
            Objects.requireNonNull(outcome);
        }

        public Cancellation resolved(CancelOutcome o) {
            return new Cancellation(requested, source, o);
        }
    }

    /**
     * A03: o que se sabe do progresso de negócio quando NÃO há resultado final do processo (cancelamento, prazo, desfecho
     * desconhecido, falha sem protocolo). Vem do checkpoint do Framework conferido pela integridade que o próprio Framework
     * grava. É parcial por definição: não diz que a execução concluiu, e uma transação {@code IN_PROGRESS} continua de
     * desfecho desconhecido. A planilha nunca é dada como consolidada (só o resultado final do Framework confirmaria).
     * {@code available} falso: não foi possível obter (ver {@code problem}); isso não significa "nada foi feito".
     */
    public record PartialProgress(boolean available, Map<String, Long> transactions, long delivered, String problem) {
        public PartialProgress {
            // ordem alfabética fixa: o estado.json sai igual para o mesmo progresso
            transactions = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(transactions == null ? Map.of() : transactions));
        }

        public static PartialProgress unavailable(String problem) {
            return new PartialProgress(false, Map.of(), 0, problem);
        }

        public long total() {
            return transactions.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    public ExecutionRecord {
        counts = counts == null ? Map.of() : Map.copyOf(counts);
        origin = origin == null ? Origin.UNKNOWN : origin;
    }

    /** Registro sem os campos da 1.1.0 (origem desconhecida, sem cancelamento, sem progresso). */
    public ExecutionRecord(ExecutionId id, ExecutionPackage.Kind kind, String packageName, String fingerprint, ExecutionState state, ErrorCode code,
                           Instant created, Instant started, Instant ended, ProcessOwner owner, Integer exitCode, Map<String, Long> counts, int suspensions,
                           int locks, boolean adopted, long outputBytes) {
        this(id, kind, packageName, fingerprint, state, code, created, started, ended, owner, exitCode, counts, suspensions, locks, adopted, outputBytes,
                Origin.UNKNOWN, null, false, null);
    }

    /** Nova versão no estado {@code next}; transição fora do ciclo de vida é recusada. */
    public ExecutionRecord moveTo(ExecutionState next, ErrorCode why, Instant at) {
        if (!state.canMoveTo(next)) throw new IllegalStateException("transição inválida: " + state + " → " + next);
        Instant s = next == ExecutionState.RUNNING ? at : started;
        Instant e = next.terminal() ? at : ended;
        return new ExecutionRecord(id, kind, packageName, fingerprint, next, why, created, s, e, owner, exitCode, counts, suspensions, locks, adopted, outputBytes,
                origin, cancellation, recovered, progress);
    }

    public ExecutionRecord withOwner(ProcessOwner o) {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, o, exitCode, counts, suspensions, locks, adopted,
                outputBytes, origin, cancellation, recovered, progress);
    }

    public ExecutionRecord withExit(Integer exit, Map<String, Long> c, long bytes) {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, owner, exit, c, suspensions, locks, adopted, bytes,
                origin, cancellation, recovered, progress);
    }

    public ExecutionRecord withSuspension() {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, owner, exitCode, counts, suspensions + 1, locks,
                adopted, outputBytes, origin, cancellation, recovered, progress);
    }

    public ExecutionRecord withLock() {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, owner, exitCode, counts, suspensions, locks + 1,
                adopted, outputBytes, origin, cancellation, recovered, progress);
    }

    public ExecutionRecord adoptedAfterRestart() {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, owner, exitCode, counts, suspensions, locks, true,
                outputBytes, origin, cancellation, recovered, progress);
    }

    public ExecutionRecord withOrigin(Origin o) {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, owner, exitCode, counts, suspensions, locks, adopted,
                outputBytes, o, cancellation, recovered, progress);
    }

    public ExecutionRecord withCancellation(Cancellation c) {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, owner, exitCode, counts, suspensions, locks, adopted,
                outputBytes, origin, c, recovered, progress);
    }

    /** A02: o desfecho veio do protocolo conferido depois de um reinício (o fim do processo não foi observado). */
    public ExecutionRecord recoveredFromProtocol() {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, owner, exitCode, counts, suspensions, locks, adopted,
                outputBytes, origin, cancellation, true, progress);
    }

    public ExecutionRecord withProgress(PartialProgress p) {
        return new ExecutionRecord(id, kind, packageName, fingerprint, state, code, created, started, ended, owner, exitCode, counts, suspensions, locks, adopted,
                outputBytes, origin, cancellation, recovered, p);
    }
}
