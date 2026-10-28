package com.nekkora.agent.platform;

import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.JsonReader;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
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
 * Progresso da entrega de cada execução vinculada a uma autorização ({@code entregas/<ExecutionId>.json}, contrato
 * sec. 12.4). É o ÚNICO estado próprio do transporte: os fatos continuam onde o supervisor e a coleta os gravaram
 * ({@code estado.json} e {@code atividade.jsonl}); aqui ficam só os identificadores da Platform, os cursores
 * confirmados por ACK, as faixas declaradas perdidas, a posição já liquidada no histórico local e um bloqueio, se houver.
 * Gravação atômica; o progresso só avança DEPOIS de um ACK validado.
 */
final class DeliveryStore {
    static final String DIR = "entregas";
    static final String INTENT = "intencao.json";
    static final String SCHEMA = "nekkora-agent/platform-delivery";
    static final String VERSION = "1.0.0";
    /** Execuções pendentes de entrega guardadas no máximo (acima disso, as mais antigas deixam de ser protegidas da retenção). */
    static final int MAX_PENDING_RUNS = 200;

    record Range(long from, long to) {
        Range {
            if (from < 1 || to < from) throw new IllegalArgumentException("faixa");
        }

        long size() {
            return to - from + 1;
        }
    }

    /**
     * Uma entrega. {@code runId} nulo: ainda não registrada. {@code reported*}: o último relatório do supervisor aceito
     * ({@code APPLIED}, {@code UNCHANGED} ou {@code STALE}). {@code readOffset}/{@code readSequence}: fim, no
     * {@code atividade.jsonl}, da linha de sequência {@code readSequence} ({@code ≤ settledThrough}) — de onde a próxima
     * leitura parte. {@code blockedCode}: a entrega parou e exige decisão (com a sequência, quando é um evento).
     */
    record Delivery(ExecutionId executionId, UUID agentId, UUID authorizationId, String fingerprint, Instant linkedAt, UUID runId, String reportedState,
                    String reportedDigest, boolean reportFinal, boolean reportConflict, long storedThrough, long settledThrough, long maxSent, long readOffset,
                    long readSequence, List<Range> declared, long contentConflicts, Long blockedSequence, String blockedCode, boolean complete,
                    Instant updatedAt) {

        Delivery {
            declared = List.copyOf(declared);
        }

        static Delivery linked(ExecutionId id, UUID agent, UUID authorization, String fingerprint, Instant now) {
            return new Delivery(id, agent, authorization, fingerprint, now, null, null, null, false, false, 0, 0, 0, 0, 0, List.of(), 0, null, null, false, now);
        }

        boolean blocked() {
            return blockedCode != null;
        }

        long declaredCount() {
            return declared.stream().mapToLong(Range::size).sum();
        }

        Delivery registered(UUID run, String state, String digest, boolean fin, Instant now) {
            return new Delivery(executionId, agentId, authorizationId, fingerprint, linkedAt, run, state, digest, fin, reportConflict, storedThrough,
                    settledThrough, maxSent, readOffset, readSequence, declared, contentConflicts, blockedSequence, blockedCode, complete, now);
        }

        Delivery reported(String state, String digest, boolean fin, boolean conflict, Instant now) {
            return new Delivery(executionId, agentId, authorizationId, fingerprint, linkedAt, runId, state, digest, fin, conflict, storedThrough, settledThrough,
                    maxSent, readOffset, readSequence, declared, contentConflicts, blockedSequence, blockedCode, complete, now);
        }

        Delivery declaring(List<Range> ranges, Instant now) {
            return new Delivery(executionId, agentId, authorizationId, fingerprint, linkedAt, runId, reportedState, reportedDigest, reportFinal, reportConflict,
                    storedThrough, settledThrough, maxSent, readOffset, readSequence, ranges, contentConflicts, blockedSequence, blockedCode, complete, now);
        }

        Delivery acked(long stored, long settled, long sent, long offset, long offsetSeq, long conflicts, Instant now) {
            return new Delivery(executionId, agentId, authorizationId, fingerprint, linkedAt, runId, reportedState, reportedDigest, reportFinal, reportConflict,
                    stored, settled, sent, offset, offsetSeq, declared, conflicts, blockedSequence, blockedCode, complete, now);
        }

        Delivery block(Long sequence, String code, Instant now) {
            return new Delivery(executionId, agentId, authorizationId, fingerprint, linkedAt, runId, reportedState, reportedDigest, reportFinal, reportConflict,
                    storedThrough, settledThrough, maxSent, readOffset, readSequence, declared, contentConflicts, sequence, code, complete, now);
        }

        Delivery unblock(Instant now) {
            return block(null, null, now);
        }

        Delivery done(Instant now) {
            return new Delivery(executionId, agentId, authorizationId, fingerprint, linkedAt, runId, reportedState, reportedDigest, reportFinal, reportConflict,
                    storedThrough, settledThrough, maxSent, readOffset, readSequence, declared, contentConflicts, blockedSequence, blockedCode, true, now);
        }

        Delivery rewound(Instant now) {
            return new Delivery(executionId, agentId, authorizationId, fingerprint, linkedAt, runId, reportedState, reportedDigest, reportFinal, reportConflict,
                    storedThrough, settledThrough, maxSent, 0, 0, declared, contentConflicts, blockedSequence, blockedCode, complete, now);
        }
    }

