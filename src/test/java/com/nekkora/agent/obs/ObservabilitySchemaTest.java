package com.nekkora.agent.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.obs.ActivityEvent.Category;
import com.nekkora.agent.obs.ActivityEvent.Level;
import com.nekkora.agent.obs.ActivityEvent.Source;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A01: o JSON Schema PUBLICADO do envelope ({@code nekkora-agent/observability} 1.0.0) é lido do arquivo real do
 * repositório e conferido por um validador independente (networknt, só nos testes): é JSON estrito, é um schema 2020-12
 * válido, aceita os exemplos publicados, os envelopes que o codec produz e os envelopes REAIS de três execuções da
 * compat-001 (sucesso, cancelamento, Agent derrubado), e recusa o que o codec recusa. O contrato semântico 1.0.0 fica
 * preservado: a única diferença para o arquivo publicado antes da correção é o escape do ponto no padrão do
 * {@code step_id} (barra simples, inválida em JSON, para barra dupla).
 */
class ObservabilitySchemaTest {
    static final Path SCHEMA = Path.of(System.getProperty("agent.basedir", "."), "docs", "desktop-001", "observabilidade",
            "nekkora-agent-observability-1.0.0.schema.json");
    /** SHA-256 (LF) do arquivo como publicado na DESKTOP-001, antes da correção do escape. */
    static final String PUBLISHED_BEFORE_A01 = "9a6c62d1d414a34169333d7430d7bbaecb259b416353ca553079b59b317566c9";
    static final List<String> REAL = List.of("compat-a1-sucesso.jsonl", "compat-a2-cancelamento.jsonl", "compat-a5-agent-derrubado.jsonl");
    static final List<String> PHASES = List.of(ActivityEvent.PHASE.pattern().split("[|]"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> READER_TOLERANCES = Set.of("sequence zero", "versão 1.0.1");

    static String schemaText() throws Exception {
        return Files.readString(SCHEMA, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static JsonSchema schema() throws Exception {
        JsonSchemaFactory f = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        SchemaValidatorsConfig cfg = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        // pela URI do arquivo real: o $id publicado é uma referência relativa e precisa de uma base (a do arquivo)
        return f.getSchema(SchemaLocation.of(SCHEMA.toAbsolutePath().normalize().toUri().toString()), cfg);
    }

    static Set<ValidationMessage> validate(JsonSchema s, String json) {
        return s.validate(json, InputFormat.JSON);
    }

    @Test
    void publishedSchemaIsStrictJson() throws Exception {
        // Jackson sem nenhuma tolerância (escape desconhecido, comentário, vírgula sobrando, aspas simples) lê o arquivo real
        JsonNode n = JSON.readTree(schemaText());
        assertTrue(n.isObject());
        assertEquals("nekkora-agent/observability/1.0.0", n.get("$id").asText());
        assertEquals("https://json-schema.org/draft/2020-12/schema", n.get("$schema").asText());
    }

    @Test
    void publishedSchemaIsAValid2020_12Schema() throws Exception {
        JsonSchemaFactory f = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        JsonSchema meta = f.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
        Set<ValidationMessage> errors = meta.validate(schemaText(), InputFormat.JSON);
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void onlyTheStepIdEscapeChangedSinceDesktop001() throws Exception {
        String fixed = "(" + "\\\\" + ".[a-z0-9_]{1,63})";
        String before = "(" + "\\" + ".[a-z0-9_]{1,63})";
        String undone = schemaText().replace(fixed, before);
        assertEquals(PUBLISHED_BEFORE_A01, sha256(undone), "o arquivo só pode diferir do publicado pelo escape do step_id");
        String step = JSON.readTree(schemaText()).at("/properties/step_id/pattern").asText();
        assertEquals("^[a-z][a-z0-9_]{0,63}(" + "\\" + ".[a-z0-9_]{1,63}){0,7}$", step, "o ponto é literal (escapado na regex)");
    }

    @Test
    void contractSurfaceIsTheOneTheCodecProduces() throws Exception {
        JsonNode s = JSON.readTree(schemaText());
        Set<String> props = new TreeSet<>();
        s.get("properties").fieldNames().forEachRemaining(props::add);
        assertEquals(new TreeSet<>(List.of("schema", "version", "agent_execution_id", "sequence", "timestamp_utc", "observed_utc", "source", "event_code",
                "severity", "category", "phase", "transaction_ref", "step_id", "studio_step_id", "action", "attempt", "elapsed_ms", "status", "error_code",
                "counts", "correlation")), props);
        Set<String> required = new TreeSet<>();
        s.get("required").forEach(n -> required.add(n.asText()));
        assertEquals(new TreeSet<>(List.of("schema", "version", "agent_execution_id", "sequence", "observed_utc", "source", "event_code", "severity",
                "category")), required);
        assertEquals("1.0.0", s.at("/properties/version/const").asText());
        assertFalse(s.get("additionalProperties").asBoolean());
        // enums iguais aos do modelo
        assertEquals(names(Source.values()), texts(s.at("/properties/source/enum")));
        assertEquals(names(Level.values()), texts(s.at("/properties/severity/enum")));
        assertEquals(names(Category.values()), texts(s.at("/properties/category/enum")));
        assertEquals(new TreeSet<>(PHASES), texts(s.at("/properties/phase/enum")));
        // os padrões publicados são os mesmos que o codec aplica (na escrita e na leitura)
        Map<String, Pattern> codec = Map.of("event_code", ActivityEvent.CODE, "transaction_ref", ActivityEvent.TX_REF, "step_id", ActivityEvent.STEP,
                "studio_step_id", ActivityEvent.UUID, "action", ActivityEvent.ACTION, "status", ActivityEvent.STATUS, "error_code", ActivityEvent.ERROR);
        codec.forEach((k, p) -> {
            // alternativa no nível de cima ganha parênteses para a âncora valer para as duas
            String expected = p.pattern().contains("|") ? "^(" + p.pattern() + ")$" : "^" + p.pattern() + "$";
            assertEquals(expected, s.at("/properties/" + k + "/pattern").asText(), k);
        });
        assertEquals("^" + ActivityEvent.FW_EXECUTION.pattern() + "$", s.at("/properties/correlation/properties/framework_execution_id/pattern").asText());
        assertEquals("^" + ActivityEvent.COUNT_KEY.pattern() + "$", s.at("/properties/counts/propertyNames/pattern").asText());
        assertEquals(ActivityEvent.MAX_COUNTS, s.at("/properties/counts/maxProperties").asInt());
    }

    @Test
    void publishedExamplesAreValid() throws Exception {
        JsonSchema s = schema();
        List<Path> files;
        try (Stream<Path> st = Files.list(DocumentedExamplesTest.DIR)) {
            files = st.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList();
        }
        assertFalse(files.isEmpty());
        for (Path f : files) {
            long n = 0;
            for (String line : Files.readString(f, StandardCharsets.UTF_8).lines().toList()) {
                assertTrue(validate(s, line).isEmpty(), f.getFileName() + ": " + validate(s, line) + " em " + line);
                n++;
            }
            assertTrue(n > 0);
        }
    }

    @Test
    void realEnvelopesFromThreeAgentRunsAreValid() throws Exception {
        JsonSchema s = schema();
        int total = 0;
        int dotted = 0;
        for (String name : REAL) {
            List<String> lines;
            try (InputStream in = getClass().getResourceAsStream("real/" + name)) {
                lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
            }
            assertTrue(lines.size() > 100, name);
            // o próprio coletor declarou quantos envelopes gravou: nenhum se perdeu na cópia
            JsonNode done = JSON.readTree(lines.get(lines.size() - 1));
            assertEquals("COLLECTION_COMPLETED", done.get("event_code").asText(), name);
            assertEquals(lines.size(), done.at("/counts/events").asInt(), name);
            long expected = 1;
            for (String line : lines) {
                assertTrue(validate(s, line).isEmpty(), name + ": " + validate(s, line));
                assertTrue(ActivityCodec.decode(line).isPresent(), name + ": o codec também aceita");
                JsonNode n = JSON.readTree(line);
                assertEquals(expected++, n.get("sequence").asLong(), name + ": sequence contínua");
                if (n.path("step_id").asText().contains(".")) dotted++;
                total++;
            }
        }
        assertEquals(294 + 420 + 668, total);
        assertTrue(dotted > 0, "os passos reais do Studio têm ponto no step_id: é o caso que o escape quebrado afetava");
    }

    @Test
    void everyEnvelopeTheCodecWritesIsValid() throws Exception {
        JsonSchema s = schema();
        String id = "ex-0123456789abcdef0123456789abcdef";
        Instant t = Instant.parse("2026-10-28T03:00:00Z");
        List<ActivityEvent> ev = new ArrayList<>();
        for (Source src : Source.values()) {
            for (Level lv : Level.values()) {
                for (Category c : Category.values()) ev.add(ActivityEvent.of(id, t, src, "EVENT_X", lv, c).build());
            }
        }
        for (String ph : PHASES) {
            ev.add(ActivityEvent.of(id, t, Source.FRAMEWORK, "STEP_SUCCEEDED", Level.INFO, Category.ACTIVITY).occurred(t.minusSeconds(1)).phase(ph)
                    .transaction("t-0123456789abcdef").step("profile.read_birth_date").studioStep("0f8fad5b-d9cb-469f-a165-70867728950e")
                    .action("read_text").attempt(1).elapsed(12L).status("SUCCEEDED").framework("ex-0123456789abcdef0123").position(4096L).build());
        }
        for (ErrorCode c : ErrorCode.values()) {
            ev.add(ActivityEvent.of(id, t, Source.COLLECTOR, "EVENT_GAP", Level.WARNING, Category.TELEMETRY).error(c.code()).count("max_events", 20_000)
                    .build());
        }
        long seq = 1;
        for (ActivityEvent e : ev) {
            String line = ActivityCodec.encode(e.withSequence(seq++));
            assertTrue(validate(s, line).isEmpty(), validate(s, line) + " em " + line);
        }
    }

    @Test
    void schemaRefusesWhatTheCodecRefuses() throws Exception {
        JsonSchema s = schema();
        String good = Files.readString(DocumentedExamplesTest.DIR.resolve("coletor-avisos.jsonl"), StandardCharsets.UTF_8).lines().findFirst()
                .orElseThrow();
        ObjectNode base = (ObjectNode) JSON.readTree(good);
        assertTrue(validate(s, good).isEmpty());
        Map<String, Consumer<ObjectNode>> bad = new LinkedHashMap<>();
        bad.put("step_id com ponto duplo", o -> o.put("step_id", "profile..read"));
        bad.put("step_id com ponto no fim", o -> o.put("step_id", "profile."));
        bad.put("step_id com outro caractere no lugar do ponto", o -> o.put("step_id", "profile-read"));
        bad.put("step_id com maiúscula", o -> o.put("step_id", "Profile.read"));
        bad.put("campo desconhecido", o -> o.put("valor", "x"));
        bad.put("sem sequence", o -> o.remove("sequence"));
        bad.put("sequence zero", o -> o.put("sequence", 0));
        bad.put("versão 1.0.1", o -> o.put("version", "1.0.1"));
        bad.put("origem desconhecida", o -> o.put("source", "STUDIO"));
        bad.put("instante inválido", o -> o.put("observed_utc", "ontem"));
        bad.put("transaction_ref fora do padrão", o -> o.put("transaction_ref", "tx-1"));
        bad.put("contagem negativa", o -> o.putObject("counts").put("events", -1));
        bad.put("correlação com campo livre", o -> o.putObject("correlation").put("cliente", "x"));
        for (Map.Entry<String, Consumer<ObjectNode>> e : bad.entrySet()) {
            ObjectNode o = base.deepCopy();
            e.getValue().accept(o);
            String line = JSON.writeValueAsString(o);
            assertFalse(validate(s, line).isEmpty(), e.getKey());
            // o leitor do Agent é de propósito tolerante a 1.x (versões menores futuras) e a sequence 0 (evento ainda não
            // numerado); o schema descreve o que é ESCRITO na 1.0.0, então só esses dois casos podem divergir
            if (!READER_TOLERANCES.contains(e.getKey())) assertTrue(ActivityCodec.decode(line).isEmpty(), e.getKey() + ": o codec também recusa");
        }
        ObjectNode ok = base.deepCopy();
        ok.put("step_id", "profile.read_birth_date");
        assertTrue(validate(s, JSON.writeValueAsString(ok)).isEmpty(), "um ponto entre segmentos é aceito");
    }

    private static Set<String> names(Enum<?>[] values) {
        Set<String> out = new TreeSet<>();
        for (Enum<?> v : values) out.add(v.name());
        return out;
    }

    private static Set<String> texts(JsonNode array) {
        Set<String> out = new TreeSet<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
