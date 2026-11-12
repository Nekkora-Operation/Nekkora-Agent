package com.nekkora.agent.platform;

import com.nekkora.agent.exec.JsonReader;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PLATFORM-AGENT-V2-004 T13: o pedido de desvínculo confirmado pela pessoa e ainda NÃO concluído na Platform
 * ({@code <dados>/plataforma/desvinculo-pendente.json}, gravação atômica, SEM segredo). Gravado ANTES do primeiro envio,
 * com o {@code operationId} que toda repetição reusa e a referência ao vínculo original (organização, Agent, Worker,
 * credencial). Enquanto existe:
 * <ul>
 *   <li>nada novo começa pelo vínculo antigo: a tela, os pedidos da Platform e os lançadores externos (que leem este arquivo
 *       pelo {@link LocalGate}) recusam;</li>
 *   <li>só a operação de desvínculo sai pela rede; a credencial (guarda protegida) fica até a conclusão, porque é a única
 *       prova da máquina para concluir ou reconciliar a operação;</li>
 *   <li>um novo pareamento é recusado (há um só vínculo por instalação; a operação antiga nunca usa uma credencial nova).</li>
 * </ul>
 * Sobrevive a reinício. Apagado só na conclusão (confirmação da Platform, vínculo já encerrado lá, ou Platform sem suporte).
 */
final class UnlinkRequestStore {
    static final String FILE = "desvinculo-pendente.json";
    static final String SCHEMA = "nekkora-agent/platform-unlink-request";
    static final String VERSION = "1.0.0";
    /** Limite do contrato para pedidos aceitos e iniciados informados no desvínculo. */
    static final int MAX_LAUNCHED = 50;

    /**
     * O pedido. {@code lastResult}: o último resultado não conclusivo (código curto, sem dados do servidor), ou {@code null}
     * antes da primeira tentativa.
     */
    record Request(UUID operationId, String origin, UUID organizationId, UUID agentId, UUID workerId, UUID credentialId, Instant confirmedAt,
                   List<UUID> launchedRunRequests, int attempts, Instant lastAttemptAt, String lastResult) {
        Request {
            launchedRunRequests = List.copyOf(launchedRunRequests);
        }

        Request attempted(Instant at, String result) {
            return new Request(operationId, origin, organizationId, agentId, workerId, credentialId, confirmedAt, launchedRunRequests, attempts + 1, at,
                    result);
        }
    }

    private final Path file;
    private final AtomicWriter writer;

    UnlinkRequestStore(Path platformDir, AtomicWriter writer) {
        this.file = platformDir.resolve(FILE);
        this.writer = writer;
    }

    static Path file(Path platformDir) {
        return platformDir.resolve(FILE);
    }

    synchronized void save(Request r) throws IOException {
        List<String> launched = new ArrayList<>();
        for (UUID u : r.launchedRunRequests()) launched.add("\"" + u + "\"");
        String j = Json.obj().str("schema", SCHEMA).str("version", VERSION).str("operationId", r.operationId().toString()).str("origin", r.origin())
                .str("organizationId", r.organizationId().toString()).str("agentId", r.agentId().toString())
                .str("workerId", r.workerId() == null ? null : r.workerId().toString()).str("credentialId", r.credentialId().toString())
                .str("confirmedAt", r.confirmedAt().toString()).raw("launchedRunRequests", "[" + String.join(",", launched) + "]").num("attempts", r.attempts())
                .str("lastAttemptAt", r.lastAttemptAt() == null ? null : r.lastAttemptAt().toString()).str("lastResult", r.lastResult()) + "\n";
        Files.createDirectories(file.getParent());
        writer.write(file, j.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * O pedido pendente. Um arquivo que EXISTE mas não se lê também conta como pendente ({@link #present}): na dúvida, o
     * vínculo antigo continua bloqueado.
     */
    synchronized Optional<Request> load() {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Há um pedido (legível ou não): o vínculo antigo não inicia nada. Leitura de outro processo (lançador) também. */
    static boolean present(Path platformDir) {
        return Files.exists(file(platformDir));
    }

    synchronized void delete() throws IOException {
        Files.deleteIfExists(file);
    }

    static Optional<Request> parse(String text) {
        try {
            Map<String, Object> m = Json.parse(text);
            if (!SCHEMA.equals(m.get("schema")) || !VERSION.equals(m.get("version"))) return Optional.empty();
            UUID op = Json.uuid(m, "operationId");
            UUID org = Json.uuid(m, "organizationId");
            UUID agent = Json.uuid(m, "agentId");
            UUID cred = Json.uuid(m, "credentialId");
            Instant at = Json.instant(m, "confirmedAt");
            String origin = Json.text(m, "origin");
            if (op == null || org == null || agent == null || cred == null || at == null || origin == null) return Optional.empty();
            List<UUID> launched = new ArrayList<>();
            List<?> items = Json.list(m, "launchedRunRequests");
            if (items != null) {
                for (Object o : items) {
                    if (!(o instanceof String s)) return Optional.empty();
                    launched.add(UUID.fromString(s));
                }
            }
            if (launched.size() > MAX_LAUNCHED) return Optional.empty();
            Long attempts = Json.number(m, "attempts");
            return Optional.of(new Request(op, origin, org, agent, Json.uuid(m, "workerId"), cred, at, launched, attempts == null ? 0 : attempts.intValue(),
                    Json.instant(m, "lastAttemptAt"), Json.text(m, "lastResult")));
        } catch (JsonReader.Invalid | RuntimeException e) {
            return Optional.empty();
        }
    }
}
