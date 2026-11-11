package com.nekkora.agent.platform;

import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.JsonReader;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * PLATFORM-AGENT-V2-002 T06: registro de cada desvínculo desta máquina ({@code <dados>/plataforma/desvinculos/<agentId>.json},
 * gravação atômica, SEM segredo). É gravado ANTES de o vínculo sair e guarda o que ficou para trás: as execuções cujo envio
 * não terminou (os arquivos de {@code entregas/} ficam onde estão e nunca são enviados por outro vínculo), os pedidos de
 * execução fechados e a resposta da Platform consultada antes do desvínculo. Nada aqui é descartado em silêncio.
 */
final class UnlinkLedger {
    static final String DIR = "desvinculos";
    static final String SCHEMA = "nekkora-agent/platform-unlink";
    static final String VERSION = "1.0.0";

    /** Uma execução deixada sem entrega: até onde a Platform confirmou e quantos eventos locais ficaram de fora. */
    record Undelivered(ExecutionId executionId, long settledThrough, long pendingEvents) {}

    /** Um desvínculo. {@code server}: a resposta da Platform consultada antes ({@link PlatformService.ServerView}). */
    record Entry(UUID agentId, UUID organizationId, UUID workerId, String origin, Instant unlinkedAt, String server, List<Undelivered> undelivered,
                 int runRequestsClosed, boolean intentDiscarded) {
        Entry {
            undelivered = List.copyOf(undelivered);
        }

        long undeliveredEvents() {
            return undelivered.stream().mapToLong(Undelivered::pendingEvents).sum();
        }
    }

    private final Path dir;
    private final AtomicWriter writer;

    UnlinkLedger(Path platformDir, AtomicWriter writer) {
        this.dir = platformDir.resolve(DIR);
        this.writer = writer;
    }

    synchronized void save(Entry e) throws IOException {
        List<String> items = new ArrayList<>();
        for (Undelivered u : e.undelivered()) {
            items.add(Json.obj().str("executionId", u.executionId().value()).num("settledThrough", u.settledThrough()).num("pendingEvents", u.pendingEvents())
                    .toString());
        }
        String j = Json.obj().str("schema", SCHEMA).str("version", VERSION).str("agentId", e.agentId().toString())
                .str("organizationId", e.organizationId() == null ? null : e.organizationId().toString())
                .str("workerId", e.workerId() == null ? null : e.workerId().toString()).str("origin", e.origin()).str("unlinkedAt", e.unlinkedAt().toString())
                .str("server", e.server()).num("undeliveredRuns", e.undelivered().size()).num("undeliveredEvents", e.undeliveredEvents())
                .raw("undelivered", Json.list(items)).num("runRequestsClosed", e.runRequestsClosed()).bool("intentDiscarded", e.intentDiscarded()) + "\n";
        Files.createDirectories(dir);
        writer.write(dir.resolve(e.agentId() + ".json"), j.getBytes(StandardCharsets.UTF_8));
    }

    /** Todos os desvínculos legíveis, por Agent (um ilegível fica de fora: a entrega dele continua "de outro vínculo"). */
    synchronized Map<UUID, Entry> all() {
        Map<UUID, Entry> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : ds) {
                parse(Files.readString(f, StandardCharsets.UTF_8)).ifPresent(e -> out.put(e.agentId(), e));
            }
        } catch (IOException | RuntimeException e) {
            // o que foi lido vale; o resto continua "de outro vínculo"
        }
        return out;
    }

    static Optional<Entry> parse(String text) {
        try {
            Map<String, Object> m = Json.parse(text);
            if (!SCHEMA.equals(m.get("schema")) || !VERSION.equals(m.get("version"))) return Optional.empty();
            UUID agent = Json.uuid(m, "agentId");
            Instant at = Json.instant(m, "unlinkedAt");
            if (agent == null || at == null) return Optional.empty();
            List<Undelivered> list = new ArrayList<>();
            List<?> items = Json.list(m, "undelivered");
            if (items != null) {
                for (Object o : items) {
                    Map<String, Object> u = Json.object(o);
                    String ex = u == null ? null : Json.text(u, "executionId");
                    if (ex == null || !ExecutionId.valid(ex)) continue;
                    Long settled = Json.number(u, "settledThrough");
                    Long pending = Json.number(u, "pendingEvents");
                    list.add(new Undelivered(new ExecutionId(ex), settled == null ? 0 : settled, pending == null ? 0 : pending));
                }
            }
            Long closed = Json.number(m, "runRequestsClosed");
            return Optional.of(new Entry(agent, Json.uuid(m, "organizationId"), Json.uuid(m, "workerId"), Json.text(m, "origin"), at, Json.text(m, "server"), list,
                    closed == null ? 0 : closed.intValue(), Boolean.TRUE.equals(Json.bool(m, "intentDiscarded"))));
        } catch (JsonReader.Invalid | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** As execuções registradas como não entregues em algum desvínculo. */
    static Set<ExecutionId> undelivered(Map<UUID, Entry> all) {
        Set<ExecutionId> out = new TreeSet<>(java.util.Comparator.comparing(ExecutionId::value));
        for (Entry e : all.values()) for (Undelivered u : e.undelivered()) out.add(u.executionId());
        return out;
    }
}