    /** Escolha feita pela pessoa antes de iniciar (gravada antes do início; vira vínculo quando a execução existir). */
    record Intent(UUID agentId, UUID authorizationId, String fingerprint, Instant at) {}

    private final Path dir;
    private final AtomicWriter writer;

    DeliveryStore(Path platformDir, AtomicWriter writer) {
        this.dir = platformDir.resolve(DIR);
        this.writer = writer;
    }

    Path dir() {
        return dir;
    }

    synchronized void save(Delivery d) throws IOException {
        Files.createDirectories(dir);
        writer.write(dir.resolve(d.executionId().value() + ".json"), (json(d) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    synchronized Optional<Delivery> get(ExecutionId id) {
        try {
            return Optional.ofNullable(parse(Files.readString(dir.resolve(id.value() + ".json"), StandardCharsets.UTF_8)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Todas as entregas legíveis (uma ilegível fica de fora e é contada). */
    synchronized Listing list() {
        List<Delivery> out = new ArrayList<>();
        int unreadable = 0;
        if (!Files.isDirectory(dir)) return new Listing(out, 0);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "ex-*.json")) {
            for (Path f : ds) {
                String n = f.getFileName().toString();
                if (!ExecutionId.valid(n.substring(0, n.length() - ".json".length()))) continue;
                try {
                    Delivery d = parse(Files.readString(f, StandardCharsets.UTF_8));
                    if (d != null) out.add(d);
                    else unreadable++;
                } catch (IOException | RuntimeException e) {
                    unreadable++;
                }
            }
        } catch (IOException e) {
            unreadable++;
        }
        out.sort(java.util.Comparator.comparing(Delivery::linkedAt));
        return new Listing(out, unreadable);
    }

    record Listing(List<Delivery> deliveries, int unreadable) {}

    synchronized void saveIntent(Intent i) throws IOException {
        Files.createDirectories(dir);
        String j = Json.obj().str("agentId", i.agentId().toString()).str("authorizationId", i.authorizationId().toString()).str("fingerprint", i.fingerprint())
                .str("at", i.at().toString()) + "\n";
        writer.write(dir.resolve(INTENT), j.getBytes(StandardCharsets.UTF_8));
    }

    synchronized Optional<Intent> intent() {
        try {
            Map<String, Object> m = Json.parse(Files.readString(dir.resolve(INTENT), StandardCharsets.UTF_8));
            String fp = Json.text(m, "fingerprint");
            if (fp == null || !fp.matches("[0-9a-f]{64}")) return Optional.empty();
            return Optional.of(new Intent(Json.uuid(m, "agentId"), Json.uuid(m, "authorizationId"), fp, Json.instant(m, "at")));
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            return Optional.empty();
        }
    }

    synchronized void clearIntent() throws IOException {
        Files.deleteIfExists(dir.resolve(INTENT));
    }

    static String json(Delivery d) {
        List<String> ranges = new ArrayList<>();
        for (Range r : d.declared()) ranges.add(Json.obj().num("from", r.from()).num("to", r.to()).toString());
        return Json.obj().str("schema", SCHEMA).str("version", VERSION).str("executionId", d.executionId().value()).str("agentId", d.agentId().toString())
                .str("authorizationId", d.authorizationId().toString()).str("fingerprint", d.fingerprint()).str("linkedAt", d.linkedAt().toString())
                .str("runId", d.runId() == null ? null : d.runId().toString()).str("reportedState", d.reportedState()).str("reportedDigest", d.reportedDigest())
                .bool("reportFinal", d.reportFinal()).bool("reportConflict", d.reportConflict()).num("storedThrough", d.storedThrough())
                .num("settledThrough", d.settledThrough()).num("maxSent", d.maxSent()).num("readOffset", d.readOffset()).num("readSequence", d.readSequence())
                .raw("declared", Json.list(ranges)).num("contentConflicts", d.contentConflicts()).num("blockedSequence", d.blockedSequence())
                .str("blockedCode", d.blockedCode()).bool("complete", d.complete()).str("updatedAt", d.updatedAt().toString()).toString();
    }

    static Delivery parse(String text) {
        try {
            Map<String, Object> m = Json.parse(text);
            if (!SCHEMA.equals(m.get("schema")) || !VERSION.equals(m.get("version"))) return null;
            List<Range> ranges = new ArrayList<>();
            for (Object o : Json.list(m, "declared")) {
                Map<String, Object> r = Json.object(o);
                ranges.add(new Range(Json.number(r, "from"), Json.number(r, "to")));
            }
            return new Delivery(new ExecutionId(Json.text(m, "executionId")), Json.uuid(m, "agentId"), Json.uuid(m, "authorizationId"), Json.text(m, "fingerprint"),
                    Json.instant(m, "linkedAt"), Json.uuid(m, "runId"), Json.text(m, "reportedState"), Json.text(m, "reportedDigest"),
                    Boolean.TRUE.equals(Json.bool(m, "reportFinal")), Boolean.TRUE.equals(Json.bool(m, "reportConflict")), Json.number(m, "storedThrough"),
                    Json.number(m, "settledThrough"), Json.number(m, "maxSent"), Json.number(m, "readOffset"), Json.number(m, "readSequence"), ranges,
                    Json.number(m, "contentConflicts"), Json.number(m, "blockedSequence"), Json.text(m, "blockedCode"), Boolean.TRUE.equals(Json.bool(m, "complete")),
                    Json.instant(m, "updatedAt"));
        } catch (JsonReader.Invalid | RuntimeException e) {
            return null;
        }
    }
}
