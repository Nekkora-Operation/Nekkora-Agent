package com.nekkora.agent.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.platform.FakeGateway.Fault;
import com.nekkora.agent.platform.PlatformFixtures.Lab;
import com.nekkora.agent.platform.PlatformService.LinkCheck;
import com.nekkora.agent.platform.PlatformService.LinkCheckOutcome;
import com.nekkora.agent.platform.PlatformStatus.LinkState;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-PACOTES-001 T02: confirmação ONLINE do vínculo para o assistente de importação, contra o transporte simulado (espelho do
 * contrato da API). Cada falha é distinta (403 não é rede nem revogação; 429 não é offline) e nada é criado na Platform: só
 * presença e leitura das autorizações saem. As falhas são INJETADAS.
 */
class PlatformLinkCheckTest {
    @TempDir
    Path dir;

    private Lab ready() {
        Lab lab = new Lab(dir);
        lab.paired();
        return lab;
    }

    @Test
    void notPairedAndPairingPending() {
        Lab lab = new Lab(dir);
        assertEquals(LinkCheckOutcome.NOT_PAIRED, lab.svc.confirmLink().outcome());
        assertTrue(lab.svc.currentLink().isEmpty());
        lab.worker = lab.gw.worker();
        assertTrue(lab.svc.pair(lab.url, lab.gw.pairingCode(lab.org, lab.worker).toCharArray()).accepted());
        assertEquals(LinkCheckOutcome.PAIRING_PENDING, lab.svc.confirmLink().outcome(), "resgate ainda sem resposta: não é vínculo");
        assertEquals(0, lab.gw.count("POST", "/heartbeat"));
    }

    @Test
    void confirmedReadsPartialCatalogAndCreatesNothing() {
        Lab lab = ready();
        UUID studio = lab.gw.authorize(lab.org, lab.worker, PlatformFixtures.FP, PlatformFixtures.T0.plus(Duration.ofDays(3)));
        UUID external = lab.gw.authorize(lab.org, lab.worker, "a".repeat(64), PackageFingerprint.EXTERNAL_RULE, PlatformFixtures.T0.plus(Duration.ofDays(3)));
        lab.gw.authorizeMonitored(lab.org, lab.worker, UUID.randomUUID(), PlatformFixtures.T0.plus(Duration.ofDays(3)));
        int before = lab.gw.requests.size();
        LinkCheck c = lab.svc.confirmLink();
        assertEquals(LinkCheckOutcome.CONFIRMED, c.outcome());
        assertEquals(lab.org, c.link().organizationId());
        assertEquals(FakeGateway.ORIGIN, c.link().origin());
        assertEquals(UUID.fromString(lab.svc.status().agentId()), c.link().agentId(), "a geração do vínculo é o Agent");
        assertNotNull(c.serverTime());
        assertTrue(c.catalog().available());
        assertEquals(2, c.catalog().items().size(), "monitoramento (sem versão) fica fora do catálogo");
        assertTrue(c.catalog().items().stream().anyMatch(a -> a.authorizationId().equals(studio)));
        assertTrue(c.catalog().items().stream().anyMatch(a -> a.authorizationId().equals(external) && a.fingerprint().equals("sha256:" + "a".repeat(64))));
        // só presença e leitura de autorizações
        var sent = lab.gw.requests.subList(before, lab.gw.requests.size());
        assertEquals(2, sent.size());
        assertTrue(sent.stream().allMatch(r -> r.uri().getPath().equals("/agent/api/v1/heartbeat") && r.method().equals("POST")
                || r.uri().getPath().equals("/agent/api/v1/authorizations") && r.method().equals("GET")));
        assertEquals(Optional.of(c.link()), lab.svc.currentLink());
    }

