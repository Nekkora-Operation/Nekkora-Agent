package com.nekkora.agent.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.platform.FakeGateway.Fault;
import com.nekkora.agent.platform.PlatformFixtures.Lab;
import com.nekkora.agent.platform.PlatformService.LinkCheck;
import com.nekkora.agent.platform.PlatformService.LinkCheckOutcome;
import com.nekkora.agent.platform.PlatformService.SelectionCheck;
import com.nekkora.agent.platform.PlatformService.SelectionOutcome;
import com.nekkora.agent.platform.PlatformStatus.LinkState;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLATFORM-AGENT-CATALOGO-001 T02: o catálogo da máquina lido pelo Agent, contra o transporte simulado (espelho do contrato
 * da 036). Nomes e números chegam; páginas são seguidas; resposta de outro vínculo é recusada; API sem catálogo é reconhecida;
 * falha no meio vira PARCIAL (nunca "completo"); a revalidação da escolha distingue "não visível" de "não deu para saber".
 * As falhas são INJETADAS.
 */
class PlatformCatalogTest {
    @TempDir
    Path dir;

    static final String EXT = PackageFingerprint.EXTERNAL_RULE;

    private Lab ready() {
        Lab lab = new Lab(dir);
        lab.paired();
        return lab;
    }

    private static Instant until() {
        return PlatformFixtures.T0.plus(Duration.ofDays(30));
    }

    @Test
    void namesNumbersAndDeclaredVersionsArrive() {
        Lab lab = ready();
        lab.gw.orgNames.put(lab.org, "Nekkora Lab A");
        UUID ws = UUID.randomUUID();
        UUID auto = UUID.randomUUID();
        lab.gw.publish(lab.org, lab.worker, ws, "Financeiro", auto, "relatorio-modular", 2, "1.10.0", "b".repeat(64), EXT, until());
        lab.gw.publish(lab.org, lab.worker, ws, "Financeiro", auto, "relatorio-modular", 1, "1.9.0", "a".repeat(64), EXT, until());
        LinkCheck c = lab.svc.confirmLink();
        RemoteCatalog cat = c.catalog();
        assertEquals(RemoteCatalog.State.COMPLETE, cat.state(), String.valueOf(cat.problem()));
        assertEquals("Nekkora Lab A", cat.organization().displayName());
        assertEquals(lab.org, cat.organization().id());
        RemoteCatalog.Project p = cat.project(auto).orElseThrow();
        assertEquals("relatorio-modular", p.automationName());
        assertEquals("Financeiro", p.workspaceName());
        assertEquals(2, p.versions().size());
        assertEquals(2, p.versions().get(0).versionNumber());
        assertEquals("1.10.0", p.versions().get(0).packageVersion());
        assertEquals("sha256:" + "b".repeat(64), p.versions().get(0).fingerprint());
        assertEquals("1.10.0", RemoteCatalog.highestDeclared(p.versions()).orElseThrow().packageVersion(), "1.10.0 > 1.9.0 (numérico, não lexicográfico)");
        assertEquals(Optional.of("1.10.1"), RemoteCatalog.nextPatch("1.10.0"));
    }

    @Test
    void pagesAreFollowedAndOrderIsStable() {
        Lab lab = ready();
        lab.gw.catalogPageCap = 2;
        for (int i = 0; i < 5; i++) {
            lab.gw.publish(lab.org, lab.worker, UUID.randomUUID(), "WS", UUID.randomUUID(), "p" + i, 1, "1.0.0", String.valueOf(i).repeat(64), EXT, until());
        }
        LinkCheck c = lab.svc.confirmLink();
        assertEquals(RemoteCatalog.State.COMPLETE, c.catalog().state());
        assertEquals(5, c.catalog().projects().size());
        assertEquals(3, lab.gw.count("GET", "/agent/api/v1/catalog"), "três páginas de 2");
    }

    @Test
    void failureAfterFirstPageIsPartialNeverComplete() {
        Lab lab = ready();
        lab.gw.catalogPageCap = 2;
        for (int i = 0; i < 5; i++) {
            lab.gw.publish(lab.org, lab.worker, UUID.randomUUID(), "WS", UUID.randomUUID(), "p" + i, 1, "1.0.0", String.valueOf(i).repeat(64), EXT, until());
        }
        LinkCheck first = lab.svc.confirmLink();
        assertTrue(first.catalog().complete());
        // a primeira página passa; a segunda chega fora do contrato
        int[] calls = {0};
        lab.gw.catalogTamper = json -> ++calls[0] == 2 ? "{\"oops\":true}" : json;
        LinkCheck c = lab.svc.confirmLink();
        assertEquals(LinkCheckOutcome.CONFIRMED, c.outcome());
        assertEquals(RemoteCatalog.State.PARTIAL, c.catalog().state(), "página faltante: parcial");
        assertEquals(2, c.catalog().projects().size());
        assertEquals(LinkCheckOutcome.INVALID_RESPONSE, c.catalog().problem());
        assertFalse(c.catalog().complete());
    }

