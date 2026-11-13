package com.nekkora.agent.pack;

/** AGENT-PACOTES-001: acesso dos testes de outros pacotes ao ponto de teste do empacotador (antes da troca de nome). */
public final class PackagerHooks {
    private PackagerHooks() {}

    public static void beforeMove(Runnable r) {
        Packager.beforeMove = r;
    }
}