    @Test
    void eachFailureIsDistinct() {
        Lab lab = ready();
        lab.gw.fault("/heartbeat", Fault.DROP_BEFORE);
        assertEquals(LinkCheckOutcome.UNREACHABLE, lab.svc.confirmLink().outcome(), "sem resposta");
        lab.gw.fault("/heartbeat", Fault.UNAVAILABLE);
        assertEquals(LinkCheckOutcome.SERVER_ERROR, lab.svc.confirmLink().outcome());
        lab.gw.fault("/heartbeat", Fault.RATE_LIMITED);
        LinkCheck rl = lab.svc.confirmLink();
        assertEquals(LinkCheckOutcome.RATE_LIMITED, rl.outcome());
        assertEquals(Optional.of(30L), rl.retryAfterSeconds());
        lab.gw.fault("/heartbeat", Fault.FORBIDDEN);
        assertEquals(LinkCheckOutcome.FORBIDDEN, lab.svc.confirmLink().outcome(), "403 não é revogação nem rede");
        assertEquals(LinkState.PAIRED, lab.svc.status().link(), "403 não marca a credencial como recusada");
        lab.gw.fault("/heartbeat", Fault.MALFORMED);
        assertEquals(LinkCheckOutcome.INVALID_RESPONSE, lab.svc.confirmLink().outcome());
        // nenhuma dessas falhas desfaz o vínculo local
        assertTrue(lab.svc.currentLink().isPresent());
        assertEquals(LinkCheckOutcome.CONFIRMED, lab.svc.confirmLink().outcome());
    }

    @Test
    void catalogFailureIsUnavailableNotEmpty() {
        Lab lab = ready();
        lab.gw.fault("/authorizations", Fault.UNAVAILABLE);
        LinkCheck c = lab.svc.confirmLink();
        assertEquals(LinkCheckOutcome.CONFIRMED, c.outcome(), "a presença confirmou o vínculo");
        assertFalse(c.catalog().available(), "falha da consulta não é catálogo vazio");
        assertEquals(LinkCheckOutcome.SERVER_ERROR, c.catalog().problem());
    }

    @Test
    void revokedCredentialIsRefusedAndMarked() {
        Lab lab = ready();
        lab.gw.revokeAgent(UUID.fromString(lab.svc.status().agentId()));
        assertEquals(LinkCheckOutcome.CREDENTIAL_REFUSED, lab.svc.confirmLink().outcome());
        assertEquals(LinkState.REFUSED, lab.svc.status().link());
        assertTrue(lab.svc.currentLink().isEmpty(), "credencial recusada: o assistente não tem vínculo");
        int before = lab.gw.requests.size();
        assertEquals(LinkCheckOutcome.CREDENTIAL_REFUSED, lab.svc.confirmLink().outcome());
        assertEquals(before, lab.gw.requests.size(), "já recusada: nenhuma chamada nova");
    }

    @Test
    void unlinkPendingAndDestinationChangedBlock() {
        Lab lab = ready();
        lab.url = "https://outra.test";
        assertEquals(LinkCheckOutcome.DESTINATION_CHANGED, lab.svc.confirmLink().outcome());
        lab.url = FakeGateway.ORIGIN;
        lab.gw.fault("/link/unlink", Fault.DROP_BEFORE);
        PlatformService.UnlinkResult r = lab.svc.unlink(true, PlatformService.ServerView.NOT_CONSULTED);
        assertTrue(r.pending(), "desvínculo confirmado e ainda sem resposta");
        assertEquals(LinkCheckOutcome.UNLINK_PENDING, lab.svc.confirmLink().outcome());
        assertTrue(lab.svc.currentLink().isEmpty());
    }

    @Test
    void newPairingIsANewGeneration() {
        Lab lab = ready();
        LinkCheck first = lab.svc.confirmLink();
        lab.svc.unlink(true, PlatformService.ServerView.NOT_CONSULTED);
        assertTrue(lab.svc.pair(lab.url, lab.gw.pairingCode(lab.org, lab.worker).toCharArray()).accepted());
        lab.tick(2);
        LinkCheck second = lab.svc.confirmLink();
        assertEquals(LinkCheckOutcome.CONFIRMED, second.outcome());
        assertEquals(first.link().organizationId(), second.link().organizationId(), "mesma organização");
        assertFalse(first.link().equals(second.link()), "outro vínculo: o assistente recomeça");
    }
}
