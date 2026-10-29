package com.nekkora.agent.storage;

import java.util.Objects;

/**
 * Configuração local versionada e mínima: tema e destinos dos atalhos. Não guarda senha, token,
 * organização nem configuração de execução. Os destinos são guardados como digitados; a validação
 * acontece em {@code ShortcutPolicy} antes de qualquer uso.
 *
 * @param themeId identificador do tema Nekkora (desconhecido ⇒ tema padrão na interface)
 * @param platformUrl endereço da Platform, ou {@code null}
 * @param studioExecutable caminho do executável do Studio, ou {@code null}
 */
public record AgentConfig(String themeId, String platformUrl, String studioExecutable) {

    public static final String FORMAT = "nekkora-agent/config";
    public static final int VERSION = 1;
    public static final String DEFAULT_THEME = "sakura-dark";

    public AgentConfig {
        Objects.requireNonNull(themeId);
        platformUrl = blankToNull(platformUrl);
        studioExecutable = blankToNull(studioExecutable);
    }

    public static AgentConfig defaults() {
        return new AgentConfig(DEFAULT_THEME, null, null);
    }

    public AgentConfig withTheme(String id) {
        return new AgentConfig(id, platformUrl, studioExecutable);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
