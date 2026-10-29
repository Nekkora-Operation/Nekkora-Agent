package com.nekkora.agent.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.obs.ActivityCodec;
import com.nekkora.agent.obs.ActivityEvent;
import com.nekkora.agent.platform.FakeGateway.Fault;
import com.nekkora.agent.platform.PlatformFixtures.Lab;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D-AP-3/D-AP-7: a perda declarada é a faixa exata registrada no disco, entregue de forma idempotente. Queda do Agent
 * com a resposta da declaração perdida (o servidor já gravou) não declara duas vezes, não declara outra faixa e não conta
 * o apagado como recebido. Atraso de gravação nunca vira perda (D6 em PlatformDeliveryTest).
 */
class LossDeclarationTest {
    @TempDir
    Path dir;

    private static String purged(ExecutionId id, Lab lab, long until) {
        return ActivityCodec.encode(ActivityEvent.of(id.value(), lab.clock.instant(), ActivityEvent.Source.COLLECTOR, "HISTORY_PURGED",
                ActivityEvent.Level.INFO, ActivityEvent.Category.TELEMETRY).error(ErrorCode.OBS_HISTORY_PURGED.code()).count("purged_until_sequence", until)
                .build().withSequence(until + 1));
    }

    @Test
    void crashWithTheDeclarationResponseLostDeclaresTheSameRangeOnceAfterRestart() throws Exception {
        Lab lab = new Lab(dir);
        lab.paired();
        lab.gw.authorize(lab.org, lab.worker, PlatformFixtures.FP, PlatformFixtures.T0.plus(Duration.ofDays(30)));
        lab.svc.refreshNow();
        lab.tick(1);
        UUID auth = lab.gw.authorizations.keySet().iterator().next();
        ExecutionId id = lab.execution(ExecutionState.SUCCEEDED, lab.clock.instant());
        lab.events(id, 1, 4);
        lab.link(id, auth);
        lab.tick(1);
        assertEquals(4, lab.svc.delivery(id).settledThrough());
        lab.events(id, 5, 10);
        // a cota local apagou 5..10 sem ACK: o arquivo vira só o HISTORY_PURGED (troca atômica = registro da perda)
        Files.writeString(lab.activity(id), purged(id, lab, 10) + "\n");
        lab.gw.fault("/events", Fault.DROP_AFTER_COMMIT); // o servidor grava a declaração e a resposta se perde
        lab.tick(1);
        FakeGateway.Run run = lab.gw.run(id.value());
        assertEquals(1, run.declared.size(), "o servidor gravou a faixa");
        lab.restart(); // queda do Agent antes de saber do ACK
        lab.settle();
        assertEquals(1, run.declared.size(), "reenvio idempotente: a mesma faixa uma vez (DUPLICATE), nenhuma outra");
        assertEquals(5, run.declared.get(0)[0]);
        assertEquals(10, run.declared.get(0)[1]);
        assertEquals(4, run.stored, "o apagado nunca conta como recebido");
        assertEquals(11, run.settled);
        assertEquals(DeliveryView.State.DELIVERED, lab.svc.delivery(id).state());
        assertTrue(lab.gw.count("POST", "/events") >= 2, "a declaração foi reenviada depois do reinício");
    }
}
