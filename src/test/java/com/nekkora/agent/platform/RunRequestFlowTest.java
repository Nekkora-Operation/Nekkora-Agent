package com.nekkora.agent.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.platform.PlatformFixtures.Lab;
import com.nekkora.agent.storage.AtomicWriter;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-PYTHON-001 (Marco 2) no transporte, contra o gateway SIMULADO: o pedido é gravado ao chegar, a decisão é gravada
 * ANTES do envio e reenviada até a resposta; só uma aceitação CONFIRMADA libera o lançamento; o mesmo pedido reentregue (ou
 * com a confirmação perdida) nunca gera outra decisão; dados locais perdidos com o pedido aceito na Platform viram
 * NOT_STARTED (nunca relançado); a execução registrada pelo pedido o liga (RUN_LINKED).
 */
class RunRequestFlowTest {
    static final String EXT_FP = "e71ca1e0aedd2842d09809d5634134f3f23fb4d2ca0c5d90c370bb15474595f9";

    @TempDir
    java.nio.file.Path dir;

    @AfterEach
    void clearLab() {
        System.clearProperty(PlatformService.LAB_LOSE_DECISION_RESPONSE);
        PlatformService.LOST_DECISION_RESPONSE.set(false);
    }

    UUID agent;
    UUID authorization;

    private Lab ready() {
        Lab lab = new Lab(dir);
        agent = lab.paired();
        authorization = lab.gw.authorize(lab.org, lab.worker, EXT_FP, PackageFingerprint.EXTERNAL_RULE, PlatformFixtures.T0.plus(Duration.ofDays(30)));
        lab.svc.refreshNow();
        lab.tick(1);
        return lab;
    }

    private PlatformService.RunRequestView view(Lab lab, UUID id) {
        return lab.svc.runRequests().stream().filter(v -> v.runRequestId().equals(id)).findFirst().orElse(null);
    }

    @Test
    void pedidoGravadoDecisaoAntesDoEnvioESoAceitacaoConfirmadaLibera() {
        Lab lab = ready();
        UUID q = lab.gw.request(agent, authorization, lab.clock.instant().plus(Duration.ofMinutes(15)));
        lab.clock.advance(Duration.ofSeconds(11));
        lab.tick(1);
        assertEquals("RECEIVED", view(lab, q).stage());
        assertEquals("sha256:" + EXT_FP, view(lab, q).fingerprint());
        assertTrue(lab.svc.authorizationUsable(authorization, EXT_FP, PackageFingerprint.EXTERNAL_RULE));
        ExecutionId reserved = ExecutionId.random();
        assertTrue(lab.svc.decideRunRequest(q, "ACCEPTED", null, reserved).accepted());
        assertFalse(lab.svc.decideRunRequest(q, "REFUSED", "BUSY", null).accepted(), "uma segunda decisão sobre o mesmo pedido é recusada");
        assertEquals("DECIDED", view(lab, q).stage(), "gravada antes do envio");
        lab.tick(1);
        assertEquals("CONFIRMED", view(lab, q).stage());
        assertEquals("ACCEPTED", lab.gw.runRequests.get(q).state);
        assertEquals(reserved.value(), lab.gw.runRequests.get(q).execution);
        // reentrega: a Platform continua listando o pedido aceito; a máquina não decide de novo
        for (int i = 0; i < 3; i++) {
            lab.clock.advance(Duration.ofSeconds(11));
            lab.tick(1);
        }
        assertEquals("CONFIRMED", view(lab, q).stage());
        assertEquals(1, lab.gw.runRequests.get(q).decisions);
    }

    @Test
    void confirmacaoPerdidaReenviaAMesmaDecisaoSemOutroLancamento() {
        Lab lab = ready();
        System.setProperty(PlatformService.LAB_LOSE_DECISION_RESPONSE, "true");
        UUID q = lab.gw.request(agent, authorization, lab.clock.instant().plus(Duration.ofMinutes(15)));
        lab.clock.advance(Duration.ofSeconds(11));
        lab.tick(1);
        ExecutionId reserved = ExecutionId.random();
        lab.svc.decideRunRequest(q, "ACCEPTED", null, reserved);
        lab.tick(1);
        assertEquals("ACCEPTED", lab.gw.runRequests.get(q).state, "a Platform gravou");
        assertEquals("DECIDED", view(lab, q).stage(), "a resposta se perdeu: a máquina ainda não pode lançar");
        lab.settle();
        assertEquals("CONFIRMED", view(lab, q).stage());
        assertEquals(2, lab.gw.runRequests.get(q).decisions, "a mesma decisão reenviada (UNCHANGED)");
        assertEquals(reserved.value(), lab.gw.runRequests.get(q).execution);
    }

