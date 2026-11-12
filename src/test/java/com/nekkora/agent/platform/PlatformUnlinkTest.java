package com.nekkora.agent.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.app.MachineUnlink;
import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.platform.FakeGateway.Fault;
import com.nekkora.agent.platform.PlatformFixtures.Lab;
import com.nekkora.agent.platform.PlatformService.ServerView;
import com.nekkora.agent.platform.PlatformStatus.LinkState;
import com.nekkora.agent.storage.AtomicWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLATFORM-AGENT-V2-002 T06: intenção do código (pareamento × renovação) e "Desvincular esta máquina", contra o TRANSPORTE
 * SIMULADO ({@link FakeGateway}, espelho do contrato da API); as quedas são falhas INJETADAS. Nenhuma pausa.
 * PLATFORM-AGENT-V2-004 T13: o desvínculo agora também encerra o vínculo na Platform (U8, U10, U13 e U14 atualizados; o
 * protocolo completo está em {@link PlatformRemoteUnlinkTest}).
 */
class PlatformUnlinkTest {
    @TempDir
    Path dir;

    private Path plataforma(Lab lab) {
        return lab.root.resolve("plataforma");
    }

    /** Pareado, com uma autorização para a impressão do pacote já baixada. */
    private Lab ready() {
        Lab lab = new Lab(dir);
        lab.paired();
        lab.gw.authorize(lab.org, lab.worker, PlatformFixtures.FP, PlatformFixtures.T0.plus(Duration.ofDays(30)));
        lab.svc.refreshNow();
        lab.tick(1);
        return lab;
    }

    private static UUID agentOf(Lab lab) {
        return UUID.fromString(lab.svc.status().agentId());
    }

    private static String intentOf(HttpTransport.Request r) {
        return r.headers().get("Nekkora-Agent-Intent");
    }

    private List<HttpTransport.Request> redeems(Lab lab) {
        return lab.gw.requests.stream().filter(r -> r.uri().getPath().endsWith("/enrollments/redeem")).toList();
    }

    /** Outra instalação (outra pasta, outra guarda) falando com o MESMO servidor simulado. */
    private PlatformService otherInstallation(Lab lab, String name) {
        Lab b = new Lab(dir.resolve(name));
        PlatformService s = new PlatformService(dir.resolve(name).resolve("plataforma"), () -> FakeGateway.ORIGIN, b.runs, id -> OptionalLong.empty(), b.secrets,
                lab.gw, AtomicWriter.DEFAULT, lab.clock, "0.1.0", () -> 0.5);
        s.load();
        return s;
    }

    // ------------------------------------------------------------------------------------------- intenção do código

    @Test
    @DisplayName("U1: o resgate declara a intenção: 'pair' no pareamento; 'rotate' (com a credencial atual) na renovação")
    void intentHeaderIsSent() {
        Lab lab = new Lab(dir);
        UUID agent = lab.paired();
        assertEquals("pair", intentOf(redeems(lab).get(0)));
        assertNull(redeems(lab).get(0).headers().get("Authorization"), "pareamento sem credencial");
        assertTrue(lab.svc.rotate(lab.gw.rotationCode(agent).toCharArray()).accepted());
        lab.tick(1);
        HttpTransport.Request rot = redeems(lab).get(1);
        assertEquals("rotate", intentOf(rot));
        assertTrue(rot.headers().get("Authorization").startsWith("Bearer nka1."));
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
    }

