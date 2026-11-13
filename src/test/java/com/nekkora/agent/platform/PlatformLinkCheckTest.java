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
 * presença e leituras (autorizações e catálogo) saem. As falhas são INJETADAS.
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
    void confirmedReadsCatalogAndCreatesNothing() {
        Lab lab = ready();
        lab.gw.authorize(lab.org, lab.worker, PlatformFixtures.FP, PlatformFixtures.T0.plus(Duration.ofDays(3)));
        lab.gw.authorize(lab.org, lab.worker, "a".repeat(64), PackageFingerprint.EXTERNAL_RULE, PlatformFixtures.T0.plus(Duration.ofDays(3)));
        lab.gw.authorizeMonitored(lab.org, lab.worker, UUID.randomUUID(), PlatformFixtures.T0.plus(Duration.ofDays(3)));
        int before = lab.gw.requests.size();
        LinkCheck c = lab.svc.confirmLink();
        assertEquals(LinkCheckOutcome.CONFIRMED, c.outcome());
        assertEquals(lab.org, c.link().organizationId());
        assertEquals(FakeGateway.ORIGIN, c.link().origin());
        assertEquals(UUID.fromString(lab.svc.status().agentId()), c.link().agentId(), "a geração do vínculo é o Agent");
        assertNotNull(c.serverTime());
        assertEquals(RemoteCatalog.State.COMPLETE, c.catalog().state());
        assertEquals(3, c.catalog().projects().size(), "monitoramento aparece sem versão elegível");
        assertEquals(1, c.catalog().projects().stream().filter(p -> p.versions().isEmpty()).count());
        // só presença e leituras (autorizações e catálogo)
        var sent = lab.gw.requests.subList(before, lab.gw.requests.size());
        assertEquals(3, sent.size());
        assertTrue(sent.stream().allMatch(r -> r.uri().getPath().equals("/agent/api/v1/heartbeat") && r.method().equals("POST")
                || r.method().equals("GET") && (r.uri().getPath().equals("/agent/api/v1/authorizations") || r.uri().getPath().equals("/agent/api/v1/catalog"))));
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
        lab.gw.fault("/heartbeat", Fault.TLS);
        assertEquals(LinkCheckOutcome.TLS_FAILED, lab.svc.confirmLink().outcome(), "TLS recusado não é \"offline\"");
        lab.gw.fault("/heartbeat", Fault.TIMEOUT);
        assertEquals(LinkCheckOutcome.TIMED_OUT, lab.svc.confirmLink().outcome(), "tempo esgotado tem texto próprio");
        lab.gw.fault("/heartbeat", Fault.MALFORMED);
        assertEquals(LinkCheckOutcome.INVALID_RESPONSE, lab.svc.confirmLink().outcome());
        // nenhuma dessas falhas desfaz o vínculo local
        assertTrue(lab.svc.currentLink().isPresent());
        assertEquals(LinkCheckOutcome.CONFIRMED, lab.svc.confirmLink().outcome());
    }

    @Test
    void catalogFailureIsUnavailableNotEmpty() {
        Lab lab = ready();
        lab.gw.fault("/catalog", Fault.UNAVAILABLE);
        LinkCheck c = lab.svc.confirmLink();
        assertEquals(LinkCheckOutcome.CONFIRMED, c.outcome(), "a presença confirmou o vínculo");
        assertEquals(RemoteCatalog.State.UNAVAILABLE, c.catalog().state(), "falha da consulta não é catálogo vazio");
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
