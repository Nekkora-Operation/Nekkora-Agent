package com.nekkora.agent.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.platform.PlatformFixtures.Lab;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D-AP-2: autorizações guardadas e o relógio local. O prazo ({@code validUntil}) é conferido pelo relógio local, nunca
 * abaixo do piso (hora do servidor na presença e o maior instante local já consultado). Atrasar o relógio não devolve o
 * prazo; adiantar só vence antes (falha fechada) até a próxima hora do servidor. A execução só local nunca depende disso.
 */
class AuthorizationClockTest {
    @TempDir
    Path dir;

    static final String FP = "sha256:" + PlatformFixtures.FP;

    private Lab lab(Duration validity) {
        Lab lab = new Lab(dir);
        lab.paired();
        lab.gw.authorize(lab.org, lab.worker, PlatformFixtures.FP, PlatformFixtures.T0.plus(validity));
        lab.svc.refreshNow();
        lab.tick(1);
        return lab;
    }

    private static UUID only(Lab lab) {
        return lab.gw.authorizations.keySet().iterator().next();
    }

    private static boolean usable(Lab lab) {
        List<PlatformService.AuthorizationChoice> c = lab.svc.authorizationsFor(FP);
        assertEquals(1, c.size());
        return c.get(0).usable();
    }

    @Test
    void clockSetBackAfterExpiryDoesNotReviveTheAuthorizationInTheSameSession() {
        Lab lab = lab(Duration.ofHours(1));
        assertTrue(usable(lab));
        lab.clock.advance(Duration.ofHours(2));
        assertFalse(usable(lab), "vencida pelo relógio local");
        lab.clock.now = PlatformFixtures.T0.plus(Duration.ofMinutes(30)); // relógio atrasado de propósito
        assertFalse(usable(lab), "o piso (maior instante já consultado) mantém a vencida");
        assertEquals(ErrorCode.PLT_AUTHORIZATION_REFUSED, lab.svc.prepare(only(lab), FP).problem());
    }

    @Test
    void afterRestartTheServerTimeSeenOnlineIsTheFloor() {
        Lab lab = lab(Duration.ofHours(1));
        // a presença trouxe a hora do servidor (T0 + ~minutos) e o download a gravou
        lab.clock.advance(Duration.ofMinutes(50));
        lab.svc.refreshNow();
        lab.tick(2);
        lab.restart();
        lab.clock.now = PlatformFixtures.T0.minus(Duration.ofDays(3)); // muito antes de qualquer hora já vista
        assertTrue(usable(lab), "piso = última hora do servidor gravada (T0+50min), ainda antes do prazo: continua valendo");
        lab.clock.now = PlatformFixtures.T0.plus(Duration.ofHours(2));
        assertFalse(usable(lab));
        lab.clock.now = PlatformFixtures.T0.minus(Duration.ofDays(3));
        assertFalse(usable(lab), "consultada vencida nesta sessão, não volta");
    }

    @Test
    void clockAheadExpiresEarlyUntilTheServerTimeCorrectsTheFloor() {
        Lab lab = lab(Duration.ofDays(2));
        lab.clock.advance(Duration.ofDays(5)); // relógio local adiantado (nenhuma presença enquanto adiantado)
        assertFalse(usable(lab), "adiantado: vence antes (falha fechada)");
        assertEquals(ErrorCode.PLT_AUTHORIZATION_REFUSED, lab.svc.prepare(only(lab), FP).problem());
        lab.clock.now = PlatformFixtures.T0.plus(Duration.ofMinutes(5)); // a pessoa corrige o relógio
        lab.clock.advance(Duration.ofMinutes(2));
        lab.tick(2); // presença com a hora do servidor
        assertTrue(usable(lab), "a hora do servidor corrige o piso");
        assertTrue(lab.svc.prepare(only(lab), FP).accepted());
    }

    @Test
    void storedFileFromTheEarlierVersionWithoutServerTimeStillLoads() throws Exception {
        Lab lab = lab(Duration.ofHours(1));
        Path f = lab.root.resolve("plataforma").resolve(AuthorizationCache.FILE);
        String doc = java.nio.file.Files.readString(f).replaceAll(",\"serverTimeAt\":\"[^\"]*\"", "");
        java.nio.file.Files.writeString(f, doc);
        assertFalse(doc.contains("serverTimeAt"));
        lab.restart();
        assertTrue(usable(lab));
        Instant later = PlatformFixtures.T0.plus(Duration.ofHours(3));
        lab.clock.now = later;
        assertFalse(usable(lab));
    }
}