    @Test
    @DisplayName("U2: código de PAREAMENTO em 'Renovar credencial': explica a ação certa, não consome o código e o vínculo fica intacto")
    void pairingCodeInRotation() throws Exception {
        Lab lab = new Lab(dir);
        UUID agent = lab.paired();
        String token = lab.secrets.text(CredentialVault.CREDENTIAL);
        byte[] link = Files.readAllBytes(plataforma(lab).resolve(CredentialVault.FILE));
        String code = lab.gw.pairingCode(lab.org, lab.gw.worker());
        assertTrue(lab.svc.rotate(code.toCharArray()).accepted(), "o formato é o mesmo: só a Platform sabe o tipo");
        lab.tick(1);
        assertEquals(1, lab.gw.wrongIntent);
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        assertEquals(ErrorCode.PLT_PAIRING_CODE_FOR_ROTATION, lab.svc.status().problem());
        assertTrue(ErrorCode.PLT_PAIRING_CODE_FOR_ROTATION.description().contains("desvincule-a primeiro e pareie de novo"));
        assertEquals(token, lab.secrets.text(CredentialVault.CREDENTIAL), "a credencial atual continua");
        assertNull(lab.secrets.text(CredentialVault.REDEEM), "só a tentativa pendente saiu");
        assertEquals(1, lab.gw.agents.size(), "nenhum Agent novo no servidor");
        assertTrue(lab.gw.enrollments.values().stream().filter(e -> e.purpose == 1).anyMatch(e -> e.consumedCredential == null), "código não consumido");
        assertEquals(agent, agentOf(lab));
        String after = Files.readString(plataforma(lab).resolve(CredentialVault.FILE));
        String before = new String(link, StandardCharsets.UTF_8);
        assertEquals(before.replaceAll(",\"lastRefusal\":[^,}]*", ""), after.replaceAll(",\"lastRefusal\":[^,}]*", ""), "vínculo inalterado");
        // a credencial continua aceita
        lab.clock.advance(Duration.ofMinutes(2));
        lab.tick(1);
        assertEquals(0, lab.gw.unauthenticated);
    }

    @Test
    @DisplayName("U3: código de RENOVAÇÃO em 'Parear': explica a ação certa, não consome o código (a máquina dona ainda renova com ele)")
    void rotationCodeInPairing() {
        Lab lab = new Lab(dir);
        UUID agent = lab.paired();
        String rotation = lab.gw.rotationCode(agent);
        PlatformService other = otherInstallation(lab, "outra");
        assertTrue(other.pair(FakeGateway.ORIGIN, rotation.toCharArray()).accepted());
        other.tick();
        assertEquals(LinkState.NOT_PAIRED, other.status().link());
        assertEquals(ErrorCode.PLT_ROTATION_CODE_FOR_PAIRING, other.status().problem());
        assertTrue(ErrorCode.PLT_ROTATION_CODE_FOR_PAIRING.description().contains("Workers → Vincular Agent"));
        assertEquals(1, lab.gw.wrongIntent);
        assertEquals(1, lab.gw.agents.size());
        // não consumido: a máquina pareada renova com o MESMO código
        assertTrue(lab.svc.rotate(rotation.toCharArray()).accepted());
        lab.tick(1);
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        assertNull(lab.svc.status().problem());
        assertEquals(0, lab.gw.unauthenticated);
    }

    @Test
    @DisplayName("U4: código malformado (local) ou desconhecido (401) na renovação: o vínculo continua; no pareamento, nada é criado")
    void invalidOrUnknownCode() {
        Lab lab = new Lab(dir);
        lab.paired();
        String token = lab.secrets.text(CredentialVault.CREDENTIAL);
        assertEquals(ErrorCode.PLT_CODE_INVALID, lab.svc.rotate("nkp1.nada".toCharArray()).problem());
        String unknown = "nkp1." + lab.org + "." + UUID.randomUUID() + "." + FakeGateway.secret();
        assertTrue(lab.svc.rotate(unknown.toCharArray()).accepted());
        lab.tick(1);
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        assertEquals(ErrorCode.PLT_ROTATION_REFUSED, lab.svc.status().problem());
        assertEquals(token, lab.secrets.text(CredentialVault.CREDENTIAL));
        PlatformService other = otherInstallation(lab, "outra");
        assertTrue(other.pair(FakeGateway.ORIGIN, ("nkp1." + lab.org + "." + UUID.randomUUID() + "." + FakeGateway.secret()).toCharArray()).accepted());
        other.tick();
        assertEquals(ErrorCode.PLT_CODE_REFUSED, other.status().problem());
        assertEquals(1, lab.gw.agents.size());
    }

    @Test
    @DisplayName("U5: resposta perdida no resgate com intenção: repetida com o mesmo material e a mesma intenção (idempotente)")
    void lostResponseWithIntent() {
        Lab lab = new Lab(dir);
        lab.gw.fault("/enrollments/redeem", Fault.DROP_AFTER_COMMIT);
        assertTrue(lab.svc.pair(lab.url, lab.gw.pairingCode(lab.org, lab.gw.worker()).toCharArray()).accepted());
        lab.tick(1);
        assertEquals(LinkState.PAIRING, lab.svc.status().link());
        lab.clock.advance(Duration.ofSeconds(3));
        lab.tick(1);
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        assertEquals(1, lab.gw.agents.size());
        assertTrue(redeems(lab).stream().allMatch(r -> "pair".equals(intentOf(r))));
    }

