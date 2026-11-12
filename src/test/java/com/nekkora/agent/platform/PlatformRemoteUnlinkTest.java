package com.nekkora.agent.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.app.MachineUnlink;
import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.core.FaultPoints;
import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.platform.FakeGateway.Fault;
import com.nekkora.agent.platform.PlatformFixtures.Lab;
import com.nekkora.agent.platform.PlatformService.ServerView;
import com.nekkora.agent.platform.PlatformStatus.LinkState;
import com.nekkora.agent.platform.PlatformStatus.RemoteOutcome;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLATFORM-AGENT-V2-004 T13: "Desvincular esta máquina" também na Platform (protocolo: docs/platform-agent-v2-004/
 * t13-desvinculo-remoto.md, na API), contra o TRANSPORTE SIMULADO ({@link FakeGateway}, espelho do contrato da API). Pedido
 * durável antes do envio, mesma operação em toda repetição, conclusão só com resposta conclusiva (200, 409 "já encerrado",
 * 404 de uma Platform anterior), nada novo pelo vínculo antigo enquanto pendente. As quedas são falhas INJETADAS.
 */
class PlatformRemoteUnlinkTest {
    @TempDir
    Path dir;

    @AfterEach
    void noFaults() {
        FaultPoints.replaceForTests(null);
    }

    private Path plataforma(Lab lab) {
        return lab.root.resolve("plataforma");
    }

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

