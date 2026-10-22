package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.storage.AtomicWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DESKTOP-001 G0: ciclo de vida, identificadores, pacotes e aprovação — sem processo. */
class ExecutionDomainTest {
    @TempDir
    Path tmp;

    @Test
    @DisplayName("estados: só as transições do contrato; terminal não sai; SUCCEEDED só a partir de RUNNING")
    void transicoes() {
        Set<String> ok = new HashSet<>();
        for (ExecutionState a : ExecutionState.values()) for (ExecutionState b : a.next()) ok.add(a + ">" + b);
        assertEquals(Set.of("READY>STARTING", "READY>BLOCKED", "READY>CANCELLED", "STARTING>RUNNING", "STARTING>BLOCKED", "STARTING>FAILED",
                "STARTING>CANCELLED", "STARTING>OUTCOME_UNKNOWN", "RUNNING>SUCCEEDED", "RUNNING>FAILED", "RUNNING>CANCELLED", "RUNNING>TIMED_OUT",
                "RUNNING>OUTCOME_UNKNOWN"), ok);
        for (ExecutionState t : EnumSet.of(ExecutionState.SUCCEEDED, ExecutionState.FAILED, ExecutionState.CANCELLED, ExecutionState.TIMED_OUT,
                ExecutionState.BLOCKED, ExecutionState.OUTCOME_UNKNOWN)) {
            assertTrue(t.terminal() && t.next().isEmpty(), t.name());
        }
        ExecutionRecord r = new ExecutionRecord(ExecutionId.random(), ExecutionPackage.Kind.LAB_GENERIC, "x", "sha256:" + "a".repeat(64), ExecutionState.READY,
                null, Instant.EPOCH, null, null, null, null, Map.of(), 0, 0, false, 0);
        assertThrows(IllegalStateException.class, () -> r.moveTo(ExecutionState.SUCCEEDED, null, Instant.EPOCH), "sem confirmação artificial");
        ExecutionRecord done = r.moveTo(ExecutionState.BLOCKED, ErrorCode.EXEC_NOT_APPROVED, Instant.EPOCH);
        assertThrows(IllegalStateException.class, () -> done.moveTo(ExecutionState.STARTING, null, Instant.EPOCH));
    }

