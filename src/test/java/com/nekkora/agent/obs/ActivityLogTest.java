package com.nekkora.agent.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.obs.ActivityEvent.Category;
import com.nekkora.agent.obs.ActivityEvent.Level;
import com.nekkora.agent.obs.ActivityEvent.Source;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** OBS-03/OBS-05: sequência estável, recuperação depois de queda no meio da escrita, limite com EVENT_GAP. */
class ActivityLogTest {
    @TempDir
    Path tmp;

    static final Instant T0 = Instant.parse("2026-10-22T12:00:00Z");

    private static ActivityEvent fw(String id, long pos) {
        return ActivityEvent.of(id, T0, Source.FRAMEWORK, "ACTION_COMPLETED", Level.INFO, Category.ACTIVITY).step("s.a").position(pos).build();
    }

    @Test
    void sequenceAndPositionsSurviveReopenAndATornLastLineIsCut() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog a = ActivityLog.open(tmp, id);
        a.append(List.of(fw(id, 100), fw(id, 200), ActivityEvent.of(id, T0, Source.AGENT, "RUNNING", Level.INFO, Category.LIFECYCLE).position(70L).build()), T0);
        assertEquals(3, a.size());
        // queda no meio de uma gravação: meia linha no fim
        Files.writeString(tmp.resolve(ActivityLog.FILE), "{\"schema\":\"nekkora-agent/observ", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        ActivityLog b = ActivityLog.open(tmp, id);
        assertEquals(3, b.size());
        assertEquals(1, b.unreadable(), "a meia linha é sinalizada");
        assertEquals(200, b.position(Source.FRAMEWORK));
        assertEquals(70, b.position(Source.AGENT));
        List<ActivityEvent> more = b.append(List.of(fw(id, 300)), T0);
        assertEquals(4, more.get(0).sequence(), "a sequência continua, sem repetir nem pular");
        assertFalse(ActivityLog.open(tmp, id).sequenceGap());
        assertTrue(Files.readString(tmp.resolve(ActivityLog.FILE)).endsWith("}\n"));
    }

    @Test
    void readForQueryNeverModifiesTheFileAndFlagsSequenceGaps() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog a = ActivityLog.open(tmp, id);
        a.append(List.of(fw(id, 1), fw(id, 2), fw(id, 3)), T0);
        List<String> lines = new ArrayList<>(Files.readAllLines(tmp.resolve(ActivityLog.FILE)));
        lines.remove(1); // uma linha sumiu
        Files.write(tmp.resolve(ActivityLog.FILE), lines);
        Files.writeString(tmp.resolve(ActivityLog.FILE), "lixo-sem-quebra", StandardOpenOption.APPEND);
        long size = Files.size(tmp.resolve(ActivityLog.FILE));
        ActivityLog r = ActivityLog.read(tmp, id);
        assertTrue(r.sequenceGap());
        assertEquals(size, Files.size(tmp.resolve(ActivityLog.FILE)), "consulta não altera o disco");
        assertEquals(2, r.size());
        assertEquals(1, r.unreadable());
    }

    @Test
    void eventsOfAnotherExecutionInTheFileAreIgnored() throws Exception {
        String id = ExecutionId.random().value();
        String other = ExecutionId.random().value();
        ActivityLog.open(tmp, other).append(List.of(fw(other, 1)), T0);
        ActivityLog r = ActivityLog.read(tmp, id);
        assertEquals(0, r.size());
        assertEquals(1, r.unreadable());
    }

    @Test
    void capWritesOneGapAndCountsWhatIsDropped() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog a = ActivityLog.open(tmp, id);
        List<ActivityEvent> batch = new ArrayList<>();
        for (int i = 1; i <= ActivityLog.MAX_EVENTS + 500; i++) batch.add(fw(id, i));
        a.append(batch, T0);
        assertTrue(a.capped());
        assertEquals(500, a.dropped());
        assertEquals(1, a.count("EVENT_GAP"));
        assertEquals(ActivityLog.MAX_EVENTS + 1, a.size(), "limite + o aviso de lacuna (reserva do coletor)");
        a.append(List.of(fw(id, 999_999)), T0);
        assertEquals(501, a.dropped());
        a.append(List.of(ActivityEvent.of(id, T0, Source.COLLECTOR, "COLLECTION_COMPLETED", Level.INFO, Category.TELEMETRY).build()), T0);
        assertTrue(ActivityLog.closedOnDisk(tmp), "o fechamento sempre cabe");
        ActivityLog r = ActivityLog.read(tmp, id);
        assertTrue(r.capped());
        assertEquals(ActivityLog.MAX_EVENTS, r.position(Source.FRAMEWORK), "a posição gravada é a do último evento guardado (o resto é relido e recontado)");
        assertTrue(Files.size(tmp.resolve(ActivityLog.FILE)) < ActivityLog.MAX_READ);
    }
}
