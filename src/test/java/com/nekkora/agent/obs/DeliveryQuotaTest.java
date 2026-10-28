package com.nekkora.agent.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.storage.AtomicWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-PLATFORM-001: a cota total da atividade (96 MiB) poupa primeiro o que ainda não foi confirmado pela Platform; só se
 * não houver outro jeito de voltar à cota a atividade pendente também é reduzida ao {@code HISTORY_PURGED} (que diz até que
 * sequência apagou, para o transporte declarar a perda com a faixa exata). O armazenamento continua limitado.
 */
class DeliveryQuotaTest {
    @TempDir
    Path dir;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC);

    /** Três execuções terminadas (da mais antiga para a mais nova), cada uma com ~10 KB de atividade. */
    private List<ExecutionId> three(RunStore store) throws Exception {
        List<ExecutionId> ids = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            ExecutionId id = ExecutionId.random();
            Instant t = clock.instant().plusSeconds(k * 60L);
            store.save(new ExecutionRecord(id, ExecutionPackage.Kind.LAB_GENERIC, "pacote x", "sha256:" + "0".repeat(64), ExecutionState.SUCCEEDED, null, t, t,
                    t.plusSeconds(1), null, 0, Map.of(), 0, 0, false, 0));
            StringBuilder b = new StringBuilder();
            for (long s = 1; b.length() < 10_000; s++) {
                b.append(ActivityCodec.encode(ActivityEvent.of(id.value(), t, ActivityEvent.Source.AGENT, "STEP_COMPLETED", ActivityEvent.Level.INFO,
                        ActivityEvent.Category.ACTIVITY).build().withSequence(s))).append('\n');
            }
            Files.writeString(store.dir(id).resolve(ActivityLog.FILE), b, StandardCharsets.UTF_8);
            ids.add(id);
        }
        return ids;
    }

    private static boolean purged(RunStore store, ExecutionId id) throws Exception {
        return Files.readString(store.dir(id).resolve(ActivityLog.FILE)).contains("HISTORY_PURGED");
    }

    @Test
    @DisplayName("Q1: a mais nova sem entrega pendente é reduzida antes da antiga com entrega pendente")
    void prefersDeliveredRuns() throws Exception {
        RunStore store = new RunStore(dir.resolve("execucoes"), AtomicWriter.DEFAULT);
        List<ExecutionId> ids = three(store);
        ActivityService s = new ActivityService(store, clock, Duration.ofMillis(50), 25_000);
        s.retention(Set.of(ids.get(0))::contains);
        s.enforceQuota();
        assertEquals(List.of(false, true, false), List.of(purged(store, ids.get(0)), purged(store, ids.get(1)), purged(store, ids.get(2))));
    }

    @Test
    @DisplayName("Q2: sem outro jeito, a pendente também é reduzida (cota respeitada) e o aviso diz até onde apagou")
    void boundedEvenWithPendingDeliveries() throws Exception {
        RunStore store = new RunStore(dir.resolve("execucoes"), AtomicWriter.DEFAULT);
        List<ExecutionId> ids = three(store);
        long last = ActivityLog.lastSequence(store.dir(ids.get(0)));
        ActivityService s = new ActivityService(store, clock, Duration.ofMillis(50), 25_000);
        s.retention(Set.of(ids.get(0), ids.get(1))::contains);
        s.enforceQuota();
        assertEquals(List.of(true, false, false), List.of(purged(store, ids.get(0)), purged(store, ids.get(1)), purged(store, ids.get(2))));
        String line = Files.readString(store.dir(ids.get(0)).resolve(ActivityLog.FILE));
        assertTrue(line.contains("\"purged_until_sequence\":" + last), line);
        assertTrue(line.contains("\"sequence\":" + (last + 1)), line);
    }
}
