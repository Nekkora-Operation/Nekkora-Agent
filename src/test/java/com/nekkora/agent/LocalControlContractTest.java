package com.nekkora.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionController.CancelResult;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.ExecutionState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * STUDIO-AGENT-LOCAL-001: o contrato PROPOSTO {@code nekkora-agent/local-control} 1.0.0 (só documento; o canal não
 * existe) é coerente: o schema é 2020-12 válido, cada tipo de mensagem tem exemplo válido, cada exemplo inválido é
 * recusado, e os enums do contrato são os do código (estado da execução, origem, cancelamento, catálogo de códigos) —
 * se o código mudar, o contrato tem de mudar junto.
 */
class LocalControlContractTest {
    static final Path DIR = Path.of(System.getProperty("agent.basedir", "."), "docs", "studio-agent-local-001", "contrato");
    static final Path SCHEMA = DIR.resolve("nekkora-agent-local-control-1.0.0.schema.json");
    static final ObjectMapper JSON = new ObjectMapper();

    static JsonSchema schema() {
        SchemaValidatorsConfig cfg = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(SchemaLocation.of(SCHEMA.toAbsolutePath().toUri().toString()), cfg);
    }

    static JsonNode node() throws Exception {
        return JSON.readTree(Files.readString(SCHEMA, StandardCharsets.UTF_8));
    }

    static List<String> valid() throws Exception {
        return Files.readString(DIR.resolve("exemplos").resolve("validos.jsonl"), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
    }

    @Test
    @DisplayName("o schema do contrato é JSON estrito e JSON Schema 2020-12 válido")
    void schemaIsValid() throws Exception {
        JsonSchema meta = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
        Set<ValidationMessage> e = meta.validate(Files.readString(SCHEMA, StandardCharsets.UTF_8), InputFormat.JSON);
        assertTrue(e.isEmpty(), e.toString());
    }

    @Test
    @DisplayName("cada tipo de mensagem tem ao menos um exemplo válido, e todos os exemplos válidos passam")
    void everyTypeHasAValidExample() throws Exception {
        JsonSchema s = schema();
        Set<String> seen = new TreeSet<>();
        for (String line : valid()) {
            Set<ValidationMessage> e = s.validate(line, InputFormat.JSON);
            assertTrue(e.isEmpty(), e + " em " + line);
            seen.add(JSON.readTree(line).get("type").asText());
        }
        Set<String> types = new TreeSet<>();
        node().at("/properties/type/enum").forEach(n -> types.add(n.asText()));
        assertEquals(types, seen);
    }

    @Test
    @DisplayName("cada exemplo inválido é recusado (aprovação pelo canal, execução automática, caminhos de rede, texto livre, estados novos...)")
    void everyInvalidExampleIsRefused() throws Exception {
        JsonSchema s = schema();
        JsonNode list = JSON.readTree(Files.readString(DIR.resolve("exemplos").resolve("invalidos.json"), StandardCharsets.UTF_8));
        assertTrue(list.size() >= 30);
        for (JsonNode c : list) {
            String m = JSON.writeValueAsString(c.get("mensagem"));
            assertFalse(s.validate(m, InputFormat.JSON).isEmpty(), "deveria recusar: " + c.get("motivo").asText());
        }
    }

    @Test
    @DisplayName("os enums do contrato são os do código: estado da execução, origem, fonte e situação do cancelamento, resultado do cancelamento")
    void enumsMatchTheCode() throws Exception {
        JsonNode n = node();
        assertEquals(names(ExecutionState.values()), texts(n.at("/$defs/executionState/enum")), "o canal não cria estado de execução");
        assertEquals(names(ExecutionRecord.Origin.values()), texts(n.at("/$defs/runSnapshot/properties/origin/properties/kind/enum")));
        Set<String> sources = names(ExecutionRecord.CancelSource.values());
        sources.add("STUDIO_LOCAL"); // só passa a existir no código com o canal (S3)
        assertEquals(sources, texts(n.at("/$defs/runSnapshot/properties/cancellation/properties/source/enum")));
        assertEquals(names(ExecutionRecord.CancelOutcome.values()), texts(n.at("/$defs/runSnapshot/properties/cancellation/properties/outcome/enum")));
        Set<String> results = names(CancelResult.values());
        results.add("NOT_REQUESTED_BY_THIS_ORIGIN"); // autorização do canal (matriz), não do controlador
        assertEquals(results, texts(n.at("/$defs/runCancelResult/properties/result/enum")));
    }

    @Test
    @DisplayName("os códigos AGT-EXE aceitos pelo contrato existem todos no catálogo; nenhum código do catálogo de execução fica de fora")
    void codesMatchTheCatalog() throws Exception {
        Pattern p = Pattern.compile(node().at("/$defs/agentCode/pattern").asText());
        Set<String> exe = new TreeSet<>();
        for (ErrorCode c : ErrorCode.values()) if (c.code().startsWith("AGT-EXE-")) exe.add(c.code());
        for (String c : exe) assertTrue(p.matcher(c).matches(), c + " do catálogo deveria ser aceito");
        for (int i = 1; i <= 99; i++) {
            String c = String.format("AGT-EXE-%03d", i);
            if (p.matcher(c).matches()) assertTrue(exe.contains(c), c + " aceito pelo contrato mas inexistente no catálogo");
        }
    }

    private static Set<String> names(Enum<?>[] values) {
        Set<String> out = new TreeSet<>();
        for (Enum<?> v : values) out.add(v.name());
        return out;
    }

    private static Set<String> texts(JsonNode array) {
        Set<String> out = new TreeSet<>();
        array.forEach(x -> out.add(x.asText()));
        return out;
    }
}
