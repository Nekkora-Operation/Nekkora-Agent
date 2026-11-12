package com.nekkora.agent.core;

import java.util.ServiceLoader;

/**
 * Pontos nomeados onde um artefato EXCLUSIVO DE TESTE pode provocar uma falha (AGENT-PYTHON-001). A distribuição normal não
 * traz nenhuma implementação: {@link #reached} devolve {@code false} e nada acontece. O código que provoca a falha (derrubar o
 * Agent, descartar uma resposta) não existe aqui; ele mora em {@code tools/lab-faults/}, vira o JAR separado
 * {@code nekkora-agent-lab-falhas.jar} e só entra numa cópia de laboratório, acrescentado ao classpath pelo roteiro do
 * laboratório. A conferência da distribuição recusa esse JAR.
 */
public final class FaultPoints {
    /** Os pontos, na ordem em que o Agent passa por eles. */
    public enum Point {
        /**
         * Execução: o registro STARTING já foi gravado e o processo ainda NÃO foi criado. Ainda não houve cópia do pacote nem
         * {@code ProcessBuilder.start}. Uma queda aqui é a fronteira do início: há rastro, e não há processo.
         */
        STARTING_SAVED_BEFORE_PROCESS,
        /**
         * Pedido da Platform: a resposta da decisão chegou com sucesso, mas ainda não foi aplicada localmente. Devolver
         * {@code true} faz o Agent tratá-la como perdida.
         */
        DECISION_RESPONSE_RECEIVED,
        /**
         * PLATFORM-AGENT-V2-004 T13: a Platform respondeu ao desvínculo (o commit já aconteceu lá), mas a resposta ainda não
         * foi aplicada aqui. Devolver {@code true} faz o Agent tratá-la como perdida (sem resposta): a reconciliação repete a
         * mesma operação.
         */
        UNLINK_RESPONSE_RECEIVED
    }

    /** Implementação de teste, descoberta por {@link ServiceLoader} (só existe no JAR de laboratório). */
    public interface Hook {
        /** @return {@code true} quando a falha acontece neste ponto (só faz sentido em {@link Point#DECISION_RESPONSE_RECEIVED}). */
        boolean reached(Point point);
    }

    private static final Hook NONE = point -> false;
    private static volatile Hook hook;

    private FaultPoints() {}

    public static boolean reached(Point point) {
        return hook().reached(point);
    }

    private static Hook hook() {
        Hook h = hook;
        if (h == null) {
            h = ServiceLoader.load(Hook.class, FaultPoints.class.getClassLoader()).findFirst().orElse(NONE);
            hook = h;
        }
        return h;
    }

    /** Só testes unitários: substitui a implementação ({@code null} volta à descoberta normal). */
    public static void replaceForTests(Hook h) {
        hook = h;
    }
}
