package com.nekkora.agent.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.obs.ActivityEvent.Category;
import com.nekkora.agent.obs.ActivityEvent.Level;
import com.nekkora.agent.obs.ActivityEvent.Source;
import com.nekkora.agent.obs.ActivityView.FrameworkState;
import com.nekkora.agent.obs.ActivityView.Gap;
import com.nekkora.agent.obs.ActivityView.Integrity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Uma linha do tempo parcial nunca é apresentada como completa: cada perda, recusa, lacuna ou origem interrompida
 * vira um motivo explícito (com código); "completa" só com a coleta encerrada e nenhum motivo.
 */
class CompletenessTest {
    @TempDir
    Path tmp;

    ObsFixtures.TestClock clock;
    RunStore store;
    Path saida;
    static final String FW = "ex-348ea538b07747d2812b";

    @BeforeEach
    void setUp() throws Exception {
        clock = new ObsFixtures.TestClock();
        store = ObsFixtures.store(tmp.resolve("agent"));
        saida = Files.createDirectories(tmp.resolve("saida"));
    }

    private ActivityService service() {
        return new ActivityService(store, clock, Duration.ofMillis(50), ActivityService.MAX_TOTAL_BYTES);
    }

    private static void drain(ActivityService s, int rounds) {
        for (int i = 0; i < rounds; i++) s.tick();
    }

    private static ActivityView.Completeness of(ActivityService s, ObsFixtures.Run run) {
        ActivityView v = s.view(run.id(), 0).orElseThrow();
        return ActivityView.completeness(v.health(), v.events());
    }

