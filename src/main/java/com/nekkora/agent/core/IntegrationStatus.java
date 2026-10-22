package com.nekkora.agent.core;

/**
 * Estados de integração desta versão. São fixos: o Agent ainda não pareia, não se conecta à
 * Platform, não verifica licença nem recebe execuções da Platform (DESKTOP-001: a execução LOCAL
 * supervisionada é separada e não depende da Platform). Nenhum identificador oficial é exibido.
 */
public enum IntegrationStatus {
    PAIRING("Vínculo", "Não pareado", "NAO_PAREADO"),
    LINK("Agent, Worker e organização", "Não vinculado", "NAO_VINCULADO"),
    CONNECTION("Conexão", "Sem conexão com a Platform", "SEM_CONEXAO"),
    LICENSE("Licença", "Não verificada", "NAO_VERIFICADA"),
    EXECUTION("Execução pela Platform", "Indisponível nesta versão", "INDISPONIVEL");

    /** Explicação exibida junto aos estados. */
    public static final String EXPLANATION = "A integração com a Platform ainda não está disponível nesta versão: "
            + "o Agent não se conecta, não verifica licença e não recebe execuções.";

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