    @Test
    void firstPageFailuresAreDistinctAndNeverEmpty() {
        Lab lab = ready();
        for (Object[] f : new Object[][] {{Fault.RATE_LIMITED, LinkCheckOutcome.RATE_LIMITED}, {Fault.FORBIDDEN, LinkCheckOutcome.FORBIDDEN},
                {Fault.UNAVAILABLE, LinkCheckOutcome.SERVER_ERROR}, {Fault.DROP_BEFORE, LinkCheckOutcome.UNREACHABLE}, {Fault.TLS, LinkCheckOutcome.TLS_FAILED},
                {Fault.TIMEOUT, LinkCheckOutcome.TIMED_OUT}}) {
            lab.gw.fault("/catalog", (Fault) f[0]);
            LinkCheck c = lab.svc.confirmLink();
            assertEquals(LinkCheckOutcome.CONFIRMED, c.outcome(), "o vínculo foi confirmado pela presença: " + f[0]);
            assertEquals(RemoteCatalog.State.UNAVAILABLE, c.catalog().state(), String.valueOf(f[0]));
            assertEquals(f[1], c.catalog().problem(), String.valueOf(f[0]));
            if (f[0] == Fault.RATE_LIMITED) assertEquals(Optional.of(30L), c.catalog().retryAfterSeconds());
            lab.gw.faults.clear();
            lab.clock.advance(Duration.ofMinutes(10)); // espera de falha do canal
            lab.svc.refreshNow();
        }
        assertEquals(LinkState.PAIRED, lab.svc.status().link(), "403 no catálogo não marca a credencial como recusada");
    }

    @Test
    void oldPlatformWithoutCatalogIsRecognized() {
        Lab lab = ready();
        lab.gw.catalogSupported = false;
        LinkCheck c = lab.svc.confirmLink();
        assertEquals(LinkCheckOutcome.CONFIRMED, c.outcome());
        assertEquals(RemoteCatalog.State.UNSUPPORTED, c.catalog().state());
        assertNull(c.catalog().organization(), "sem catálogo, nada de nome inventado");
    }

    @Test
    void answerOfAnotherLinkIsRefused() {
        Lab lab = ready();
        lab.gw.publish(lab.org, lab.worker, UUID.randomUUID(), "WS", UUID.randomUUID(), "x", 1, "1.0.0", "c".repeat(64), EXT, until());
        String agent = lab.svc.status().agentId();
        lab.gw.catalogTamper = json -> json.replace(agent, UUID.randomUUID().toString());
        LinkCheck c = lab.svc.confirmLink();
        assertEquals(RemoteCatalog.State.UNAVAILABLE, c.catalog().state());
        assertEquals(LinkCheckOutcome.INVALID_RESPONSE, c.catalog().problem());
        assertTrue(c.catalog().projects().isEmpty(), "nada da resposta de outro vínculo é aceito");
    }

    @Test
    void credentialRefusedInCatalogIsNotConfirmed() {
        Lab lab = ready();
        // revogada entre a presença e o catálogo
        lab.gw.revokeOnCatalog = true;
        LinkCheck c = lab.svc.confirmLink();
        assertEquals(LinkCheckOutcome.CREDENTIAL_REFUSED, c.outcome());
        assertEquals(LinkState.REFUSED, lab.svc.status().link());
    }

    @Test
    void selectionIsRevalidatedOnline() {
        Lab lab = ready();
        UUID auto = UUID.randomUUID();
        UUID z = lab.gw.publish(lab.org, lab.worker, UUID.randomUUID(), "WS", auto, "x", 1, "1.0.0", "d".repeat(64), EXT, until());
        SelectionCheck ok = lab.svc.checkSelection(auto);
        assertEquals(SelectionOutcome.VISIBLE, ok.outcome());
        assertEquals("x", ok.project().automationName());
        lab.gw.revokeAuthorization(z);
        assertEquals(SelectionOutcome.NOT_VISIBLE, lab.svc.checkSelection(auto).outcome(), "revogada: não visível agora");
        assertEquals(SelectionOutcome.NOT_VISIBLE, lab.svc.checkSelection(UUID.randomUUID()).outcome());
        lab.gw.fault("/catalog/automations", Fault.UNAVAILABLE);
        SelectionCheck failed = lab.svc.checkSelection(auto);
        assertEquals(SelectionOutcome.FAILED, failed.outcome(), "erro do servidor não é \"não existe\"");
        assertEquals(LinkCheckOutcome.SERVER_ERROR, failed.problem());
        // outro vínculo na resposta: recusado
        UUID auto2 = UUID.randomUUID();
        lab.gw.publish(lab.org, lab.worker, UUID.randomUUID(), "WS", auto2, "y", 1, "1.0.0", "e".repeat(64), EXT, until());
        String agent = lab.svc.status().agentId();
        lab.gw.catalogTamper = json -> json.replace(agent, UUID.randomUUID().toString());
        lab.clock.advance(Duration.ofMinutes(10));
        lab.svc.refreshNow();
        SelectionCheck other = lab.svc.checkSelection(auto2);
        assertEquals(SelectionOutcome.FAILED, other.outcome());
        assertEquals(LinkCheckOutcome.INVALID_RESPONSE, other.problem());
    }

    @Test
    void catalogQueriesCreateNothingRemote() {
        Lab lab = ready();
        UUID auto = UUID.randomUUID();
        lab.gw.publish(lab.org, lab.worker, UUID.randomUUID(), "WS", auto, "x", 1, "1.0.0", "f".repeat(64), EXT, until());
        int before = lab.gw.requests.size();
        for (int i = 0; i < 3; i++) {
            lab.svc.confirmLink();
            lab.svc.checkSelection(auto);
        }
        var sent = lab.gw.requests.subList(before, lab.gw.requests.size());
        assertTrue(sent.stream().allMatch(r -> r.method().equals("GET") || r.uri().getPath().equals("/agent/api/v1/heartbeat")),
                "só leituras e presença: nada de registro, decisão, desvínculo ou envio");
        assertTrue(lab.gw.runs.isEmpty());
    }
}