    @Test
    @DisplayName("U6: Agent anterior (sem o cabeçalho): código de pareamento COM credencial é 409, sem criar outro Agent")
    void legacyPairingCodeWithBearer() throws Exception {
        Lab lab = new Lab(dir);
        lab.paired();
        String token = lab.secrets.text(CredentialVault.CREDENTIAL);
        String code = lab.gw.pairingCode(lab.org, lab.gw.worker());
        String body = "{\"code\":\"" + code + "\",\"credentialSecret\":\"" + FakeGateway.secret() + "\",\"agentVersion\":\"0.0.9\",\"contractVersions\":[\"1.0\"]}";
        HttpTransport.Response r = lab.gw.send(new HttpTransport.Request("POST", java.net.URI.create(FakeGateway.ORIGIN + "/agent/api/v1/enrollments/redeem"),
                java.util.Map.of("Nekkora-Agent-Contract", "1.0", "Authorization", "Bearer " + token), body.getBytes(StandardCharsets.UTF_8)));
        assertEquals(409, r.status());
        assertEquals(1, lab.gw.agents.size());
    }

    // ------------------------------------------------------------------------------------------- desvínculo

    @Test
    @DisplayName("U7: cancelar a confirmação não muda nada (vínculo, credencial, autorizações, entregas)")
    void cancelKeepsEverything() throws Exception {
        Lab lab = ready();
        byte[] link = Files.readAllBytes(plataforma(lab).resolve(CredentialVault.FILE));
        byte[] auth = Files.readAllBytes(plataforma(lab).resolve(AuthorizationCache.FILE));
        String token = lab.secrets.text(CredentialVault.CREDENTIAL);
        MachineUnlink u = new MachineUnlink(lab.svc, () -> false);
        MachineUnlink.Plan plan = u.plan();
        assertFalse(plan.running());
        assertEquals(ServerView.ACCEPTED, plan.preview().server());
        assertEquals(lab.org.toString(), plan.preview().organizationId(), "identificadores completos para a pessoa");
        assertEquals(lab.worker.toString(), plan.preview().workerId());
        // a pessoa cancela: nada é chamado
        assertArrayEquals(link, Files.readAllBytes(plataforma(lab).resolve(CredentialVault.FILE)));
        assertArrayEquals(auth, Files.readAllBytes(plataforma(lab).resolve(AuthorizationCache.FILE)));
        assertEquals(token, lab.secrets.text(CredentialVault.CREDENTIAL));
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        assertFalse(Files.exists(plataforma(lab).resolve(UnlinkLedger.DIR)));
        assertEquals(1, lab.svc.authorizationsFor("sha256:" + PlatformFixtures.FP).size());
    }

    @Test
    @DisplayName("U8: desvínculo com a Platform aceitando a credencial: a Platform confirma (Worker livre lá) e tudo do vínculo sai desta máquina")
    void unlinkOnline() throws Exception {
        Lab lab = ready();
        UUID agent = agentOf(lab);
        MachineUnlink u = new MachineUnlink(lab.svc, () -> false);
        MachineUnlink.Plan plan = u.plan();
        assertEquals(ServerView.ACCEPTED, plan.preview().server());
        PlatformService.UnlinkResult r = u.confirm(plan, false);
        assertTrue(r.done());
        assertNull(r.problem());
        assertEquals(PlatformStatus.RemoteOutcome.CONFIRMED, r.remote());
        assertEquals(LinkState.NOT_PAIRED, lab.svc.status().link());
        assertFalse(Files.exists(plataforma(lab).resolve(CredentialVault.FILE)));
        assertFalse(Files.exists(plataforma(lab).resolve(AuthorizationCache.FILE)));
        assertNull(lab.secrets.text(CredentialVault.CREDENTIAL));
        assertNull(lab.secrets.text(CredentialVault.REDEEM));
        assertTrue(lab.svc.authorizationsFor("sha256:" + PlatformFixtures.FP).isEmpty());
        // T13: a Platform encerrou o vínculo desta credencial (Worker livre lá), por UMA operação desta máquina
        assertFalse(lab.gw.agents.get(agent).active || lab.gw.agents.get(agent).assigned);
        assertEquals(1, lab.gw.count("POST", "/link/unlink"));
        assertTrue(lab.gw.unlinkOps.containsKey(agent));
        String ledger = Files.readString(plataforma(lab).resolve(UnlinkLedger.DIR).resolve(agent + ".json"));
        assertTrue(ledger.contains("\"server\":\"CONFIRMED\"") && ledger.contains("\"undeliveredRuns\":0"));
        assertFalse(Files.exists(plataforma(lab).resolve(UnlinkRequestStore.FILE)), "o pedido sai só na conclusão");
        int calls = lab.gw.requests.size();
        lab.settle();
        assertEquals(calls, lab.gw.requests.size(), "nenhuma chamada pela identidade anterior depois do desvínculo");
        // o lançador externo (só leitura do disco) também não acha vínculo
        assertTrue(LocalGate.pairedAgent(plataforma(lab)).isEmpty());
        // reabrir o Agent: "Não pareado"
        lab.restart();
        assertEquals(LinkState.NOT_PAIRED, lab.svc.status().link());
        assertNull(lab.svc.status().problem());
    }

