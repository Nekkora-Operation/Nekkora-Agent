package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.storage.AtomicWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AGENT-PLATFORM-001: a limpeza das execuções terminadas não apaga o que ainda não foi confirmado pela Platform. */
class DeliveryRetentionTest {
    @TempDir
    Path dir;

    @Test
    @DisplayName("R1: além das 50, as terminadas com entrega pendente ficam; as demais saem como antes")
    void pruneSparesPendingDeliveries() throws Exception {
        RunStore store = new RunStore(dir.resolve("execucoes"), AtomicWriter.DEFAULT);
        List<ExecutionId> ids = new ArrayList<>();
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        for (int i = 0; i < RunStore.KEEP + 10; i++) {
            ExecutionId id = ExecutionId.random();
            ids.add(id);
            store.save(new ExecutionRecord(id, ExecutionPackage.Kind.LAB_GENERIC, "pacote x", "sha256:" + "0".repeat(64), ExecutionState.SUCCEEDED, null,
                    t.plusSeconds(i), t.plusSeconds(i), t.plusSeconds(i + 1), null, 0, Map.of(), 0, 0, false, 0));
        }
        // as 10 mais antigas estão além das 50; 3 delas têm entrega pendente
        Set<ExecutionId> pending = Set.of(ids.get(0), ids.get(4), ids.get(9));
        store.retention(pending::contains);
        store.prune();
        for (int i = 0; i < 10; i++) assertEquals(pending.contains(ids.get(i)), Files.exists(store.dir(ids.get(i))), "execução " + i);
        for (int i = 10; i < ids.size(); i++) assertTrue(Files.exists(store.dir(ids.get(i))));
        // entregue: a proteção sai e a próxima limpeza age
        store.retention(id -> false);
        store.prune();
        for (ExecutionId id : pending) assertFalse(Files.exists(store.dir(id)));
    }
}
