package com.nekkora.agent.platform;

import com.nekkora.agent.exec.JsonReader;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AGENT-PYTHON-001 (Marco 2): registro DURÁVEL de cada pedido de execução recebido da Platform
 * ({@code <dados>/plataforma/pedidos/<runRequestId>.json}, gravação atômica). É o que garante que um pedido reentregue nunca
 * gera outra decisão nem outro processo:
 * <ol>
 *   <li>{@code RECEIVED}: gravado antes de qualquer decisão;</li>
 *   <li>{@code DECIDED}: a decisão (e, se aceita, o {@code agentExecutionId} RESERVADO) gravada ANTES do envio; reenviada até a
 *       resposta;</li>
 *   <li>{@code CONFIRMED}: a Platform confirmou a aceitação — só aqui o lançamento pode acontecer;</li>
 *   <li>{@code LAUNCHED}: o lançamento foi tentado (a pasta da execução existe; o resto é fato do supervisor);</li>
 *   <li>{@code CLOSED}: nada mais a fazer (recusa ou "não iniciado" confirmados, pedido vencido ou revogado, conflito).</li>
 * </ol>
 */
final class RunRequestStore {
    static final String DIR = "pedidos";
    static final String SCHEMA = "nekkora-agent/platform-run-request";
    static final String VERSION = "1.0.0";

    enum Stage { RECEIVED, DECIDED, CONFIRMED, LAUNCHED, CLOSED }

    /** Um pedido como esta máquina o conhece. {@code decision}: ACCEPTED, REFUSED ou NOT_STARTED. */
    record Request(UUID runRequestId, UUID agentId, UUID authorizationId, UUID automationVersionId, String sourceKind, String fingerprint, String rule,
                   Instant validUntil, Instant receivedAt, Stage stage, String decision, String reason, String executionId, String serverOutcome,
                   Instant updatedAt) {

        Request with(Stage s, String decision, String reason, String executionId, String serverOutcome, Instant now) {
            return new Request(runRequestId, agentId, authorizationId, automationVersionId, sourceKind, fingerprint, rule, validUntil, receivedAt, s, decision,
                    reason, executionId, serverOutcome, now);
        }
    }

    private final Path dir;
    private final AtomicWriter writer;

    RunRequestStore(Path platformDir, AtomicWriter writer) {
        this.dir = platformDir.resolve(DIR);
        this.writer = writer;
    }

    synchronized Optional<Request> get(UUID id) {
        Path f = dir.resolve(id + ".json");
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            return Optional.ofNullable(parse(Files.readString(f, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    synchronized List<Request> all() {
        List<Request> out = new ArrayList<>();
        java.io.File[] files = dir.toFile().listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        for (java.io.File f : files) {
            try {
                Request r = parse(Files.readString(f.toPath(), StandardCharsets.UTF_8));
                if (r != null) out.add(r);
            } catch (IOException e) {
                // ilegível agora: tentado de novo na próxima leitura
            }
        }
        out.sort(java.util.Comparator.comparing(Request::receivedAt));
        return out;
    }

    synchronized void save(Request r) throws IOException {
        Files.createDirectories(dir);
        String j = Json.obj().str("schema", SCHEMA).str("version", VERSION).str("runRequestId", r.runRequestId().toString()).str("agentId", r.agentId().toString())
                .str("authorizationId", r.authorizationId().toString()).str("automationVersionId", s(r.automationVersionId())).str("sourceKind", r.sourceKind())
                .str("packageFingerprint", r.fingerprint()).str("packageFingerprintRule", r.rule()).str("validUntil", r.validUntil().toString())
                .str("receivedAt", r.receivedAt().toString()).str("stage", r.stage().name()).str("decision", r.decision()).str("reason", r.reason())
                .str("agentExecutionId", r.executionId()).str("serverOutcome", r.serverOutcome()).str("updatedAt", r.updatedAt().toString()) + "\n";
        writer.write(dir.resolve(r.runRequestId() + ".json"), j.getBytes(StandardCharsets.UTF_8));
    }

    static Request parse(String text) {
        try {
            Map<String, Object> m = Json.parse(text);
            if (!SCHEMA.equals(m.get("schema")) || !VERSION.equals(m.get("version"))) return null;
            return new Request(Json.uuid(m, "runRequestId"), Json.uuid(m, "agentId"), Json.uuid(m, "authorizationId"), Json.uuid(m, "automationVersionId"),
                    Json.text(m, "sourceKind"), Json.text(m, "packageFingerprint"), Json.text(m, "packageFingerprintRule"), Json.instant(m, "validUntil"),
                    Json.instant(m, "receivedAt"), Stage.valueOf(Json.text(m, "stage")), Json.text(m, "decision"), Json.text(m, "reason"),
                    Json.text(m, "agentExecutionId"), Json.text(m, "serverOutcome"), Json.instant(m, "updatedAt"));
        } catch (JsonReader.Invalid | RuntimeException e) {
            return null;
        }
    }

    private static String s(UUID u) {
        return u == null ? null : u.toString();
    }
}
