package com.nekkora.agent.core;

import java.util.Map;
import java.util.Optional;

/**
 * Nome da máquina obtido do ambiente local ({@code COMPUTERNAME} no Windows, {@code HOSTNAME} nos
 * demais). Sem consulta de rede nem DNS. Ausente ou inválido: vazio — a interface mostra
 * "Não disponível" e nada é inventado.
 */
public final class MachineName {
    private static final int MAX_LENGTH = 255;

    private MachineName() {}

    public static Optional<String> resolve(Map<String, String> env) {
        for (String key : new String[] {"COMPUTERNAME", "HOSTNAME"}) {
            String v = env.get(key);
            if (v == null) continue;
            v = v.strip();
            if (!v.isEmpty() && v.length() <= MAX_LENGTH && v.chars().noneMatch(Character::isISOControl)) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }
}
