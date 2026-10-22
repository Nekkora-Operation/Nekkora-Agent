package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.StrictJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Pastas das execuções em {@code <dados>/execucoes/<ExecutionId>/}: {@code estado.json} (gravação atômica a cada
 * transição) e {@code eventos.jsonl} (só nomes de evento, códigos, tempos e contagens; limitado a {@link #MAX_EVENTS}
 * linhas). Retenção: as {@link #KEEP} execuções TERMINADAS mais recentes; nada em andamento é apagado.
 */
public final class RunStore {
    public static final int KEEP = 50;
    public static final int MAX_EVENTS = 500;

    private final Path root;
    private final AtomicWriter writer;

    public RunStore(Path root, AtomicWriter writer) {
        this.root = root;
        this.writer = writer;
    }

    public Path root() {
        return root;
    }

    public Path dir(ExecutionId id) {
        return root.resolve(id.value());
    }

    public void save(ExecutionRecord r) throws ExecRefused {
        try {
            Files.createDirectories(dir(r.id()));
            writer.write(dir(r.id()).resolve("estado.json"), json(r).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ExecRefused(ErrorCode.EXEC_STORAGE_FAILED);
        }
    }

    /** Uma linha de evento (descartada além do limite: o estado continua sendo gravado). */
    public void event(ExecutionId id, Instant at, String event, Map<String, Object> fields) {
        Path f = dir(id).resolve("eventos.jsonl");
        try {
            Files.createDirectories(dir(id));
            if (Files.exists(f)) {
                try (Stream<String> s = Files.lines(f, StandardCharsets.UTF_8)) {
                    if (s.count() >= MAX_EVENTS) return;
                }
            }
            StringBuilder b = new StringBuilder("{\"at\": ").append(StrictJson.quote(at.toString())).append(", \"event\": ").append(StrictJson.quote(event));
            fields.forEach((k, v) -> b.append(", ").append(StrictJson.quote(k)).append(": ")
                    .append(v instanceof Number || v instanceof Boolean ? String.valueOf(v) : StrictJson.quote(String.valueOf(v))));
            b.append("}\n");
            Files.writeString(f, b, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // evento perdido não muda a execução: o estado.json é a fonte da verdade
        }
    }

    public List<String> events(ExecutionId id) {
        try {
            return Files.readAllLines(dir(id).resolve("eventos.jsonl"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Todas as execuções registradas, da mais nova para a mais antiga (registro ilegível fica de fora). */
    public List<ExecutionRecord> all() {
        List<ExecutionRecord> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path d : ds) {
                if (!ExecutionId.valid(String.valueOf(d.getFileName()))) continue;
                load(d.resolve("estado.json")).ifPresent(out::add);
            }
        } catch (IOException e) {
            return out;
        }
        out.sort(Comparator.comparing(ExecutionRecord::created).reversed());
        return out;
    }

    /** Apaga as execuções terminadas mais antigas além de {@link #KEEP} (só pastas do Agent: cópia do pacote e registro). */
    public void prune() {
        List<ExecutionRecord> done = all().stream().filter(r -> r.state().terminal()).toList();
        for (int i = KEEP; i < done.size(); i++) deleteTree(dir(done.get(i).id()));
    }

    static Optional<ExecutionRecord> load(Path f) {
        try {
            Map<String, Object> m = StrictJson.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            if (!ExecutionRecord.SCHEMA.equals(m.get("schema")) || !ExecutionRecord.VERSION.equals(m.get("version"))) return Optional.empty();
            ProcessOwner owner = null;
            if (m.get("process") instanceof Map<?, ?> p && p.get("pid") instanceof Long pid && p.get("start") instanceof Long start
                    && p.get("executableDigest") instanceof String exe && exe.matches("sha256:[0-9a-f]{64}")) {
                owner = new ProcessOwner(pid, start, exe);
            }
            Map<String, Long> counts = new LinkedHashMap<>();
            if (m.get("counts") instanceof Map<?, ?> c) c.forEach((k, v) -> {
                if (v instanceof Long n) counts.put(String.valueOf(k), n);
            });
            return Optional.of(new ExecutionRecord(new ExecutionId((String) m.get("id")), m.get("kind") instanceof String k ? ExecutionPackage.Kind.valueOf(k) : null,
                    (String) m.get("package"), (String) m.get("fingerprint"), ExecutionState.valueOf((String) m.get("state")), code(m.get("code")),
                    Instant.parse((String) m.get("created")), instant(m.get("started")), instant(m.get("ended")), owner,
                    m.get("exitCode") instanceof Long x ? Math.toIntExact(x) : null, counts, Math.toIntExact((Long) m.get("suspensions")),
                    Math.toIntExact((Long) m.get("locks")), Boolean.TRUE.equals(m.get("adopted")), (Long) m.get("outputBytes")));
        } catch (IOException | StrictJson.SyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static ErrorCode code(Object v) {
        if (!(v instanceof String s)) return null;
        for (ErrorCode c : ErrorCode.values()) if (c.code().equals(s)) return c;
        return null;
    }

    private static Instant instant(Object v) {
        return v instanceof String s ? Instant.parse(s) : null;
    }

    static String json(ExecutionRecord r) {
        StringBuilder b = new StringBuilder("{\n");
        field(b, "schema", ExecutionRecord.SCHEMA);
        field(b, "version", ExecutionRecord.VERSION);
        field(b, "id", r.id().value());
        field(b, "kind", r.kind() == null ? null : r.kind().name());
        field(b, "package", r.packageName());
        field(b, "fingerprint", r.fingerprint());
        field(b, "state", r.state().name());
        field(b, "code", r.code() == null ? null : r.code().code());
        field(b, "created", r.created().toString());
        field(b, "started", r.started() == null ? null : r.started().toString());
        field(b, "ended", r.ended() == null ? null : r.ended().toString());
        if (r.owner() != null) {
            b.append("  \"process\": {\"pid\": ").append(r.owner().pid()).append(", \"start\": ").append(r.owner().startMillis()).append(", \"executableDigest\": ")
                    .append(StrictJson.quote(r.owner().executableDigest())).append("},\n");
        }
        b.append("  \"exitCode\": ").append(r.exitCode() == null ? "null" : r.exitCode()).append(",\n");
        b.append("  \"counts\": {");
        int n = 0;
        for (Map.Entry<String, Long> e : r.counts().entrySet()) b.append(n++ == 0 ? "" : ", ").append(StrictJson.quote(e.getKey())).append(": ").append(e.getValue());
        b.append("},\n");
        b.append("  \"suspensions\": ").append(r.suspensions()).append(",\n");
        b.append("  \"locks\": ").append(r.locks()).append(",\n");
        b.append("  \"adopted\": ").append(r.adopted()).append(",\n");
        b.append("  \"outputBytes\": ").append(r.outputBytes()).append("\n}\n");
        return b.toString();
    }

    private static void field(StringBuilder b, String k, String v) {
        b.append("  ").append(StrictJson.quote(k)).append(": ").append(v == null ? "null" : StrictJson.quote(v)).append(",\n");
    }

    static void deleteTree(Path d) {
        if (!Files.exists(d)) return;
        try (Stream<Path> s = Files.walk(d)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            // fica para a próxima limpeza
        }
    }
}