    @Test
    @DisplayName("U9: desvínculo sem resposta da Platform: diz que não pôde consultar e conclui localmente")
    void unlinkOffline() {
        Lab lab = ready();
        lab.gw.fault("/heartbeat", Fault.DROP_BEFORE);
        MachineUnlink u = new MachineUnlink(lab.svc, () -> false);
        MachineUnlink.Plan plan = u.plan();
        assertEquals(ServerView.UNREACHABLE, plan.preview().server());
        assertEquals("platform.test", plan.preview().host());
        assertTrue(u.confirm(plan, false).done());
        assertEquals(LinkState.NOT_PAIRED, lab.svc.status().link());
    }

    @Test
    @DisplayName("U10: depois da revogação na Platform (401): a consulta mostra a recusa; o desvínculo pergunta UMA vez e conclui com 'já revogado'")
    void unlinkAfterServerRevocation() {
        Lab lab = ready();
        lab.gw.revokeAgent(agentOf(lab));
        MachineUnlink u = new MachineUnlink(lab.svc, () -> false);
        MachineUnlink.Plan plan = u.plan();
        assertEquals(ServerView.REFUSED, plan.preview().server());
        assertEquals(LinkState.REFUSED, lab.svc.status().link(), "a recusa vale como em qualquer chamada");
        int calls = lab.gw.requests.size();
        assertEquals(ServerView.REFUSED, u.plan().preview().server(), "já recusada: nenhuma chamada nova");
        assertEquals(calls, lab.gw.requests.size());
        PlatformService.UnlinkResult r = u.confirm(plan, false);
        assertTrue(r.done());
        assertEquals(PlatformStatus.RemoteOutcome.ENDED_BY_ADMINISTRATION, r.remote());
        assertEquals(calls + 1, lab.gw.requests.size(), "uma pergunta (a rota de desvínculo), nenhuma outra chamada");
        assertEquals(LinkState.NOT_PAIRED, lab.svc.status().link());
    }

    @Test
    @DisplayName("U11: execução em andamento bloqueia o desvínculo (antes e no momento da confirmação); nada muda")
    void runningExecutionBlocks() {
        Lab lab = ready();
        AtomicBoolean running = new AtomicBoolean(true);
        MachineUnlink u = new MachineUnlink(lab.svc, running::get);
        MachineUnlink.Plan plan = u.plan();
        assertTrue(plan.running());
        assertEquals(ServerView.NOT_CONSULTED, plan.preview().server(), "nem consulta a Platform");
        assertEquals(ErrorCode.PLT_UNLINK_RUNNING, u.confirm(plan, true).problem());
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        // a execução começa entre a confirmação mostrada e o pedido: ainda bloqueado
        running.set(false);
        MachineUnlink.Plan p2 = u.plan();
        running.set(true);
        PlatformService.UnlinkResult r = u.confirm(p2, true);
        assertFalse(r.done());
        assertEquals(ErrorCode.PLT_UNLINK_RUNNING, r.problem());
        assertNotNull(lab.secrets.text(CredentialVault.CREDENTIAL));
        running.set(false);
        assertTrue(u.confirm(u.plan(), false).done(), "depois do fim, desvincula");
    }

