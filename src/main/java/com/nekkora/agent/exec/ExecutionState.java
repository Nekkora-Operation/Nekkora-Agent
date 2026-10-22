package com.nekkora.agent.exec;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ciclo de vida de uma execução local supervisionada. As transições são fechadas: qualquer outra é recusada
 * ({@link #canMoveTo}). {@link #SUCCEEDED} só vem de um protocolo estruturado que confirme o resultado; código de saída 0
 * sozinho não prova sucesso de negócio ({@link #OUTCOME_UNKNOWN}).
 */
public enum ExecutionState {
    /** Pedido aceito, ainda não lançado. */
    READY("Pronta para iniciar"),
    /** Conferências finais e lançamento do processo. */
    STARTING("Iniciando"),
    /** Processo lançado e supervisionado. */
    RUNNING("Em andamento"),
    SUCCEEDED("Concluída com sucesso"),
    FAILED("Falhou"),
    CANCELLED("Cancelada"),
    TIMED_OUT("Tempo esgotado"),
    /** Nada foi iniciado: uma pré-condição falhou (sessão, pacote, aprovação, ambiente). */
    BLOCKED("Bloqueada"),
    /** O processo terminou (ou foi perdido) sem prova do resultado: nada é presumido e nada é repetido. */
    OUTCOME_UNKNOWN("Resultado desconhecido");

    private final String label;

    ExecutionState(String label) {
        this.label = label;
    }

    /** Texto para a interface. */
    public String label() {
        return label;
    }

    public boolean terminal() {
        return this != READY && this != STARTING && this != RUNNING;
    }

    /** Transições permitidas a partir deste estado. */
    public Set<ExecutionState> next() {
        return switch (this) {
            case READY -> EnumSet.of(STARTING, BLOCKED, CANCELLED);
            // OUTCOME_UNKNOWN: o Agent caiu entre lançar e registrar o processo — pode ter começado; nada é presumido
            case STARTING -> EnumSet.of(RUNNING, BLOCKED, FAILED, CANCELLED, OUTCOME_UNKNOWN);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, OUTCOME_UNKNOWN);
            default -> EnumSet.noneOf(ExecutionState.class);
        };
    }

    public boolean canMoveTo(ExecutionState target) {
        return next().contains(target);
    }
}
