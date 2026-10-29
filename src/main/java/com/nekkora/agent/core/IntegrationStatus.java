package com.nekkora.agent.core;

/**
 * Estados de integração que continuam FIXOS nesta versão: a licença não é verificada e a Platform não envia execuções à
 * máquina (despacho, cancelamento e agendamento remotos são da fase seguinte). O vínculo, a comunicação e os envios ao
 * canal da Platform (AGENT-PLATFORM-001) são dinâmicos e ficam no card "Platform" e no diagnóstico próprio.
 */
public enum IntegrationStatus {
    LICENSE("Licença", "Não verificada", "NAO_VERIFICADA"),
    EXECUTION("Execução pela Platform", "Indisponível nesta versão", "INDISPONIVEL");

    /** Explicação exibida junto aos estados. */
    public static final String EXPLANATION = "A licença ainda não é verificada e a Platform não envia execuções a esta máquina nesta versão. "
            + "Execuções locais autorizadas são registradas na Platform quando houver vínculo.";

    private final String label;
    private final String value;
    private final String diagnosticValue;

    IntegrationStatus(String label, String value, String diagnosticValue) {
        this.label = label;
        this.value = value;
        this.diagnosticValue = diagnosticValue;
    }

    public String label() {
        return label;
    }

    public String value() {
        return value;
    }

    public String diagnosticValue() {
        return diagnosticValue;
    }
}
