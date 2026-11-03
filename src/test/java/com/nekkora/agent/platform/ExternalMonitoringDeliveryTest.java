package com.nekkora.agent.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.platform.PlatformFixtures.Lab;
import com.nekkora.agent.storage.AtomicWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-PYTHON-001 (Marco 1) no transporte, contra o gateway SIMULADO: a autorização de MONITORAMENTO chega sem versão e sem
 * impressão e é guardada; o lançador a enxerga só por leitura ({@link LocalGate}); a execução que ele grava (pedido de
 * vínculo + estado.json) vira UMA entrega, registrada SEM impressão, com relatórios e eventos; a perda e a volta da rede
 * não criam outra execução nem duplicam eventos; pedido de outro Agent é ignorado; item de monitoramento malformado é
 * recusado (falha fechada).
 */
class ExternalMonitoringDeliveryTest {
    @TempDir
    Path dir;

    final UUID automation = UUID.randomUUID();

    private Lab ready() {
        Lab lab = new Lab(dir);
        lab.paired();
        lab.gw.authorizeMonitored(lab.org, lab.worker, automation, PlatformFixtures.T0.plus(Duration.ofDays(30)));
        lab.svc.refreshNow();
        lab.tick(1);
        return lab;
    }

    private ExecutionId launcherRun(Lab lab, LocalGate.LinkRequest req, ExecutionState state) throws Exception {
        ExecutionId id = ExecutionId.random();
        Path d = lab.runs.dir(id);
        LocalGate.writeLinkRequest(d, req, AtomicWriter.DEFAULT); // primeiro, como o lançador
        save(lab, id, state);
        return id;
    }

    private void save(Lab lab, ExecutionId id, ExecutionState state) throws Exception {
        Instant created = lab.clock.instant();
        boolean started = state == ExecutionState.RUNNING || state.terminal();
        ErrorCode code = state == ExecutionState.SUCCEEDED ? ErrorCode.EXT_EXIT_ZERO_NO_SIGNAL : state == ExecutionState.FAILED ? ErrorCode.EXT_EXIT_NONZERO : null;
        lab.runs.save(new ExecutionRecord(id, ExecutionPackage.Kind.EXTERNAL_MONITORED, "python externo", null, state, code, created,
                started ? created.plusMillis(300) : null, state.terminal() ? created.plusSeconds(4) : null, null,
                state.terminal() ? (state == ExecutionState.FAILED ? 3 : 0) : null, Map.of(), 0, 0, false, 0).withOrigin(ExecutionRecord.Origin.EXTERNAL_SCHEDULER));
    }

    @Test
    void monitoramentoGuardadoLidoPeloLancadorEEntregueSemImpressao() throws Exception {
        Lab lab = ready();
        Optional<LocalGate.LinkRequest> gate = LocalGate.monitored(dir.resolve("plataforma"), automation, lab.clock.instant());
        assertTrue(gate.isPresent(), "o lançador enxerga a autorização guardada");
        assertNull(gate.get().fingerprint());
        assertEquals("EXTERNAL_MONITORED", gate.get().sourceKind());
        assertTrue(LocalGate.monitored(dir.resolve("plataforma"), UUID.randomUUID(), lab.clock.instant()).isEmpty(), "outra automação: nada");
        // depois do prazo, pelo relógio local: nada (sem autorização offline inventada)
        assertTrue(LocalGate.monitored(dir.resolve("plataforma"), automation, lab.clock.instant().plus(Duration.ofDays(31))).isEmpty());

        // a primeira resposta do registro se perde DEPOIS do commit: o reenvio devolve a mesma execução
        lab.gw.fault("/agent/api/v1/runs", FakeGateway.Fault.DROP_AFTER_COMMIT);
        ExecutionId id = launcherRun(lab, gate.get(), ExecutionState.RUNNING);
        lab.settle();
        FakeGateway.Run run = lab.gw.run(id.value());
        assertTrue(run != null, "registrada");
        assertNull(run.fingerprint, "registro sem impressão");
        String register = lab.gw.bodies.stream().filter(b -> b.contains("\"agentExecutionId\":\"" + id.value())).findFirst().orElseThrow();
        assertFalse(register.contains("packageFingerprint"), register);

        // rede cai no meio: nada muda localmente e o reenvio não cria outra execução
        lab.gw.fault("/supervisor", FakeGateway.Fault.DROP_BEFORE, FakeGateway.Fault.DROP_AFTER_COMMIT);
        lab.gw.fault("/events", FakeGateway.Fault.DROP_AFTER_COMMIT);
        save(lab, id, ExecutionState.SUCCEEDED);
        lab.events(id, 1, 3);
        lab.append(id, java.util.List.of(lab.closing(id, 4)));
        lab.settle();
        assertEquals(1, lab.gw.runs.size(), "uma única execução na Platform");
        assertEquals("SUCCEEDED", lab.gw.run(id.value()).state);
        assertEquals(4, lab.gw.run(id.value()).events.size(), "quatro eventos, sem duplicata");
        assertEquals(DeliveryView.State.DELIVERED, lab.svc.delivery(id).state());
        assertTrue(lab.gw.count("POST", "/runs") >= 1);
    }

