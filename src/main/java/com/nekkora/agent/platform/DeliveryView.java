package com.nekkora.agent.platform;

import com.nekkora.agent.core.ErrorCode;

/**
 * Envio de UMA execução à Platform, separado do desfecho dela: "automação concluída" e "envio concluído" são coisas
 * diferentes (uma execução pode ter terminado há horas e ainda estar sendo entregue, e vice-versa).
 *
 * @param state situação do envio
 * @param settledThrough cursor de entrega confirmado (sec. 8.1)
 * @param storedThrough o que a Platform comprovadamente tem
 * @param declared quantos números foram declarados perdidos (histórico incompleto na Platform)
 * @param blocked motivo da parada, quando {@link State#BLOCKED}
 * @param blockedSequence evento em que parou, quando é um evento
 * @param reportConflict a Platform registrou um relatório do supervisor como contraditório
 */
public record DeliveryView(State state, long settledThrough, long storedThrough, long declared, ErrorCode blocked, Long blockedSequence,
                           boolean reportConflict) {

    public enum State {
        /** Execução só local (sem autorização da Platform escolhida). */
        NOT_LINKED,
        /** Vinculada, ainda não registrada na Platform. */
        WAITING_REGISTRATION,
        /** Registrada; eventos ou relatório ainda por entregar. */
        PENDING,
        /** Tudo entregue e confirmado. */
        DELIVERED,
        /** Parada à espera de decisão. */
        BLOCKED,
        /** Pertence a um vínculo anterior desta máquina: não é enviada pelo atual. */
        OTHER_LINK
    }

    public static DeliveryView notLinked() {
        return new DeliveryView(State.NOT_LINKED, 0, 0, 0, null, null, false);
    }
}