    private List<String> unlinkBodies(Lab lab) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < lab.gw.requests.size(); i++) {
            if (lab.gw.requests.get(i).uri().getPath().equals("/agent/api/v1/link/unlink")) out.add(lab.gw.bodies.get(i));
        }
        return out;
    }

    private static String op(String body) {
        int i = body.indexOf("\"operationId\":\"") + 15;
        return body.substring(i, i + 36);
    }

    /** O vínculo antigo não inicia nada: tela, pedidos da Platform, lançador externo; o pareamento também é recusado. */
    private void nothingNewStarts(Lab lab, UUID auth) {
        assertEquals(ErrorCode.PLT_UNLINK_REMOTE_PENDING, lab.svc.prepare(auth, "sha256:" + PlatformFixtures.FP).problem());
        assertFalse(lab.svc.authorizationUsable(auth, PlatformFixtures.FP, "nekkora-agent/package-fingerprint/1"));
        assertTrue(LocalGate.pairedAgent(plataforma(lab)).isEmpty(), "o lançador externo (outro processo) não acha vínculo");
        assertEquals(ErrorCode.PLT_UNLINK_IN_PROGRESS, lab.svc.pair(lab.url, lab.gw.pairingCode(lab.org, lab.gw.worker()).toCharArray()).problem());
    }

    // =========================================================================== T13-2

    @Test
    @DisplayName("T13-2: online: a Platform confirma, a credencial antiga perde o acesso, o Worker fica livre e um novo pareamento funciona")
    void onlineConfirmedAndRepairWorks() throws Exception {
        Lab lab = ready();
        UUID agent = agentOf(lab);
        AtomicInteger ended = new AtomicInteger();
        lab.svc.onLinkEnded(ended::incrementAndGet);
        MachineUnlink u = new MachineUnlink(lab.svc, () -> false);
        PlatformService.UnlinkResult r = u.confirm(u.plan(), false);
        assertTrue(r.done());
        assertEquals(RemoteOutcome.CONFIRMED, r.remote());
        assertEquals(1, ended.get(), "o app apaga as aprovações locais uma vez, na conclusão");
        FakeGateway.Agent a = lab.gw.agents.get(agent);
        assertFalse(a.active || a.assigned, "Worker sem esta máquina na Platform");
        assertTrue(lab.gw.credentials.values().stream().filter(c -> c.agent.equals(agent)).allMatch(c -> c.revoked && "MACHINE".equals(c.revokedBy)));
        assertEquals(LinkState.NOT_PAIRED, lab.svc.status().link());
        assertEquals(RemoteOutcome.CONFIRMED, lab.svc.status().unlink().concluded());
        // novo pareamento no MESMO Worker (agora livre lá): outro Agent, que trabalha
        assertTrue(lab.svc.pair(lab.url, lab.gw.pairingCode(lab.org, lab.worker).toCharArray()).accepted());
        lab.tick(2);
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        assertFalse(agent.toString().equals(lab.svc.status().agentId()));
    }

    // =========================================================================== T13-4 e T13-5

    @Test
    @DisplayName("T13-4/5: sem rede: pedido durável, nada liberado nem iniciado; reinício mantém; ao reconectar conclui UMA vez com a mesma operação")
    void offlineDurableThenConcludesOnce() throws Exception {
        Lab lab = ready();
        UUID agent = agentOf(lab);
        UUID auth = lab.gw.authorizations.keySet().iterator().next();
        lab.gw.fault("/link/unlink", Fault.DROP_BEFORE, Fault.DROP_BEFORE, Fault.DROP_BEFORE);
        MachineUnlink u = new MachineUnlink(lab.svc, () -> false);
        PlatformService.UnlinkResult r = u.confirm(u.plan(), false);
        assertFalse(r.done());
        assertTrue(r.pending());
        assertNull(r.remote(), "nunca 'Worker liberado' sem a Platform");
        assertTrue(Files.exists(plataforma(lab).resolve(UnlinkRequestStore.FILE)));
        String saved = Files.readString(plataforma(lab).resolve(UnlinkRequestStore.FILE), StandardCharsets.UTF_8);
        assertFalse(saved.contains("nka1") || saved.contains(lab.secrets.text(CredentialVault.CREDENTIAL)), "sem segredo no pedido");
        assertEquals(LinkState.UNLINKING, lab.svc.status().link());
        assertTrue(lab.svc.status().unlink().pending());
        assertEquals("SEM_RESPOSTA", lab.svc.status().unlink().lastResult());
        assertNotNull(lab.secrets.text(CredentialVault.CREDENTIAL), "a credencial fica até a conclusão (é a prova da máquina)");
        assertTrue(lab.gw.agents.get(agent).active, "na Platform nada mudou ainda");
        nothingNewStarts(lab, auth);
        // só o desvínculo sai pela rede, espaçado (sem presença, pedidos ou entregas do vínculo antigo)
        long heartbeats = lab.gw.count("POST", "/heartbeat");
        lab.tick(5);
        assertEquals(1, unlinkBodies(lab).size(), "a próxima tentativa respeita o intervalo");
        // reinício do Agent: o pedido continua, e continua bloqueando
        lab.restart();
        assertEquals(LinkState.UNLINKING, lab.svc.status().link());
        assertEquals(1, lab.svc.status().unlink().attempts());
        nothingNewStarts(lab, auth);
        // conexão de volta (as duas quedas restantes e depois a resposta): conclui UMA vez, sempre a MESMA operação
        lab.settle();
        assertEquals(LinkState.NOT_PAIRED, lab.svc.status().link());
        assertEquals(RemoteOutcome.CONFIRMED, lab.svc.status().unlink().concluded());
        List<String> bodies = unlinkBodies(lab);
        assertEquals(4, bodies.size());
        assertTrue(bodies.stream().map(PlatformRemoteUnlinkTest::op).distinct().count() == 1, "uma operação só");
        assertEquals(op(bodies.get(0)), lab.gw.unlinkOps.get(agent).toString());
        assertEquals(heartbeats, lab.gw.count("POST", "/heartbeat"), "nenhuma presença pelo vínculo antigo enquanto pendente");
        assertFalse(Files.exists(plataforma(lab).resolve(UnlinkRequestStore.FILE)));
        assertNull(lab.secrets.text(CredentialVault.CREDENTIAL));
        int calls = lab.gw.requests.size();
        lab.settle();
        assertEquals(calls, lab.gw.requests.size(), "nada mais pela identidade anterior");
    }

    // =========================================================================== T13-6

    @Test
    @DisplayName("T13-6: resposta perdida DEPOIS do commit na Platform: a mesma operação é repetida e reconcilia (200), sem novo efeito")
    void lostResponseAfterCommitReconciles() {
        Lab lab = ready();
        UUID agent = agentOf(lab);
        lab.gw.fault("/link/unlink", Fault.DROP_AFTER_COMMIT);
        MachineUnlink u = new MachineUnlink(lab.svc, () -> false);
        PlatformService.UnlinkResult r = u.confirm(u.plan(), false);
        assertTrue(r.pending(), "sem resposta: pendente, mesmo com o servidor já concluído");
        assertTrue(lab.gw.unlinkOps.containsKey(agent), "o servidor concluiu (commit)");
        UUID serverOp = lab.gw.unlinkOps.get(agent);
        lab.settle();
        assertEquals(RemoteOutcome.CONFIRMED, lab.svc.status().unlink().concluded(), "a repetição da MESMA operação é aceita como conclusão");
        assertEquals(serverOp, lab.gw.unlinkOps.get(agent), "nenhuma operação nova");
        assertEquals(2, unlinkBodies(lab).size());
        assertEquals(serverOp.toString(), op(unlinkBodies(lab).get(1)));
    }

    @Test
    @DisplayName("T13-6: a resposta perdida pelo ponto de falha do laboratório (UNLINK_RESPONSE_RECEIVED) também reconcilia")
    void lostResponseByLabFaultPoint() {
        Lab lab = ready();
        AtomicInteger hits = new AtomicInteger();
        FaultPoints.replaceForTests(p -> p == FaultPoints.Point.UNLINK_RESPONSE_RECEIVED && hits.incrementAndGet() == 1);
        MachineUnlink u = new MachineUnlink(lab.svc, () -> false);
        assertTrue(u.confirm(u.plan(), false).pending());
        lab.settle();
        assertEquals(RemoteOutcome.CONFIRMED, lab.svc.status().unlink().concluded());
        assertEquals(2, hits.get());
        assertEquals(1, lab.gw.unlinkOps.size());
    }

    @Test
    @DisplayName("T13-6: um 401 isolado não prova o desvínculo (fica pendente); o 409 'revogado pela administração' conclui")
    void isolated401IsNotProof() {
        Lab lab = ready();
        UUID agent = agentOf(lab);
        // a credencial desta máquina foi substituída na Platform sem a máquina saber (ex.: renovação concluída em outro lugar)
        for (FakeGateway.Credential c : lab.gw.credentials.values()) {
            if (c.agent.equals(agent)) {
                c.revoked = true;
                c.revokedBy = "ROTATION";
            }
        }
        MachineUnlink u = new MachineUnlink(lab.svc, () -> false);
        PlatformService.UnlinkResult r = u.confirm(u.plan(), false);
        assertTrue(r.pending());
        assertEquals("CREDENCIAL_RECUSADA", lab.svc.status().unlink().lastResult());
        lab.clock.advance(Duration.ofMinutes(6));
        lab.tick(1);
        assertEquals(LinkState.UNLINKING, lab.svc.status().link(), "continua pendente: 401 não é conclusão");
        // a administração revoga na Web: a próxima tentativa recebe a resposta conclusiva
        for (FakeGateway.Credential c : lab.gw.credentials.values()) if (c.agent.equals(agent)) c.revokedBy = "ADMIN";
        lab.clock.advance(Duration.ofMinutes(6));
        lab.tick(1);
        assertEquals(RemoteOutcome.ENDED_BY_ADMINISTRATION, lab.svc.status().unlink().concluded());
        assertEquals(LinkState.NOT_PAIRED, lab.svc.status().link());
    }

    // =========================================================================== T13-8 / T13-13

    @Test
    @DisplayName("T13-8: vínculo já revogado pela administração: 409 conclusivo, sem segunda autoria")
    void alreadyRevokedByAdministration() {
        Lab lab = ready();
        UUID agent = agentOf(lab);
        lab.gw.revokeAgent(agent);
        PlatformService.UnlinkResult r = lab.svc.unlink(false, ServerView.NOT_CONSULTED);
        assertTrue(r.done());
        assertEquals(RemoteOutcome.ENDED_BY_ADMINISTRATION, r.remote());
        assertFalse(lab.gw.unlinkOps.containsKey(agent));
    }

    @Test
    @DisplayName("T13-13: Platform anterior (sem a rota, 404): avisa que a conclusão remota não é suportada e desvincula só aqui")
    void olderPlatformIsUnsupported() {
        Lab lab = ready();
        UUID agent = agentOf(lab);
        lab.gw.unlinkSupported = false;
        PlatformService.UnlinkResult r = lab.svc.unlink(false, ServerView.NOT_CONSULTED);
        assertTrue(r.done());
        assertEquals(RemoteOutcome.UNSUPPORTED, r.remote());
        assertEquals(ErrorCode.PLT_UNLINK_UNSUPPORTED.code(), "AGT-PLT-034");
        assertTrue(lab.gw.agents.get(agent).active, "a Platform anterior não mudou nada (a revogação continua sendo em Workers)");
        assertEquals(LinkState.NOT_PAIRED, lab.svc.status().link());
        assertEquals(RemoteOutcome.UNSUPPORTED, lab.svc.status().unlink().concluded());
    }

    // =========================================================================== T13-10 / T13-11

    @Test
    @DisplayName("T13-10: pedido da Platform aceito e INICIADO é informado (fica aceito lá); os não iniciados não começam depois do pedido")
    void runRequestsLaunchedAreReportedAndOthersNeverStart() {
        Lab lab = ready();
        UUID agent = agentOf(lab);
        UUID auth = lab.gw.authorizations.keySet().iterator().next();
        UUID started = lab.gw.request(agent, auth, lab.clock.instant().plus(Duration.ofHours(1)));
        UUID waiting = lab.gw.request(agent, auth, lab.clock.instant().plus(Duration.ofHours(1)));
        lab.clock.advance(Duration.ofMinutes(6));
        lab.tick(1);
        assertEquals(2, lab.svc.runRequests().size());
        ExecutionId ex = ExecutionId.random();
        assertTrue(lab.svc.decideRunRequest(started, "ACCEPTED", null, ex).accepted());
        lab.tick(1);
        lab.svc.runRequestLaunched(started);
        assertTrue(lab.svc.linkRequestFor(waiting).isPresent(), "antes do desvínculo, o pedido ainda pode ser lançado");
        lab.gw.fault("/link/unlink", Fault.DROP_BEFORE);
        assertTrue(lab.svc.unlink(false, ServerView.NOT_CONSULTED).pending());
        assertTrue(lab.svc.linkRequestFor(waiting).isEmpty(), "depois do pedido gravado, nada mais é lançado pelo vínculo antigo");
        lab.settle();
        assertEquals(List.of(started), lab.gw.reportedLaunched);
        assertEquals("ACCEPTED", lab.gw.runRequests.get(started).state, "iniciado de fato: continua aceito (o registro não chegou)");
        assertEquals("REFUSED", lab.gw.runRequests.get(waiting).state);
        assertEquals("AGENT_UNLINKED", lab.gw.runRequests.get(waiting).reason);
    }

    @Test
    @DisplayName("T13-11: entregas pendentes: 'Aguardar' mantém tudo; 'mesmo assim' guarda como NÃO entregues e nunca as envia")
    void pendingDeliveriesAreKeptUndelivered() throws Exception {
        Lab lab = ready();
        UUID auth = lab.gw.authorizations.keySet().iterator().next();
        ExecutionId id = lab.execution(ExecutionState.SUCCEEDED, lab.clock.instant());
        lab.events(id, 1, 3);
        lab.gw.fault("/agent/api/v1/runs", Fault.UNAVAILABLE, Fault.UNAVAILABLE);
        lab.link(id, auth);
        lab.tick(1);
        assertEquals(1, lab.svc.status().pendingRuns());
        PlatformService.UnlinkResult wait = lab.svc.unlink(false, ServerView.NOT_CONSULTED);
        assertEquals(ErrorCode.PLT_UNLINK_PENDING, wait.problem());
        assertFalse(Files.exists(plataforma(lab).resolve(UnlinkRequestStore.FILE)), "aguardar: nada gravado");
        assertEquals(0, unlinkBodies(lab).size());
        PlatformService.UnlinkResult r = lab.svc.unlink(true, ServerView.NOT_CONSULTED);
        assertTrue(r.done());
        assertEquals(1, r.undeliveredRuns());
        assertEquals(DeliveryView.State.UNLINKED_UNDELIVERED, lab.svc.delivery(id).state());
        lab.settle();
        assertTrue(lab.gw.run(id.value()) == null, "o envio pendente nunca sai");
    }

    @Test
    @DisplayName("T13-10: corrida: uma execução que começa DEPOIS da confirmação e ANTES do pedido gravado desfaz o pedido; nada muda")
    void raceWithAStartWithdrawsTheRequest() {
        Lab lab = ready();
        UUID agent = agentOf(lab);
        AtomicInteger checks = new AtomicInteger();
        // conferências: no plano (parada), na confirmação (parada) e DEPOIS do pedido gravado (um início passou pela trava antes)
        MachineUnlink u = new MachineUnlink(lab.svc, () -> checks.incrementAndGet() >= 3);
        MachineUnlink.Plan plan = u.plan();
        assertFalse(plan.running());
        PlatformService.UnlinkResult r = u.confirm(plan, false);
        assertEquals(3, checks.get());
        assertEquals(ErrorCode.PLT_UNLINK_RUNNING, r.problem());
        assertFalse(r.pending() || r.done());
        assertFalse(Files.exists(plataforma(lab).resolve(UnlinkRequestStore.FILE)), "o pedido saiu");
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        assertTrue(LocalGate.pairedAgent(plataforma(lab)).isPresent(), "o vínculo continua");
        assertEquals(0, unlinkBodies(lab).size(), "nada enviado à Platform");
        assertTrue(lab.gw.agents.get(agent).active);
    }

    @Test
    @DisplayName("T13-10: corrida do outro lado: a intenção pré-início gravada depois do pedido é recusada (o início desiste)")
    void startAfterRequestIsRefused() {
        Lab lab = ready();
        UUID auth = lab.gw.authorizations.keySet().iterator().next();
        lab.gw.fault("/link/unlink", Fault.DROP_BEFORE);
        assertTrue(lab.svc.unlink(false, ServerView.NOT_CONSULTED).pending());
        assertEquals(ErrorCode.PLT_UNLINK_REMOTE_PENDING, lab.svc.prepare(auth, "sha256:" + PlatformFixtures.FP).problem());
        assertFalse(Files.exists(plataforma(lab).resolve(DeliveryStore.DIR).resolve(DeliveryStore.INTENT)), "nenhuma intenção fica");
    }

    // =========================================================================== T13-12

    @Test
    @DisplayName("T13-12: credencial antiga e presença atrasada não reativam o vínculo; uma operação antiga não toca o vínculo novo")
    void lateCallsNeverRevive() {
        Lab lab = ready();
        UUID old = agentOf(lab);
        lab.gw.fault("/link/unlink", Fault.DROP_AFTER_COMMIT);
        assertTrue(lab.svc.unlink(false, ServerView.NOT_CONSULTED).pending());
        // o pedido pendente (já concluído no servidor) é repetido; enquanto isso, nenhuma outra rota é usada
        lab.settle();
        assertEquals(LinkState.NOT_PAIRED, lab.svc.status().link());
        assertTrue(lab.svc.pair(lab.url, lab.gw.pairingCode(lab.org, lab.worker).toCharArray()).accepted());
        lab.tick(2);
        UUID fresh = agentOf(lab);
        assertFalse(old.equals(fresh));
        lab.settle();
        assertTrue(lab.gw.agents.get(fresh).active && lab.gw.agents.get(fresh).assigned);
        assertFalse(lab.gw.agents.get(old).active || lab.gw.agents.get(old).assigned);
        assertEquals(1, lab.gw.unlinkOps.size());
    }

    @Test
    @DisplayName("T13: pedido ilegível no disco: na dúvida, o vínculo antigo continua bloqueado e nada é enviado")
    void unreadableRequestKeepsBlocked() throws Exception {
        Lab lab = ready();
        UUID auth = lab.gw.authorizations.keySet().iterator().next();
        Files.writeString(plataforma(lab).resolve(UnlinkRequestStore.FILE), "{quebrado", StandardCharsets.UTF_8);
        lab.restart();
        assertEquals(LinkState.UNLINKING, lab.svc.status().link());
        assertEquals("PEDIDO_ILEGIVEL", lab.svc.status().unlink().lastResult());
        int calls = lab.gw.requests.size();
        lab.settle();
        assertEquals(calls, lab.gw.requests.size());
        nothingNewStarts(lab, auth);
    }
}