    @Test
    @DisplayName("U12: envios pendentes: mostrados; 'Aguardar' mantém; 'Desvincular mesmo assim' guarda como NÃO entregues, nunca enviados")
    void pendingDeliveries() throws Exception {
        Lab lab = ready();
        UUID agent = agentOf(lab);
        UUID auth = lab.gw.authorizations.keySet().iterator().next();
        ExecutionId id = lab.execution(ExecutionState.SUCCEEDED, lab.clock.instant());
        lab.events(id, 1, 3);
        lab.gw.fault("/agent/api/v1/runs", Fault.UNAVAILABLE); // a Platform não aceita agora: o envio fica pendente
        lab.link(id, auth);
        lab.tick(1);
        assertEquals(1, lab.svc.status().pendingRuns());
        MachineUnlink u = new MachineUnlink(lab.svc, () -> false);
        lab.clock.advance(Duration.ofMinutes(2));
        MachineUnlink.Plan plan = u.plan();
        assertEquals(1, plan.preview().pendingRuns());
        // sem a escolha explícita, nada sai (nem em silêncio)
        PlatformService.UnlinkResult keep = lab.svc.unlink(false, plan.preview().server());
        assertFalse(keep.done());
        assertEquals(ErrorCode.PLT_UNLINK_PENDING, keep.problem());
        assertEquals(1, keep.undeliveredRuns());
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        // "Desvincular mesmo assim"
        PlatformService.UnlinkResult r = u.confirm(plan, true);
        assertTrue(r.done());
        assertEquals(1, r.undeliveredRuns());
        assertEquals(DeliveryView.State.UNLINKED_UNDELIVERED, lab.svc.delivery(id).state());
        assertTrue(Files.exists(lab.runs.dir(id).resolve("estado.json")), "o histórico da execução fica");
        assertTrue(Files.exists(plataforma(lab).resolve(DeliveryStore.DIR).resolve(id.value() + ".json")), "o registro da entrega fica");
        String ledger = Files.readString(plataforma(lab).resolve(UnlinkLedger.DIR).resolve(agent + ".json"));
        assertTrue(ledger.contains(id.value()) && ledger.contains("\"undeliveredRuns\":1"));
        // reinício: continua identificado como não entregue
        lab.restart();
        assertEquals(DeliveryView.State.UNLINKED_UNDELIVERED, lab.svc.delivery(id).state());
        assertEquals(0, lab.svc.status().pendingRuns());
    }

    @Test
    @DisplayName("U13: parear de novo na MESMA pasta, em outra organização: nenhuma autorização, entrega, pedido ou intenção do vínculo anterior é usada")
    void rePairElsewhereUsesNothingOld() throws Exception {
        Lab lab = ready();
        UUID oldAgent = agentOf(lab);
        UUID auth = lab.gw.authorizations.keySet().iterator().next();
        // entrega pendente
        ExecutionId pending = lab.execution(ExecutionState.SUCCEEDED, lab.clock.instant());
        lab.events(pending, 1, 2);
        lab.gw.fault("/agent/api/v1/runs", Fault.UNAVAILABLE, Fault.UNAVAILABLE); // o registro não acontece antes do desvínculo
        lab.link(pending, auth);
        lab.tick(1);
        // pedido de execução recebido (ainda não decidido)
        UUID req = lab.gw.request(oldAgent, auth, lab.clock.instant().plus(Duration.ofHours(1)));
        lab.clock.advance(Duration.ofMinutes(6));
        lab.tick(1);
        assertEquals(1, lab.svc.runRequests().size());
        // intenção pré-início gravada (a execução ainda não existe)
        assertTrue(lab.svc.prepare(auth, "sha256:" + PlatformFixtures.FP).accepted());
        assertTrue(Files.exists(plataforma(lab).resolve(DeliveryStore.DIR).resolve(DeliveryStore.INTENT)));
        // T13: um início sendo preparado (intenção gravada) bloqueia o desvínculo, sem mudar nada
        assertEquals(ErrorCode.PLT_UNLINK_RUNNING, lab.svc.unlink(true, ServerView.NOT_CONSULTED).problem());
        assertFalse(Files.exists(plataforma(lab).resolve(UnlinkRequestStore.FILE)));
        assertNotNull(lab.secrets.text(CredentialVault.CREDENTIAL));
        // o início não aconteceu (a tela descarta a intenção quando o início é recusado): desvincula mesmo assim e pareia em
        // outra organização (sem apagar a pasta)
        lab.svc.discardIntent();
        assertTrue(lab.svc.unlink(true, ServerView.NOT_CONSULTED).done());
        assertFalse(Files.exists(plataforma(lab).resolve(DeliveryStore.DIR).resolve(DeliveryStore.INTENT)), "intenção descartada");
        assertTrue(Files.readString(plataforma(lab).resolve(RunRequestStore.DIR).resolve(req + ".json")).contains("\"stage\":\"CLOSED\""));
        int runsBefore = lab.gw.runs.size();
        int decisionsBefore = lab.gw.runRequests.get(req).decisions;
        long aboutPending = lab.gw.requests.stream().filter(r -> r.uri().getPath().contains(pending.value())).count();
        int bodiesBefore = lab.gw.bodies.size();
        UUID otherOrg = UUID.randomUUID();
        UUID w2 = lab.gw.worker();
        assertTrue(lab.svc.pair(lab.url, lab.gw.pairingCode(otherOrg, w2).toCharArray()).accepted());
        lab.settle();
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        assertEquals(otherOrg.toString(), lab.svc.status().organizationId());
        assertTrue(lab.svc.authorizationsFor("sha256:" + PlatformFixtures.FP).isEmpty(), "nenhuma autorização antiga");
        assertEquals(ErrorCode.PLT_AUTHORIZATION_REFUSED, lab.svc.prepare(auth, "sha256:" + PlatformFixtures.FP).problem());
        assertTrue(lab.svc.runRequests().isEmpty(), "nenhum pedido antigo");
        assertEquals(DeliveryView.State.UNLINKED_UNDELIVERED, lab.svc.delivery(pending).state());
        assertEquals(runsBefore, lab.gw.runs.size(), "nada registrado pela credencial nova");
        assertEquals(decisionsBefore, lab.gw.runRequests.get(req).decisions, "nenhuma decisão enviada pelo vínculo novo");
        assertEquals(0, lab.svc.status().pendingRuns());
        // e a credencial nova nunca foi usada para a execução antiga (nem no caminho, nem no corpo)
        assertEquals(aboutPending, lab.gw.requests.stream().filter(r -> r.uri().getPath().contains(pending.value())).count());
        assertTrue(lab.gw.bodies.subList(bodiesBefore, lab.gw.bodies.size()).stream().noneMatch(b -> b.contains(pending.value())));
        assertTrue(lab.gw.run(pending.value()) == null, "a execução do vínculo anterior nunca foi registrada");
        lab.restart();
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        assertTrue(lab.svc.runRequests().isEmpty());
    }

