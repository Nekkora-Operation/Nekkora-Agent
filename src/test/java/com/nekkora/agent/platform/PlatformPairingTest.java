package com.nekkora.agent.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.platform.FakeGateway.Fault;
import com.nekkora.agent.platform.PlatformFixtures.Lab;
import com.nekkora.agent.platform.PlatformStatus.LinkState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pareamento, credencial, rotação, revogação e destino (contrato sec. 4 e 12.8), contra o TRANSPORTE SIMULADO
 * ({@link FakeGateway}); as quedas são falhas INJETADAS. Nenhuma pausa: cada rodada é chamada diretamente.
 */
class PlatformPairingTest {
    @TempDir
    Path dir;

    private static String secretOf(String redeemBody) {
        Matcher m = Pattern.compile("\"credentialSecret\":\"([A-Za-z0-9_-]{43})\"").matcher(redeemBody);
        return m.find() ? m.group(1) : null;
    }

    @Test
    @DisplayName("P1: o material vai para a guarda protegida ANTES de qualquer tentativa; resposta perdida é repetida com o MESMO material")
    void lostPairingResponseIsRepeatedWithTheSameMaterial() {
        Lab lab = new Lab(dir);
        UUID worker = lab.gw.worker();
        String code = lab.gw.pairingCode(lab.org, worker);
        lab.gw.fault("/enrollments/redeem", Fault.DROP_AFTER_COMMIT);
        assertTrue(lab.svc.pair(lab.url, code.toCharArray()).accepted());
        // antes de qualquer rede: material protegido gravado e o pendente anotado
        assertEquals(0, lab.gw.requests.size());
        assertNotNull(lab.secrets.text(CredentialVault.REDEEM));
        assertEquals(LinkState.PAIRING, lab.svc.status().link());
        lab.tick(1); // o servidor processou e a resposta se perdeu
        assertEquals(1, lab.gw.agents.size(), "o servidor criou o vínculo");
        assertEquals(LinkState.PAIRING, lab.svc.status().link(), "resultado desconhecido: continua pendente");
        assertEquals(ErrorCode.PLT_UNREACHABLE, lab.svc.status().problem());
        lab.tick(1);
        assertEquals(1, lab.gw.count("POST", "/redeem"), "espera crescente: nada antes do prazo");
        lab.clock.advance(Duration.ofSeconds(3));
        lab.tick(1);
        assertEquals(2, lab.gw.count("POST", "/redeem"));
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        assertEquals(1, lab.gw.agents.size(), "a repetição não criou outro Agent");
        assertEquals(secretOf(lab.gw.bodies.get(0)), secretOf(lab.gw.bodies.get(1)), "mesmo segredo nas duas tentativas");
        assertNull(lab.secrets.text(CredentialVault.REDEEM), "material do resgate apagado só depois da confirmação gravada");
        assertTrue(lab.secrets.text(CredentialVault.CREDENTIAL).startsWith("nka1." + lab.org + "."));
        // ordem das gravações protegidas: material do resgate → credencial → apaga material
        List<String> o = lab.secrets.order;
        assertEquals(List.of("write:resgate", "write:credencial", "delete:resgate"), o.subList(o.indexOf("write:resgate"), o.size()));
    }

    @Test
    @DisplayName("P2: o mesmo código com OUTRO segredo (outra instalação) é recusado e não cria nada")
    void sameCodeWithAnotherSecretIsRefused() throws Exception {
        Lab a = new Lab(dir.resolve("a"));
        UUID worker = a.gw.worker();
        String code = a.gw.pairingCode(a.org, worker);
        assertTrue(a.svc.pair(a.url, code.toCharArray()).accepted());
        a.tick(1);
        assertEquals(LinkState.PAIRED, a.svc.status().link());
        // outra instalação com o MESMO código (mesmo gateway): segredo diferente
        Lab b = new Lab(dir.resolve("b"));
        PlatformService other = new PlatformService(dir.resolve("b").resolve("plataforma"), () -> FakeGateway.ORIGIN, b.runs, id -> java.util.OptionalLong.empty(),
                b.secrets, a.gw, com.nekkora.agent.storage.AtomicWriter.DEFAULT, a.clock, "0.1.0", () -> 0.5);
        other.load();
        assertTrue(other.pair(FakeGateway.ORIGIN, code.toCharArray()).accepted());
        other.tick();
        assertEquals(LinkState.NOT_PAIRED, other.status().link());
        assertEquals(ErrorCode.PLT_CODE_REFUSED, other.status().problem());
        assertEquals(1, a.gw.agents.size());
        assertNull(b.secrets.text(CredentialVault.REDEEM), "pendente encerrado");
        assertNull(b.secrets.text(CredentialVault.CREDENTIAL));
    }

