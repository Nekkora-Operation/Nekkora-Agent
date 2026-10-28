package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.StrictJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 * linhas, a última delas o marcador {@link #LIMIT_MARKER}). Retenção: as {@link #KEEP} execuções TERMINADAS mais
 * recentes; nada em andamento é apagado (a atividade da OBS-001, {@code atividade.jsonl}, fica na mesma pasta).
 */
public final class RunStore {
    public static final int KEEP = 50;
    public static final int MAX_EVENTS = 500;
    /** Última linha do {@code eventos.jsonl} quando o limite é atingido (OBS-001: a perda deixa de ser silenciosa). */
    public static final String LIMIT_MARKER = "EVENTS_LIMIT_REACHED";

    /** AGENT-PLATFORM-001: execuções terminadas com entrega pendente guardadas além de {@link #KEEP}, no máximo. */
    public static final int KEEP_PENDING_DELIVERY = 200;

    private final Path root;
    private final AtomicWriter writer;
    private volatile java.util.function.Predicate<ExecutionId> retained = id -> false;

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
            long lines = 0;
            if (Files.exists(f)) {
                try (Stream<String> s = Files.lines(f, StandardCharsets.UTF_8)) {
                    lines = s.count();
                }
            }
            if (lines >= MAX_EVENTS) return;
            if (lines == MAX_EVENTS - 1) {
                // a última linha possível vira o marcador do limite: quem lê sabe que eventos seguintes não foram gravados
                event = LIMIT_MARKER;
                fields = Map.of();
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

    /** O registro de uma execução, se existir e for legível. */
    public Optional<ExecutionRecord> record(ExecutionId id) {
        return load(dir(id).resolve("estado.json"));
    }

    /**
     * A pasta de saída anotada no início da execução ({@code saida.ref}), só quando é a pasta que o Agent criou para ela:
     * caminho absoluto cujo último nome é o próprio identificador da execução.
     */
    public Optional<Path> output(ExecutionId id) {
        try {
            Path ref = dir(id).resolve(ExecutionController.OUTPUT_REF);
            if (!Files.isRegularFile(ref, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(ref) > 4096) return Optional.empty();
            Path p = Path.of(Files.readString(ref, StandardCharsets.UTF_8).strip());
            if (!p.isAbsolute() || p.getFileName() == null || !p.getFileName().toString().equals(id.value()) || !p.normalize().equals(p)) return Optional.empty();
            return Optional.of(p);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Todas as execuções registradas, da mais nova para a mais antiga (registro ilegível fica de fora). */
    public List<ExecutionRecord> all() {
        return list().records();
    }

    /**
     * O histórico como a tela precisa: os registros legíveis (mais novos primeiro), quantos registros existem mas não
     * puderam ser lidos e se a própria pasta do histórico falhou — "nenhuma execução" nunca é confundido com "não
     * foi possível ler".
     */
    public record Listing(List<ExecutionRecord> records, int unreadable, boolean failed) {
        public Listing {
            records = List.copyOf(records);
        }

        public static Listing failure() {
            return new Listing(List.of(), 0, true);
        }
    }

    public Listing list() {
        List<ExecutionRecord> out = new ArrayList<>();
        int unreadable = 0;
        boolean failed = false;
        // só "não existe" confirmado é histórico vazio; sem poder determinar (acesso negado etc.) é falha
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return new Listing(out, 0, false);
        if (!Files.isDirectory(root)) return Listing.failure();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path d : ds) {
                if (!ExecutionId.valid(String.valueOf(d.getFileName()))) continue;
                Path f = d.resolve("estado.json");
                Optional<ExecutionRecord> r = load(f);
                if (r.isPresent()) out.add(r.get());
                else if (!Files.notExists(f, LinkOption.NOFOLLOW_LINKS)) unreadable++; // sem estado.json: execução sendo criada
            }
        } catch (IOException | RuntimeException e) {
            failed = true; // o que já foi lido continua valendo (como antes); a tela avisa que a lista pode estar parcial
        }
        out.sort(Comparator.comparing(ExecutionRecord::created).reversed());
        return new Listing(out, unreadable, failed);
    }

    /**
     * AGENT-PLATFORM-001: execuções com entrega pendente à Platform. A limpeza não as apaga (até
     * {@link #KEEP_PENDING_DELIVERY} além das {@link #KEEP}): nada é descartado antes da confirmação.
     */
    public void retention(java.util.function.Predicate<ExecutionId> pendingDelivery) {
        this.retained = pendingDelivery == null ? id -> false : pendingDelivery;
    }

    /**
     * Apaga as execuções terminadas mais antigas além de {@link #KEEP} (só pastas do Agent: cópia do pacote e registro). As
     * que têm entrega pendente à Platform ficam, até o limite {@link #KEEP_PENDING_DELIVERY}.
     */
    public void prune() {
        List<ExecutionRecord> done = all().stream().filter(r -> r.state().terminal()).toList();
        int spared = 0;
        for (int i = KEEP; i < done.size(); i++) {
            if (retained.test(done.get(i).id()) && spared++ < KEEP_PENDING_DELIVERY) continue;
            deleteTree(dir(done.get(i).id()));
        }
    }

    static Optional<ExecutionRecord> load(Path f) {
        try {
            Map<String, Object> m = StrictJson.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            if (!ExecutionRecord.SCHEMA.equals(m.get("schema")) || !ExecutionRecord.READABLE.contains(m.get("version"))) return Optional.empty();
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
                    Math.toIntExact((Long) m.get("locks")), Boolean.TRUE.equals(m.get("adopted")), (Long) m.get("outputBytes"), origin(m.get("origin")),
                    cancellation(m.get("cancellation")), Boolean.TRUE.equals(m.get("recovered")), progress(m.get("progress"))));
        } catch (IOException | StrictJson.SyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 1.1.0: campo ausente (registro 1.0.0) = origem desconhecida; valor fora do enum torna o registro ilegível. */
    private static ExecutionRecord.Origin origin(Object v) {
        return v instanceof Map<?, ?> o ? ExecutionRecord.Origin.valueOf((String) o.get("kind")) : ExecutionRecord.Origin.UNKNOWN;
    }

    private static ExecutionRecord.Cancellation cancellation(Object v) {
        if (!(v instanceof Map<?, ?> c)) return null;
        return new ExecutionRecord.Cancellation(Instant.parse((String) c.get("requested")), ExecutionRecord.CancelSource.valueOf((String) c.get("source")),
                ExecutionRecord.CancelOutcome.valueOf((String) c.get("outcome")));
    }

    private static ExecutionRecord.PartialProgress progress(Object v) {
        if (!(v instanceof Map<?, ?> p)) return null;
        Map<String, Long> tx = new LinkedHashMap<>();
        if (p.get("transactions") instanceof Map<?, ?> t) t.forEach((k, n) -> {
            if (n instanceof Long x && String.valueOf(k).matches("[A-Z_]{1,40}")) tx.put(String.valueOf(k), x);
        });
        Object problem = p.get("problem");
        return new ExecutionRecord.PartialProgress(Boolean.TRUE.equals(p.get("available")), tx, p.get("delivered") instanceof Long d ? d : 0,
                problem instanceof String s && s.matches("[A-Z_]{1,40}") ? s : null);
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
        b.append("  \"outputBytes\": ").append(r.outputBytes()).append(",\n");
        // 1.1.0
        b.append("  \"origin\": {\"kind\": ").append(StrictJson.quote(r.origin().name())).append("},\n");
        if (r.cancellation() != null) {
            ExecutionRecord.Cancellation c = r.cancellation();
            b.append("  \"cancellation\": {\"requested\": ").append(StrictJson.quote(c.requested().toString())).append(", \"source\": ")
                    .append(StrictJson.quote(c.source().name())).append(", \"outcome\": ").append(StrictJson.quote(c.outcome().name())).append("},\n");
        }
        if (r.progress() != null) {
            ExecutionRecord.PartialProgress p = r.progress();
            b.append("  \"progress\": {\"available\": ").append(p.available()).append(", \"transactions\": {");
            int k = 0;
            for (Map.Entry<String, Long> e : p.transactions().entrySet()) {
                b.append(k++ == 0 ? "" : ", ").append(StrictJson.quote(e.getKey())).append(": ").append(e.getValue());
            }
            b.append("}, \"delivered\": ").append(p.delivered()).append(", \"problem\": ").append(p.problem() == null ? "null" : StrictJson.quote(p.problem()))
                    .append("},\n");
        }
        b.append("  \"recovered\": ").append(r.recovered()).append("\n}\n");
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