    @Test
    @DisplayName("U14: renovação em andamento: a consulta não é feita; a renovação termina antes e o desvínculo sai com a credencial nova")
    void unlinkDuringRotation() {
        Lab lab = ready();
        assertTrue(lab.svc.rotate(lab.gw.rotationCode(agentOf(lab)).toCharArray()).accepted());
        assertNotNull(lab.secrets.text(CredentialVault.REDEEM));
        int calls = lab.gw.requests.size();
        MachineUnlink u = new MachineUnlink(lab.svc, () -> false);
        MachineUnlink.Plan plan = u.plan();
        assertTrue(plan.preview().rotationPending());
        assertEquals(ServerView.NOT_CONSULTED, plan.preview().server());
        assertEquals(calls, lab.gw.requests.size());
        PlatformService.UnlinkResult r = u.confirm(plan, false);
        assertTrue(r.pending(), "pendente: a renovação termina primeiro");
        assertEquals(calls, lab.gw.requests.size(), "nada enviado ainda");
        lab.settle();
        assertNull(lab.secrets.text(CredentialVault.REDEEM));
        assertNull(lab.secrets.text(CredentialVault.CREDENTIAL));
        assertEquals(1, lab.gw.count("POST", "/enrollments/redeem") - 1, "a renovação foi resgatada uma vez");
        assertEquals(1, lab.gw.count("POST", "/link/unlink"), "um desvínculo, com a credencial que valia");
        assertEquals(PlatformStatus.RemoteOutcome.CONFIRMED, lab.svc.status().unlink().concluded());
        assertEquals(LinkState.NOT_PAIRED, lab.svc.status().link());
    }

    @Test
    @DisplayName("U15: o lançador externo recusa novo monitoramento depois do desvínculo")
    void localGateRefusesAfterUnlink() {
        Lab lab = new Lab(dir);
        lab.paired();
        UUID automation = UUID.randomUUID();
        lab.gw.authorizeMonitored(lab.org, lab.worker, automation, PlatformFixtures.T0.plus(Duration.ofDays(3)));
        lab.svc.refreshNow();
        lab.tick(1);
        assertTrue(LocalGate.monitored(plataforma(lab), automation, lab.clock.instant()).isPresent());
        assertTrue(lab.svc.removeLocalLink().accepted());
        assertTrue(LocalGate.monitored(plataforma(lab), automation, lab.clock.instant()).isEmpty());
    }
}
