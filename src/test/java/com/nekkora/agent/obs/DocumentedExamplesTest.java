package com.nekkora.agent.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.obs.ActivityEvent.Category;
import com.nekkora.agent.obs.ActivityEvent.Level;
import com.nekkora.agent.obs.ActivityEvent.Source;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Os exemplos publicados em {@code docs/desktop-001/observabilidade/exemplos} continuam válidos no codec (um consumidor
 * futuro — Studio, Platform — pode usá-los como referência) e não carregam sentinela sensível. O arquivo de avisos do
 * coletor é GERADO por este teste a partir do modelo (nunca escrito à mão).
 */
class DocumentedExamplesTest {
    static final Path DIR = Path.of(System.getProperty("agent.basedir", "."), "docs", "desktop-001", "observabilidade", "exemplos");

    @Test
    void collectorWarningsExampleIsGeneratedFromTheModel() throws Exception {
        String id = "ex-0123456789abcdef0123456789abcdef";
        Instant t = Instant.parse("2026-10-23T03:00:00Z");
        List<ActivityEvent> ev = List.of(
                ActivityEvent.of(id, t, Source.COLLECTOR, "COLLECTION_RESUMED", Level.INFO, Category.TELEMETRY).count("restarts", 1).build(),
                ActivityEvent.of(id, t, Source.COLLECTOR, "EVENT_INVALID", Level.WARNING, Category.TELEMETRY).status("FRAMEWORK_SOURCE").error("MALFORMED_JSON")
                        .position(18_442L).build(),
                ActivityEvent.of(id, t, Source.COLLECTOR, "EVENT_INVALID", Level.WARNING, Category.TELEMETRY).status("FRAMEWORK_SOURCE")
                        .error("CORRELATION_CONFLICT").position(18_901L).build(),
                ActivityEvent.of(id, t, Source.COLLECTOR, "SOURCE_REPLACED", Level.WARNING, Category.TELEMETRY).status("FRAMEWORK_SOURCE")
                        .error(ErrorCode.OBS_SOURCE_REPLACED.code()).position(20_115L).build(),
                ActivityEvent.of(id, t, Source.COLLECTOR, "EVENT_GAP", Level.WARNING, Category.TELEMETRY).status("ACTIVITY_STORE")
                        .error(ErrorCode.OBS_RETENTION_LIMIT.code()).count("max_events", ActivityLog.MAX_EVENTS).build(),
                ActivityEvent.of(id, t, Source.AGENT, "EVENT_GAP", Level.WARNING, Category.TELEMETRY).occurred(t.minusSeconds(3))
                        .error(ErrorCode.OBS_AGENT_EVENTS_LIMIT.code()).position(41_300L).build(),
                ActivityEvent.of(id, t, Source.COLLECTOR, "COLLECTION_COMPLETED", Level.INFO, Category.TELEMETRY).status("SUCCEEDED")
                        .count("events", 20_064).count("framework_events", 19_980).count("dropped", 2_000).count("invalid", 3).count("excluded_fields", 41_200)
                        .count("unknown_fields", 6).count("unknown_codes", 0).count("restarts", 1).error(ErrorCode.OBS_UNKNOWN_CONTENT.code()).build(),
                ActivityEvent.of(id, t, Source.COLLECTOR, "HISTORY_PURGED", Level.INFO, Category.TELEMETRY).error(ErrorCode.OBS_HISTORY_PURGED.code())
                        .count("purged_until_sequence", 20_064).build());
        List<ActivityEvent> numbered = new ArrayList<>();
        for (int i = 0; i < ev.size(); i++) numbered.add(ev.get(i).withSequence(i + 1));
        Files.createDirectories(DIR);
        Files.write(DIR.resolve("coletor-avisos.jsonl"), ActivityExport.jsonl(numbered));
    }

    @Test
    void everyPublishedExampleDecodesAndCarriesNoSentinel() throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.list(DIR)) {
            files = s.filter(p -> p.toString().endsWith(".jsonl")).toList();
        }
        assertFalse(files.isEmpty());
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            assertNull(ObsFixtures.leak(text), f.getFileName().toString());
            long n = 0;
            for (String line : text.lines().toList()) {
                assertTrue(ActivityCodec.decode(line).isPresent(), f.getFileName() + ": " + line);
                assertEquals(line, ActivityCodec.encode(ActivityCodec.decode(line).orElseThrow()), "forma canônica");
                n++;
            }
            assertTrue(n > 0, f.getFileName().toString());
        }
    }
}