    @Test
    @DisplayName("P3: depois de rotação ou revogação, a repetição do resgate é recusada e nada é reativado")
    void replayAfterRotationOrRevocationIsRefused() {
        Lab lab = new Lab(dir);
        UUID agent = lab.paired();
        String originalRedeem = lab.gw.bodies.get(0);
        String oldToken = lab.secrets.text(CredentialVault.CREDENTIAL);
        // rotação pedida pela pessoa com código da Platform
        assertTrue(lab.svc.rotate(lab.gw.rotationCode(agent).toCharArray()).accepted());
        assertEquals(LinkState.ROTATING, lab.svc.status().link());
        lab.tick(1);
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        String newToken = lab.secrets.text(CredentialVault.CREDENTIAL);
        assertNotEquals(oldToken, newToken);
        // o resgate ORIGINAL repetido depois da rotação: 401, sem restaurar a credencial substituída
        int before = lab.gw.unauthenticated;
        HttpTransport.Response r = replay(lab, originalRedeem);
        assertEquals(401, r.status());
        assertEquals(before + 1, lab.gw.unauthenticated);
        long valid = lab.gw.credentials.values().stream().filter(c -> c.agent.equals(agent) && !c.revoked).count();
        assertEquals(1, valid, "só a credencial nova vale");
        // a credencial antiga não autentica mais
        assertEquals(401, call(lab, oldToken).status());
        // revogação: o transporte para inteiro na primeira recusa (sem insistir: o limite de recusas por IP da API)
        lab.gw.revokeAgent(agent);
        lab.clock.advance(Duration.ofMinutes(2));
        lab.tick(1);
        assertEquals(LinkState.REFUSED, lab.svc.status().link());
        int afterRefusal = lab.gw.requests.size();
        lab.settle();
        assertEquals(afterRefusal, lab.gw.requests.size(), "nenhuma chamada autenticada depois da recusa");
        assertEquals(401, replay(lab, originalRedeem).status(), "nem a repetição do pareamento reativa o Agent revogado");
        assertTrue(lab.diag.has("credentialRefused"));
    }

    @Test
    @DisplayName("P4: queda entre a confirmação da rotação no servidor e a gravação local: o reinício conclui pela repetição idempotente")
    void restartDuringRotation() {
        Lab lab = new Lab(dir);
        UUID agent = lab.paired();
        String oldToken = lab.secrets.text(CredentialVault.CREDENTIAL);
        assertTrue(lab.svc.rotate(lab.gw.rotationCode(agent).toCharArray()).accepted());
        lab.gw.fault("/enrollments/redeem", Fault.DROP_AFTER_COMMIT);
        lab.tick(1); // servidor rotacionou (a antiga foi revogada) e a resposta se perdeu
        assertTrue(lab.gw.credentials.values().stream().filter(c -> c.agent.equals(agent)).anyMatch(c -> c.revoked));
        assertEquals(oldToken, lab.secrets.text(CredentialVault.CREDENTIAL), "a credencial anterior não foi apagada antes da confirmação");
        assertNotNull(lab.secrets.text(CredentialVault.REDEEM), "material da rotação guardado para a reconciliação");
        lab.restart(); // queda do Agent
        assertEquals(LinkState.ROTATING, lab.svc.status().link());
        int hbBefore = (int) lab.gw.count("POST", "/heartbeat");
        lab.tick(1); // a primeira coisa é concluir a rotação (nenhuma chamada com a credencial revogada)
        assertEquals(hbBefore, (int) lab.gw.count("POST", "/heartbeat"));
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        assertNotEquals(oldToken, lab.secrets.text(CredentialVault.CREDENTIAL));
        lab.tick(1);
        assertEquals(LinkState.PAIRED, lab.svc.status().link(), "a credencial nova autentica");
        assertEquals(0, lab.gw.unauthenticated);
    }

    @Test
    @DisplayName("P5: queda depois de gravar a credencial nova e antes do vínculo: o reinício usa a credencial guardada e conclui")
    void crashBetweenCredentialAndLink() throws Exception {
        Lab lab = new Lab(dir);
        UUID agent = lab.paired();
        assertTrue(lab.svc.rotate(lab.gw.rotationCode(agent).toCharArray()).accepted());
        // a confirmação chega, a credencial nova é gravada e o vínculo NÃO (o processo morre aqui): simulado gravando só a credencial
        lab.gw.fault("/enrollments/redeem", Fault.DROP_AFTER_COMMIT);
        lab.tick(1);
        FakeGateway.Credential fresh = lab.gw.credentials.values().stream().filter(c -> c.agent.equals(agent) && !c.revoked).findFirst().orElseThrow();
        String material = lab.secrets.text(CredentialVault.REDEEM);
        String secret = material.substring(material.indexOf('\n') + 1);
        lab.secrets.data.put(CredentialVault.CREDENTIAL, ("nka1." + lab.org + "." + fresh.id + "." + secret).getBytes(StandardCharsets.US_ASCII));
        lab.restart();
        lab.tick(2);
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        assertEquals(0, lab.gw.unauthenticated);
        assertNull(lab.secrets.text(CredentialVault.REDEEM));
        assertTrue(Files.readString(dir.resolve("plataforma").resolve(CredentialVault.FILE)).contains(fresh.id.toString()));
    }