    private ObsFixtures.Run studioRun(String... codes) throws Exception {
        Instant t0 = clock.instant();
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, t0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < codes.length; i++) b.append(ObsFixtures.fw(FW, t0.plusMillis(i), codes[i])).append('\n');
        run.frameworkRaw(b.toString());
        return run;
    }

    @Test
    void cleanRunIsInProgressWhileRunningAndCompleteOnlyAfterTheCollectionCloses() throws Exception {
        ObsFixtures.Run run = studioRun("ENGINE_STARTED", "ENGINE_COMPLETED");
        ActivityService s = service();
        s.track(run.id());
        drain(s, 2);
        assertEquals(new ActivityView.Completeness(Integrity.IN_PROGRESS, List.of()), of(s, run));
        run.finish(ExecutionState.SUCCEEDED, clock.instant().plusSeconds(1));
        drain(s, 3);
        ActivityView.Completeness c = of(s, run);
        assertEquals(Integrity.COMPLETE, c.integrity());
        assertTrue(c.complete());
        // reaberto do disco (depois de reiniciar o Agent): a mesma conclusão
        assertEquals(c, of(service(), run));
    }

    @Test
    void terminalRunWhoseCollectionNeverClosedIsPendingNotComplete() throws Exception {
        ObsFixtures.Run run = studioRun("ENGINE_STARTED");
        ActivityService s = service();
        s.track(run.id());
        drain(s, 2);
        run.finish(ExecutionState.FAILED, clock.instant()); // o Agent "caiu" antes de encerrar a coleta
        ActivityService reopened = service(); // sem track/catchUp: só leitura do disco
        ActivityView v = reopened.view(run.id(), 0).orElseThrow();
        assertFalse(v.health().completed());
        assertEquals(Integrity.PENDING, ActivityView.completeness(v.health(), v.events()).integrity());
    }

    @Test
    void rejectedLinesAndAStoppedFrameworkSourceMakeTheTimelineIncomplete() throws Exception {
        Instant t0 = clock.instant();
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, t0);
        run.frameworkRaw(ObsFixtures.fw(FW, t0, "ENGINE_STARTED") + "\n{corrompida\n");
        ActivityService s = service();
        s.track(run.id());
        drain(s, 2);
        ActivityView.Completeness c = of(s, run);
        assertEquals(Integrity.INCOMPLETE, c.integrity(), "em andamento, mas já com perda: nunca 'sem lacunas'");
        assertEquals(List.of(Gap.REJECTED), c.gaps());
        Files.writeString(run.framework(), "x\n", StandardCharsets.UTF_8); // truncado/trocado
        drain(s, 2);
        run.finish(ExecutionState.SUCCEEDED, clock.instant().plusSeconds(1));
        drain(s, 3);
        c = of(s, run);
        assertEquals(Integrity.INCOMPLETE, c.integrity(), "coleta encerrada, mas incompleta");
        assertEquals(List.of(Gap.REJECTED, Gap.FRAMEWORK_STOPPED), c.gaps());
        assertEquals(c, of(service(), run), "a mesma conclusão lida do disco");
    }

    @Test
    void studioRunThatEndedWithoutAnyEventFileIsIncomplete() throws Exception {
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, clock.instant());
        ActivityService s = service();
        s.track(run.id());
        s.tick();
        run.finish(ExecutionState.FAILED, clock.instant());
        drain(s, 2);
        assertEquals(List.of(Gap.FRAMEWORK_UNAVAILABLE), of(s, run).gaps());
    }

    @Test
    void genericRunWithOnlyTheLifecycleIsCompleteWhenTheCollectionCloses() throws Exception {
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.LAB_GENERIC, clock.instant());
        ActivityService s = service();
        s.track(run.id());
        s.tick();
        run.finish(ExecutionState.SUCCEEDED, clock.instant());
        drain(s, 2);
        assertEquals(Integrity.COMPLETE, of(s, run).integrity(), "etapas não se aplicam: não é lacuna");
    }

    @Test
    void supervisorEventLimitIsAGap() throws Exception {
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.LAB_GENERIC, clock.instant());
        for (int i = 0; i < RunStore.MAX_EVENTS + 5; i++) store.event(run.id(), clock.instant(), i % 2 == 0 ? "SESSION_LOCKED" : "SESSION_UNLOCKED", Map.of());
        ActivityService s = service();
        s.track(run.id());
        drain(s, 3);
        assertEquals(List.of(Gap.SUPERVISOR_LIMIT), of(s, run).gaps());
    }

    @Test
    void frameworkSourceThatWasTemporarilyUnreadableAndCameBackLosesNothing() throws Exception {
        ObsFixtures.Run run = studioRun("ENGINE_STARTED", "ENGINE_COMPLETED");
        run.finish(ExecutionState.SUCCEEDED, clock.instant().plusSeconds(1));
        // histórico como a coleta teria gravado: indisponível por um momento e de volta, lido da mesma posição
        ActivityLog log = ActivityLog.open(store.dir(run.id()), run.id().value());
        Instant now = clock.instant();
        log.append(List.of(collector(run, now, "COLLECTION_STARTED", null), collector(run, now, "SOURCE_UNAVAILABLE", ActivityCollector.FRAMEWORK_ORIGIN),
                collector(run, now, "SOURCE_AVAILABLE", ActivityCollector.FRAMEWORK_ORIGIN),
                ActivityEvent.of(run.id().value(), now, Source.FRAMEWORK, "ENGINE_STARTED", Level.INFO, Category.LIFECYCLE).occurred(now).framework(FW)
                        .position(10L).build(),
                collector(run, now, "COLLECTION_COMPLETED", null)), now);
        ActivityView v = service().view(run.id(), 0).orElseThrow();
        assertEquals(FrameworkState.READING, v.health().frameworkState());
        assertEquals(Integrity.COMPLETE, ActivityView.completeness(v.health(), v.events()).integrity());
    }

    @Test
    void supervisorSourceThatStayedUnavailableIsAGapOnlyOnceTheCollectionStopped() {
        String id = "ex-" + "c".repeat(32);
        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        List<ActivityEvent> events = List.of(ActivityEvent.of(id, now, Source.COLLECTOR, "SOURCE_UNAVAILABLE", Level.WARNING, Category.TELEMETRY)
                .status(ActivityCollector.AGENT_ORIGIN).error("AGT-OBS-001").build().withSequence(1));
        assertEquals(Integrity.IN_PROGRESS, ActivityView.completeness(health(true, false, false, 0, false), events).integrity(), "pode voltar");
        assertEquals(List.of(Gap.SUPERVISOR_SOURCE), ActivityView.completeness(health(false, true, false, 0, false), events).gaps());
    }

    @Test
    void everyHealthCounterBecomesAnExplicitReason() {
        assertEquals(List.of(Gap.RETENTION_LIMIT), ActivityView.completeness(health(false, true, true, 2_000, false), List.of()).gaps());
        assertEquals(List.of(Gap.SEQUENCE_GAP), ActivityView.completeness(health(false, true, false, 0, true), List.of()).gaps());
        ActivityView.Health storeFailed = new ActivityView.Health(false, true, false, FrameworkState.NOT_APPLICABLE, 0, 0, 0, 0, 0, false, false, 0, false, true, 0, 0,
                null, false);
        assertEquals(List.of(Gap.STORE_FAILED), ActivityView.completeness(storeFailed, List.of()).gaps());
        ActivityView.Health purged = new ActivityView.Health(false, false, true, FrameworkState.WAITING, 0, 0, 0, 0, 0, false, false, 0, true, false, 0, 0, null,
                false);
        assertEquals(Gap.PURGED, ActivityView.completeness(purged, List.of()).gaps().get(0));
        assertEquals(Integrity.PENDING, ActivityView.completeness(null, List.of()).integrity(), "sem saúde: nunca 'completa'");
        // conteúdo desconhecido descartado (campos) não tira eventos da linha do tempo
        ActivityView.Health unknown = new ActivityView.Health(false, true, false, FrameworkState.NOT_APPLICABLE, 0, 0, 7, 3, 1, false, false, 0, false, false, 0, 1,
                null, false);
        assertEquals(Integrity.COMPLETE, ActivityView.completeness(unknown, List.of()).integrity());
    }

    private static ActivityView.Health health(boolean live, boolean completed, boolean capped, long dropped, boolean sequenceGap) {
        return new ActivityView.Health(live, completed, false, FrameworkState.NOT_APPLICABLE, dropped, 0, 0, 0, 0, capped, sequenceGap, 0, false, false, 0, 0, null,
                false);
    }

    private static ActivityEvent collector(ObsFixtures.Run run, Instant now, String code, String origin) {
        return ActivityEvent.of(run.id().value(), now, Source.COLLECTOR, code, Level.INFO, Category.TELEMETRY).status(origin).build();
    }
}