    @Test
    void pedidoDeOutroAgentOuSemEstadoNaoViraEntrega() throws Exception {
        Lab lab = ready();
        LocalGate.LinkRequest mine = LocalGate.monitored(dir.resolve("plataforma"), automation, lab.clock.instant()).orElseThrow();
        LocalGate.LinkRequest other = new LocalGate.LinkRequest(UUID.randomUUID(), mine.authorizationId(), mine.sourceKind(), null, null, mine.at());
        ExecutionId foreign = launcherRun(lab, other, ExecutionState.RUNNING);
        ExecutionId noRecord = ExecutionId.random();
        LocalGate.writeLinkRequest(lab.runs.dir(noRecord), mine, AtomicWriter.DEFAULT);
        lab.tick(3);
        assertEquals(DeliveryView.State.NOT_LINKED, lab.svc.delivery(foreign).state());
        assertEquals(DeliveryView.State.NOT_LINKED, lab.svc.delivery(noRecord).state());
        assertEquals(0, lab.gw.runs.size());
        // o estado.json chega depois (o lançador grava o pedido primeiro): agora vira entrega
        save(lab, noRecord, ExecutionState.STARTING);
        lab.tick(2);
        assertEquals(1, lab.gw.runs.size());
    }

    @Test
    void itemDeMonitoramentoMalformadoERecusado() throws Exception {
        assertNull(AuthorizationCache.parseItem(Json.parse("{\"authorizationId\":\"" + UUID.randomUUID() + "\",\"automationId\":\"" + UUID.randomUUID()
                + "\",\"validUntil\":\"2026-12-01T00:00:00Z\",\"sourceKind\":\"EXTERNAL_MONITORED\",\"packageFingerprint\":\"" + PlatformFixtures.FP
                + "\",\"packageFingerprintRule\":\"nekkora-agent/package-fingerprint/1\"}")), "monitoramento com impressão inventada");
        assertNull(AuthorizationCache.parseItem(Json.parse("{\"authorizationId\":\"" + UUID.randomUUID()
                + "\",\"validUntil\":\"2026-12-01T00:00:00Z\",\"sourceKind\":\"EXTERNAL_MONITORED\"}")), "sem automação");
        assertNull(AuthorizationCache.parseItem(Json.parse("{\"authorizationId\":\"" + UUID.randomUUID() + "\",\"automationId\":\"" + UUID.randomUUID()
                + "\",\"validUntil\":\"2026-12-01T00:00:00Z\",\"sourceKind\":\"OUTRA\"}")), "origem desconhecida");
        // item de pacote de um servidor anterior (sem sourceKind) continua aceito como STUDIO_PACKAGE
        AuthorizationCache.Authorization old = AuthorizationCache.parseItem(Json.parse("{\"authorizationId\":\"" + UUID.randomUUID()
                + "\",\"validUntil\":\"2026-12-01T00:00:00Z\",\"packageFingerprint\":\"" + PlatformFixtures.FP
                + "\",\"packageFingerprintRule\":\"nekkora-agent/package-fingerprint/1\"}"));
        assertEquals("STUDIO_PACKAGE", old.sourceKind());
    }

    @Test
    void lancadorNaoEnxergaAutorizacaoComVinculoRecusadoOuSemVinculo() throws Exception {
        assertTrue(LocalGate.monitored(dir.resolve("plataforma"), automation, Instant.now()).isEmpty(), "sem vínculo");
        Lab lab = ready();
        Path vinculo = dir.resolve("plataforma").resolve(CredentialVault.FILE);
        String text = Files.readString(vinculo);
        Files.writeString(vinculo, text.replaceFirst("\"refused\"\\s*:\\s*false", "\"refused\": true"));
        assertTrue(LocalGate.monitored(dir.resolve("plataforma"), automation, lab.clock.instant()).isEmpty(), "credencial recusada");
    }
}