    @Test
    @DisplayName("P6: código malformado, destino http e falta de proteção do sistema são recusados sem rede e sem gravar nada")
    void localRefusalsTouchNothing() {
        Lab lab = new Lab(dir);
        assertEquals(ErrorCode.PLT_CODE_INVALID, lab.svc.pair(lab.url, "nkp1.x.y.z".toCharArray()).problem());
        assertEquals(ErrorCode.PLT_DESTINATION_INVALID, lab.svc.pair("http://platform.test", lab.gw.pairingCode(lab.org, lab.gw.worker()).toCharArray()).problem());
        assertEquals(ErrorCode.PLT_DESTINATION_INVALID, lab.svc.pair("https://u:p@platform.test", "x".toCharArray()).problem());
        lab.secrets.available = false;
        assertEquals(ErrorCode.PLT_SECRET_STORE_UNAVAILABLE, lab.svc.pair(lab.url, lab.gw.pairingCode(lab.org, lab.gw.worker()).toCharArray()).problem());
        lab.settle();
        assertEquals(0, lab.gw.requests.size());
        assertTrue(lab.secrets.data.isEmpty());
        assertFalse(Files.exists(dir.resolve("plataforma").resolve(CredentialVault.FILE)));
    }

    @Test
    @DisplayName("P7: endereço alterado depois do pareamento: a credencial não segue a mudança (nada é enviado) e 3xx nunca é seguido")
    void credentialNeverFollowsAnotherDestination() {
        Lab lab = new Lab(dir);
        lab.paired();
        int before = lab.gw.requests.size();
        lab.url = "https://outra-platform.test";
        lab.settle();
        assertEquals(LinkState.DESTINATION_CHANGED, lab.svc.status().link());
        assertEquals(ErrorCode.PLT_DESTINATION_CHANGED, lab.svc.status().problem());
        assertEquals(before, lab.gw.requests.size(), "nenhum pedido (nem para a antiga, nem para a nova)");
        lab.url = FakeGateway.ORIGIN;
        lab.gw.fault("/heartbeat", Fault.REDIRECT);
        lab.clock.advance(Duration.ofMinutes(2));
        lab.tick(1);
        assertEquals(ErrorCode.PLT_RESPONSE_INVALID, lab.svc.status().problem(), "redirecionamento é resposta fora do contrato");
        assertTrue(lab.gw.requests.stream().allMatch(r -> r.uri().toString().startsWith(FakeGateway.ORIGIN)));
    }

    @Test
    @DisplayName("P8: credencial, segredo e código nunca em arquivo comum, diagnóstico ou toString")
    void noSecretOutsideTheProtectedStore() throws Exception {
        Lab lab = new Lab(dir);
        UUID worker = lab.gw.worker();
        String code = lab.gw.pairingCode(lab.org, worker);
        lab.svc.pair(lab.url, code.toCharArray());
        lab.tick(2);
        String token = lab.secrets.text(CredentialVault.CREDENTIAL);
        String secret = token.substring(token.lastIndexOf('.') + 1);
        String codeSecret = code.substring(code.lastIndexOf('.') + 1);
        String files = lab.allFiles();
        for (String s : List.of(secret, codeSecret, code, token)) {
            assertFalse(files.contains(s), "segredo em arquivo comum");
            assertFalse(String.join("\n", lab.diag.lines).contains(s), "segredo no diagnóstico");
            assertFalse(lab.svc.status().toString().contains(s));
        }
        for (HttpTransport.Request r : lab.gw.requests) assertFalse(r.toString().contains(secret), "pedido sem cabeçalho no toString");
        assertEquals("Material[<segredo>]", new CredentialVault.Material(code, secret).toString());
    }

    private static HttpTransport.Response replay(Lab lab, String body) {
        try {
            return lab.gw.send(new HttpTransport.Request("POST", java.net.URI.create(FakeGateway.ORIGIN + "/agent/api/v1/enrollments/redeem"),
                    java.util.Map.of("Nekkora-Agent-Contract", "1.0", "Content-Type", "application/json"), body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static HttpTransport.Response call(Lab lab, String token) {
        try {
            return lab.gw.send(new HttpTransport.Request("POST", java.net.URI.create(FakeGateway.ORIGIN + "/agent/api/v1/heartbeat"),
                    java.util.Map.of("Nekkora-Agent-Contract", "1.0", "Authorization", "Bearer " + token),
                    "{\"agentVersion\":\"0.1.0\",\"contractVersion\":\"1.0\",\"capabilities\":[]}".getBytes(StandardCharsets.UTF_8)));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