    @Test
    void naoIniciadoVencidoEDadosLocaisPerdidos() throws Exception {
        Lab lab = ready();
        UUID q = lab.gw.request(agent, authorization, lab.clock.instant().plus(Duration.ofMinutes(15)));
        lab.clock.advance(Duration.ofSeconds(11));
        lab.tick(1);
        ExecutionId reserved = ExecutionId.random();
        lab.svc.decideRunRequest(q, "ACCEPTED", null, reserved);
        lab.tick(1);
        lab.svc.runRequestNotStarted(q);
        lab.tick(1);
        assertEquals("NOT_STARTED", lab.gw.runRequests.get(q).state);
        assertEquals("CLOSED", view(lab, q).stage());

        // vencido sem decisão: a Platform não o entrega mais; nada é recebido
        UUID old = lab.gw.request(agent, authorization, lab.clock.instant().plus(Duration.ofSeconds(30)));
        lab.clock.advance(Duration.ofMinutes(2));
        lab.tick(2);
        assertEquals("EXPIRED", lab.gw.runRequests.get(old).state);
        assertEquals(null, view(lab, old));

        // aceito na Platform e registro local perdido: nunca relançado (NOT_STARTED, sem pasta de execução)
        UUID lost = lab.gw.request(agent, authorization, lab.clock.instant().plus(Duration.ofMinutes(15)));
        lab.clock.advance(Duration.ofSeconds(11));
        lab.tick(1);
        lab.svc.decideRunRequest(lost, "ACCEPTED", null, ExecutionId.random());
        lab.tick(1);
        Files.delete(dir.resolve("plataforma").resolve("pedidos").resolve(lost + ".json"));
        lab.clock.advance(Duration.ofSeconds(11));
        lab.tick(2);
        assertEquals("NOT_STARTED", lab.gw.runRequests.get(lost).state);
    }

    @Test
    void execucaoDoPedidoRegistradaComOPedidoLigaNaPlatform() throws Exception {
        Lab lab = ready();
        UUID q = lab.gw.request(agent, authorization, lab.clock.instant().plus(Duration.ofMinutes(15)));
        lab.clock.advance(Duration.ofSeconds(11));
        lab.tick(1);
        ExecutionId id = ExecutionId.random();
        lab.svc.decideRunRequest(q, "ACCEPTED", null, id);
        lab.tick(1);
        // o que o lançamento grava: pedido de vínculo (antes do STARTING) e o registro
        LocalGate.writeLinkRequest(lab.runs.dir(id), lab.svc.linkRequestFor(q).orElseThrow(), AtomicWriter.DEFAULT);
        lab.runs.save(new ExecutionRecord(id, ExecutionPackage.Kind.EXTERNAL_PYTHON, "pacote e71ca1e0aedd", "sha256:" + EXT_FP, ExecutionState.READY, null,
                lab.clock.instant(), null, null, null, null, Map.of(), 0, 0, false, 0).withOrigin(ExecutionRecord.Origin.PLATFORM_REQUEST)
                .moveTo(ExecutionState.STARTING, null, lab.clock.instant()));
        lab.svc.runRequestLaunched(q);
        lab.tick(2);
        assertEquals("RUN_LINKED", lab.gw.runRequests.get(q).state);
        FakeGateway.Run run = lab.gw.run(id.value());
        assertEquals(q, run.runRequest);
        assertEquals(EXT_FP, run.fingerprint);
        assertEquals("LAUNCHED", view(lab, q).stage());
        assertEquals(List.of(), lab.gw.runRequests.values().stream().filter(r -> r.decisions > 1).toList());
    }
}
