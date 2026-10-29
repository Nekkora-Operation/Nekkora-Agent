package com.nekkora.agent.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.platform.PlatformFixtures.Lab;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D-AP-1: com a máquina pareada, a execução só local continua permitida e é uma escolha explícita. Sem escolha, nada é
 * registrado: nenhuma requisição de registro ou de eventos, nenhuma entrega, nenhum dado da Platform no registro local.
 * Pré-escolha só com exatamente uma autorização válida para a impressão.
 */
class LocalOnlyChoiceTest {
    @TempDir
    Path dir;

    static final String FP = "sha256:" + PlatformFixtures.FP;

    private Lab paired() {
        Lab lab = new Lab(dir);
        lab.paired();
        return lab;
    }

    private static void refresh(Lab lab) {
        lab.svc.refreshNow();
        lab.tick(1);
    }

    @Test
    void preselectionOnlyWithExactlyOneValidAuthorization() {
        Lab lab = paired();
        refresh(lab);
        assertEquals(Optional.empty(), PlatformService.preselected(lab.svc.authorizationsFor(FP)), "nenhuma: só local");

        UUID first = lab.gw.authorize(lab.org, lab.worker, PlatformFixtures.FP, PlatformFixtures.T0.plus(Duration.ofDays(5)));
        lab.gw.authorize(lab.org, lab.worker, PlatformFixtures.FP, PlatformFixtures.T0.plus(Duration.ofHours(1)));
        refresh(lab);
        List<PlatformService.AuthorizationChoice> two = lab.svc.authorizationsFor(FP);
        assertEquals(2, two.stream().filter(PlatformService.AuthorizationChoice::usable).count());
        assertEquals(Optional.empty(), PlatformService.preselected(two), "duas válidas: ninguém escolhe pela pessoa");

        lab.clock.advance(Duration.ofHours(2)); // a de 1 h vence (relógio local)
        List<PlatformService.AuthorizationChoice> one = lab.svc.authorizationsFor(FP);
        assertEquals(2, one.size(), "a vencida continua listada, marcada");
        assertTrue(one.stream().anyMatch(c -> !c.usable() && "VENCIDA".equals(c.reason())));
        assertEquals(Optional.of(first), PlatformService.preselected(one), "uma válida + uma vencida: a válida");

        UUID expired = one.stream().filter(c -> !c.usable()).findFirst().orElseThrow().authorizationId();
        assertEquals(ErrorCode.PLT_AUTHORIZATION_REFUSED, lab.svc.prepare(expired, FP).problem(), "vencida: nada começa sob ela");
        assertEquals(List.of(), lab.svc.authorizationsFor("sha256:" + "0".repeat(64)), "outra impressão: nenhuma");
    }

    @Test
    void localOnlyExecutionIsNeverRegisteredNorReceivesPlatformIdentity() throws Exception {
        Lab lab = paired();
        lab.gw.authorize(lab.org, lab.worker, PlatformFixtures.FP, PlatformFixtures.T0.plus(Duration.ofDays(5)));
        refresh(lab);
        assertTrue(PlatformService.preselected(lab.svc.authorizationsFor(FP)).isPresent());
        // a pessoa escolheu "Não registrar": o app não chama prepare() nem link()
        ExecutionId id = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.events(id, 1, 5);
        lab.settle();
        lab.save(id, ExecutionState.SUCCEEDED, lab.clock.instant().minusSeconds(30));
        lab.events(id, 6, 8);
        byte[] estado = Files.readAllBytes(lab.runs.dir(id).resolve("estado.json"));
        lab.settle();
        assertEquals(0, lab.gw.count("POST", "/runs"), "nenhum registro, relatório ou lote");
        assertTrue(lab.gw.count("POST", "/heartbeat") > 0, "a presença da máquina continua");
        assertArrayEquals(estado, Files.readAllBytes(lab.runs.dir(id).resolve("estado.json")), "o registro local não ganha nada da Platform");
        try (Stream<Path> files = Files.walk(lab.root.resolve("plataforma"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(f, StandardCharsets.UTF_8).contains(id.value()), "nenhuma entrega da execução só local em " + f.getFileName());
            }
        }
    }
}
