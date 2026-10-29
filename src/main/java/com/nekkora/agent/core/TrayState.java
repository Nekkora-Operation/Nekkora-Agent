package com.nekkora.agent.core;

/** Situação do ícone na bandeja do sistema. */
public enum TrayState {
    /** Ainda não instalado (janela em abertura). */
    PENDING("PENDENTE", null),
    ACTIVE("ATIVA", null),
    UNSUPPORTED("INDISPONIVEL", ErrorCode.TRAY_UNSUPPORTED),
    FAILED("FALHOU", ErrorCode.TRAY_FAILED),
    DISABLED("DESATIVADA", ErrorCode.TRAY_DISABLED);

    private final String diagnosticValue;
    private final ErrorCode code;

    TrayState(String diagnosticValue, ErrorCode code) {
        this.diagnosticValue = diagnosticValue;
        this.code = code;
    }

    public String diagnosticValue() {
        return diagnosticValue;
    }

    /** Código do problema, ou {@code null} quando ativa ou pendente. */
    public ErrorCode code() {
        return code;
    }

    /** {@code true} se fechar a janela pode recolhê-la para a bandeja. */
    public boolean canHideToTray() {
        return this == ACTIVE;
    }
}
