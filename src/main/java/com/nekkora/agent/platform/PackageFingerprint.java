package com.nekkora.agent.platform;

import java.util.Optional;

/**
 * Impressão do pacote no canal (contrato, sec. 6.4): é a que o Agent JÁ calcula e aprova
 * ({@code ExecutionPackage.fingerprint}, regra {@code nekkora-agent/package-fingerprint/1}); aqui só muda a forma — o
 * canal leva os 64 hex sem o prefixo {@code sha256:}, sempre acompanhados da regra. Nunca é o hash dos bytes de um
 * artefato distribuído ({@code artifact.sha256}): as duas medidas não se comparam.
 */
public final class PackageFingerprint {
    public static final String RULE = "nekkora-agent/package-fingerprint/1";

    private PackageFingerprint() {}

    /** {@code sha256:<64 hex>} (forma do Agent) → 64 hex (forma do canal); outra forma: vazio. */
    public static Optional<String> wire(String agentFingerprint) {
        if (agentFingerprint == null || !agentFingerprint.matches("sha256:[0-9a-f]{64}")) return Optional.empty();
        return Optional.of(agentFingerprint.substring("sha256:".length()));
    }
}
