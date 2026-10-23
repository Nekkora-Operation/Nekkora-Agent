package com.nekkora.agent.obs;

import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.exec.ProcessOwner;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.StrictJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Execuções fabricadas (registro + eventos do supervisor + eventos do Framework) para os testes da OBS-001. */
final class ObsFixtures {
    private ObsFixtures() {}

    /** Valores sintéticos que NUNCA podem aparecer na atividade, no histórico, na tela nem na exportação. */
    static final List<String> SENTINELS = List.of("123.456.789-09", "12345678909", "12.345.678/0001-95", "maria.sigilosa@cliente.example",
            "tok_SIGILOSO_OBS_9Z", "Senha#Sigilosa9Z", "ClienteSigiloso9Z", "VALOR-EXCEL-9Z-4471", "token=abc9Z", "cliente.example/api",
            "Traceback (most recent call last)", "SENTINELA-VALOR-DA-AUTOMACAO-7Q2");

    /** Relógio ajustável. */
    static final class TestClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-22T12:00:00Z"));

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void plusMillis(long ms) {
            now.updateAndGet(t -> t.plusMillis(ms));
        }
    }

    /** Uma execução como o supervisor deixaria: registro, eventos e (Studio) a pasta de saída anotada. */
    record Run(ExecutionId id, Path output, RunStore store) {
        Path framework() {
            return output.resolve("execucao").resolve("events.jsonl");
        }

        ExecutionRecord record() {
            return store.record(id).orElseThrow();
        }

        void finish(ExecutionState state, Instant at) throws Exception {
            ExecutionRecord r = record().moveTo(state, null, at);
            store.save(r);
            store.event(id, at, state.name(), Map.of("exit", 0, "protocol", true, "output_bytes", 198));
        }

        void frameworkLines(List<String> lines) throws IOException {
            Files.createDirectories(framework().getParent());
            Files.writeString(framework(), String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }

        void frameworkRaw(String text) throws IOException {
            Files.createDirectories(framework().getParent());
            Files.writeString(framework(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    static RunStore store(Path agent) {
        return new RunStore(agent.resolve("execucoes"), AtomicWriter.DEFAULT);
    }

    /** Execução em andamento (RUNNING) com dono fictício, criada em {@code created}. */
    static Run running(RunStore store, Path saida, ExecutionPackage.Kind kind, Instant created) throws Exception {
        ExecutionId id = ExecutionId.random();
        ExecutionRecord r = new ExecutionRecord(id, kind, "pacote abc", "sha256:" + "a".repeat(64), ExecutionState.READY, null, created, null, null, null, null,
                Map.of(), 0, 0, false, 0);
        r = r.moveTo(ExecutionState.STARTING, null, created);
        store.save(r);
        store.event(id, created, "STARTING", Map.of("kind", kind.name(), "mode", "LIST", "session", 1));
        Path out = saida.resolve(id.value());
        Files.createDirectories(out);
        Files.writeString(store.dir(id).resolve("saida.ref"), out.toString(), StandardCharsets.UTF_8);
        r = r.withOwner(new ProcessOwner(4242, 1, "sha256:" + "b".repeat(64))).moveTo(ExecutionState.RUNNING, null, created.plusMillis(30));
        store.save(r);
        store.event(id, created.plusMillis(30), "RUNNING", Map.of("pid", 4242));
        return new Run(id, out, store);
    }

    static final java.time.format.DateTimeFormatter TS = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Linha do Framework ({@code nekkora-rpa/events} 1.2.0) com os campos dados (na ordem). */
    static String fw(String fwId, Instant at, String code, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "nekkora-rpa/events");
        m.put("version", "1.2.0");
        m.put("timestamp_utc", TS.format(at));
        m.put("execution_id", fwId);
        m.put("event_code", code);
        m.put("framework_version", "0.3.0");
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return json(m);
    }

    static String json(Map<String, Object> m) {
        StringBuilder b = new StringBuilder("{");
        m.forEach((k, v) -> {
            if (b.length() > 1) b.append(',');
            b.append(StrictJson.quote(k)).append(':');
            if (v instanceof Number || v instanceof Boolean) b.append(v);
            else if (v instanceof RawJson r) b.append(r.text());
            else b.append(StrictJson.quote(String.valueOf(v)));
        });
        return b.append('}').toString();
    }

    record RawJson(String text) {}

    /** Os 121 eventos REAIS do Framework 0.3.0 do run G1 (pacote do Studio, Wikipédia, 2 itens). */
    static List<String> g1Events() throws IOException {
        try (InputStream in = ObsFixtures.class.getResourceAsStream("g1-framework-events.jsonl")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    /** Procura os sentinelas num texto; devolve o primeiro encontrado (ou nulo). */
    static String leak(String text) {
        for (String s : SENTINELS) if (text.contains(s)) return s;
        return null;
    }

    /** Lê todos os arquivos de uma árvore como texto (UTF-8 com substituição) e procura sentinelas. */
    static String leakIn(Path root) throws IOException {
        if (!Files.exists(root)) return null;
        try (var s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String hit = leak(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                if (hit != null) return root.relativize(p) + ": " + hit;
            }
        }
        return null;
    }
}
