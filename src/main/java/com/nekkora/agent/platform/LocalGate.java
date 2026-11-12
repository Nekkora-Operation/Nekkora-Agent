package com.nekkora.agent.platform;

import com.nekkora.agent.exec.JsonReader;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.StrictJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AGENT-PYTHON-001: o que o LANÇADOR (outro processo, sem rede) pode saber do vínculo com a Platform, SÓ POR LEITURA dos
 * arquivos que o Agent mantém em {@code <dados>/plataforma}: o Agent pareado ({@code vinculo.json}, sem segredo) e as
 * autorizações guardadas ({@code autorizacoes.json}). Nenhuma chamada de rede, nenhuma gravação nos arquivos do transporte,
 * nenhum segredo lido. Sem vínculo, com credencial recusada ou sem autorização no prazo: vazio (nada é presumido).
 * <p>
 * Também define o PEDIDO DE VÍNCULO ({@code plataforma.json}) que o lançador grava na pasta da execução: é por ele que o
 * Agent cria a entrega (só o processo do Agent escreve {@code plataforma/entregas}).
 */
public final class LocalGate {
    public static final String LINK_REQUEST = "plataforma.json";
    static final String LINK_REQUEST_SCHEMA = "nekkora-agent/platform-link-request";
    static final String LINK_REQUEST_VERSION = "1.0.0";

    private LocalGate() {}

    /** Pedido de vínculo de uma execução à Platform. {@code fingerprint} e {@code runRequestId}: nulos no monitoramento. */
    public record LinkRequest(UUID agentId, UUID authorizationId, String sourceKind, String fingerprint, UUID runRequestId, Instant at) {}

    /** A autorização de monitoramento usável agora para a automação, pelo Agent pareado nesta máquina. */
    public static Optional<LinkRequest> monitored(Path platformDir, UUID automationId, Instant now) {
        Optional<UUID> agent = pairedAgent(platformDir);
        if (agent.isEmpty()) return Optional.empty();
        AuthorizationCache cache = new AuthorizationCache(platformDir, (t, d) -> {
            throw new IOException("somente leitura");
        });
        cache.load(agent.get());
        return cache.monitoredFor(automationId, now)
                .map(z -> new LinkRequest(agent.get(), z.authorizationId(), AuthorizationCache.EXTERNAL_MONITORED, null, null, now));
    }

    /**
     * O Agent do vínculo confirmado e não recusado ({@code vinculo.json}); nunca lê a guarda de segredos. T13: com um
     * desvínculo confirmado e não concluído ({@link UnlinkRequestStore}), nenhum (o vínculo antigo não inicia nada).
     */
    static Optional<UUID> pairedAgent(Path platformDir) {
        if (UnlinkRequestStore.present(platformDir)) return Optional.empty();
        try {
            Map<String, Object> m = StrictJson.parseObject(Files.readString(platformDir.resolve(CredentialVault.FILE), StandardCharsets.UTF_8));
            if (!CredentialVault.SCHEMA.equals(m.get("schema")) || !CredentialVault.VERSION.equals(m.get("version"))) return Optional.empty();
            if (Boolean.TRUE.equals(m.get("refused")) || !(m.get("agentId") instanceof String a)) return Optional.empty();
            return Optional.of(UUID.fromString(a));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException | StrictJson.SyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Grava o pedido de vínculo na pasta da execução (gravação atômica). */
    public static void writeLinkRequest(Path runDir, LinkRequest r, AtomicWriter writer) throws IOException {
        String j = Json.obj().str("schema", LINK_REQUEST_SCHEMA).str("version", LINK_REQUEST_VERSION).str("agentId", r.agentId().toString())
                .str("authorizationId", r.authorizationId().toString()).str("sourceKind", r.sourceKind()).str("packageFingerprint", r.fingerprint())
                .str("runRequestId", r.runRequestId() == null ? null : r.runRequestId().toString()).str("at", r.at().toString()) + "\n";
        Files.createDirectories(runDir);
        writer.write(runDir.resolve(LINK_REQUEST), j.getBytes(StandardCharsets.UTF_8));
    }

    /** Lê o pedido de vínculo de uma execução; ilegível ou fora do formato: vazio. */
    public static Optional<LinkRequest> readLinkRequest(Path runDir) {
        Path f = runDir.resolve(LINK_REQUEST);
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > 4096) return Optional.empty();
            Map<String, Object> m = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            if (!LINK_REQUEST_SCHEMA.equals(m.get("schema")) || !LINK_REQUEST_VERSION.equals(m.get("version"))) return Optional.empty();
            UUID agent = Json.uuid(m, "agentId");
            UUID auth = Json.uuid(m, "authorizationId");
            String kind = Json.text(m, "sourceKind");
            String fp = Json.text(m, "packageFingerprint");
            if (agent == null || auth == null || kind == null) return Optional.empty();
            if (AuthorizationCache.EXTERNAL_MONITORED.equals(kind) != (fp == null)) return Optional.empty();
            if (fp != null && !fp.matches("[0-9a-f]{64}")) return Optional.empty();
            return Optional.of(new LinkRequest(agent, auth, kind, fp, Json.uuid(m, "runRequestId"), Json.instant(m, "at")));
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            return Optional.empty();
        }
    }
}
