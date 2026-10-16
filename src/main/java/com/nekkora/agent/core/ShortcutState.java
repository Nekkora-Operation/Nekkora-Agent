package com.nekkora.agent.core;

/** Situação de um destino de atalho, sem o destino em si. */
public enum ShortcutState {
    NOT_CONFIGURED("NAO_CONFIGURADO"),
    VALID("VALIDO"),
    INVALID("INVALIDO");

    private final String diagnosticValue;

    ShortcutState(String diagnosticValue) {
        this.diagnosticValue = diagnosticValue;
    }

    public String diagnosticValue() {
        return diagnosticValue;
    }
}