    @Test
    @DisplayName("ExecutionId: formato fixo, 20 mil sem colisão, valor inválido recusado")
    void identificadores() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) assertTrue(seen.add(ExecutionId.random().value()));
        assertTrue(seen.stream().allMatch(ExecutionId::valid));
        for (String bad : List.of("", "ex-", "ex-" + "g".repeat(32), "../" + "a".repeat(32), "ex-" + "A".repeat(32))) {
            assertThrows(IllegalArgumentException.class, () -> new ExecutionId(bad), bad);
        }
    }

    @Test
    @DisplayName("JsonReader: listas, números, chave repetida e profundidade")
    void json() throws Exception {
        Map<String, Object> m = JsonReader.object("{\"a\": [1, 2.5, \"x\", null, true], \"b\": {\"c\": {}}}");
        assertEquals(List.of(1L, 2.5, "x", JsonReader.NULL, true), m.get("a"));
        assertThrows(JsonReader.Invalid.class, () -> JsonReader.object("{\"a\": 1, \"a\": 2}"));
        assertThrows(JsonReader.Invalid.class, () -> JsonReader.object("[1]"));
        assertThrows(JsonReader.Invalid.class, () -> JsonReader.object("{\"a\": " + "[".repeat(40) + "]".repeat(40) + "}"));
    }

    @Test
    @DisplayName("pacote de laboratório: inspeção, impressão digital estável, arquivo mudado é recusado e muda a impressão")
    void pacoteLaboratorio() throws Exception {
        Path dir = ExecFixtures.labPackage(tmp.resolve("lab"), "Laboratorio sucesso", "lab_sucesso.py");
        ExecutionPackage p = ExecutionPackage.inspect(dir);
        assertEquals(ExecutionPackage.Kind.LAB_GENERIC, p.kind());
        assertEquals(ExecutionPackage.Mode.LAB, p.mode());
        assertEquals(p.fingerprint(), ExecutionPackage.inspect(dir).fingerprint(), "determinística");
        assertTrue(p.requiredFramework().isEmpty(), "laboratório não exige nekkora_rpa");
        Files.writeString(dir.resolve("lab_sucesso.py"), Files.readString(dir.resolve("lab_sucesso.py")) + "\n# mudado\n");
        assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(dir)).code());
    }

    @Test
    @DisplayName("pacote do Studio: lista exige entrada e planilha; CRLF não muda o hash; segredos, versão antiga e opção desconhecida são recusados")
    void pacoteStudio() throws Exception {
        String list = "\"input\": \"--input\", \"spreadsheet\": \"--spreadsheet\", \"output\": \"--output\"";
        Path dir = ExecFixtures.studioPackage(tmp.resolve("s1"), "list", list, "");
        ExecutionPackage p = ExecutionPackage.inspect(dir);
        assertEquals(ExecutionPackage.Kind.STUDIO_PYTHON, p.kind());
        assertTrue(p.needsInput());
        assertEquals("0.3.0", p.requiredFramework().orElseThrow());
        assertEquals(List.of("https://exemplo.invalid"), p.origins());
        // checkout do Windows com CRLF: mesmo conteúdo, mesmo hash (como o gerador do Studio calcula)
        Files.writeString(dir.resolve("main.py"), "print('main')\r\n");
        assertEquals(p.fingerprint(), ExecutionPackage.inspect(dir).fingerprint());
        Files.writeString(dir.resolve("main.py"), "print('outro')\n");
        assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(dir)).code());
        Path secrets = ExecFixtures.studioPackage(tmp.resolve("s2"), "single", "\"output\": \"--output\"", "\"steps\": [{\"credential\": true}], ");
        assertEquals(ErrorCode.EXEC_SECRETS_UNSUPPORTED, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(secrets)).code());
        Path unknown = ExecFixtures.studioPackage(tmp.resolve("s3"), "single", "\"output\": \"--output\", \"extra\": \"--rm\"", "");
        assertEquals(ErrorCode.EXEC_PACKAGE_INVALID, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(unknown)).code());
        Path old = ExecFixtures.studioPackage(tmp.resolve("s4"), "single", "\"output\": \"--output\"", "");
        Files.writeString(old.resolve("manifest.json"), Files.readString(old.resolve("manifest.json")).replace("\"1.5.0\"", "\"1.3.0\""));
        assertEquals(ErrorCode.EXEC_PACKAGE_INVALID, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(old)).code());
        Path noList = ExecFixtures.studioPackage(tmp.resolve("s5"), "list", "\"output\": \"--output\"", "");
        assertEquals(ErrorCode.EXEC_PACKAGE_INVALID, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(noList)).code(), "lista sem entrada/planilha");
    }

    @Test
    @DisplayName("pacote inseguro: caminho que sobe de pasta, arquivo ausente e pasta desconhecida")
    void pacoteInseguro() throws Exception {
        Path dir = ExecFixtures.labPackage(tmp.resolve("lab"), "Laboratorio", "lab_sucesso.py");
        Path d = dir.resolve(ExecutionPackage.LAB_DESCRIPTOR);
        String base = Files.readString(d);
        Files.writeString(d, base.replace("\"lab_comum.py\"", "\"../fora.py\""));
        assertEquals(ErrorCode.EXEC_PACKAGE_UNSAFE, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(dir)).code());
        Files.writeString(d, base);
        Files.delete(dir.resolve("lab_comum.py"));
        assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(dir)).code());
        Files.createDirectories(tmp.resolve("vazia"));
        assertEquals(ErrorCode.EXEC_PACKAGE_UNKNOWN, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(tmp.resolve("vazia"))).code());
        assertEquals(ErrorCode.EXEC_PACKAGE_NOT_FOUND, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(tmp.resolve("nao-existe"))).code());
    }

    @Test
    @DisplayName("aprovação: vale só para a impressão aprovada; arquivo ilegível = nenhuma aprovação; sem caminho no arquivo")
    void aprovacao() throws Exception {
        ApprovalStore store = new ApprovalStore(tmp.resolve("dados").resolve("aprovacoes.json"), AtomicWriter.DEFAULT);
        Path dir = ExecFixtures.labPackage(tmp.resolve("lab"), "Laboratorio", "lab_sucesso.py");
        ExecutionPackage p = ExecutionPackage.inspect(dir);
        assertFalse(store.approved(p));
        store.approve(p, Instant.parse("2026-10-22T00:00:00Z"));
        assertTrue(store.approved(p));
        String saved = Files.readString(tmp.resolve("dados").resolve("aprovacoes.json"), StandardCharsets.UTF_8);
        assertFalse(saved.contains(tmp.toString().replace("\\", "\\\\")) || saved.contains("lab_sucesso"), "nenhum caminho nem arquivo: " + saved);
        // outro conteúdo (outro descritor): outra impressão digital, não aprovada
        ExecutionPackage changed = ExecutionPackage.inspect(ExecFixtures.labPackage(tmp.resolve("lab2"), "Laboratorio", "lab_falha.py"));
        assertNotEquals(p.fingerprint(), changed.fingerprint());
        assertFalse(store.approved(changed));
        Files.writeString(tmp.resolve("dados").resolve("aprovacoes.json"), "{ quebrado");
        assertFalse(store.approved(p), "ilegível: nada é presumido aprovado");
    }
}
