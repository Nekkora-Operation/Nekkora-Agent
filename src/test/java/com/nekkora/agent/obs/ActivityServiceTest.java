package com.nekkora.agent.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.obs.ActivityEvent.Source;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OBS-G4/G5/G7 no domínio: coleta incremental das duas origens, desfecho só do supervisor, reinício sem duplicar,
 * volume (10 mil eventos e acima do limite), arquivo corrompido/trocado/ausente, conflito de correlação e retenção.
 */
class ActivityServiceTest {
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

    private static List<ActivityEvent> all(ActivityService s, ObsFixtures.Run r) {
        return s.view(r.id(), 0).orElseThrow().events();
    }

    private static void drain(ActivityService s, int rounds) {
        for (int i = 0; i < rounds; i++) s.tick();
    }

    private static void assertNoDuplicates(List<ActivityEvent> events) {
        Set<String> keys = new HashSet<>();
        long prev = 0;
        for (ActivityEvent e : events) {
            assertEquals(prev + 1, e.sequence(), "sequência contínua");
            prev = e.sequence();
            if (e.sourcePosition() != null && e.source() != Source.COLLECTOR) {
                assertTrue(keys.add(e.source() + "@" + e.sourcePosition()), "evento repetido: " + e.source() + "@" + e.sourcePosition());
            }
        }
    }

    @Test
    void liveCollectionShowsStepsAsTheyArriveAndOutcomeComesFromTheSupervisor() throws Exception {
        Instant t0 = clock.instant();
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, t0);
        ActivityService s = service();
        s.track(run.id());
        s.tick();
        ActivityView v = s.view(run.id(), 0).orElseThrow();
        assertEquals(List.of("COLLECTION_STARTED", "STARTING", "RUNNING"), v.events().stream().map(ActivityEvent::code).toList());
        assertEquals(ActivityView.FrameworkState.WAITING, v.health().frameworkState());
        assertTrue(v.health().live());

        run.frameworkLines(List.of(ObsFixtures.fw(FW, t0.plusMillis(100), "ENGINE_STARTED"), ObsFixtures.fw(FW, t0.plusMillis(110), "PHASE_STARTED", "phase", "INIT")));
        s.tick();
        long seen = s.view(run.id(), 0).orElseThrow().events().size();
        List<ActivityEvent> fresh = s.view(run.id(), 3).orElseThrow().events();
        assertEquals(List.of("ENGINE_STARTED", "PHASE_STARTED"), fresh.stream().map(ActivityEvent::code).toList(), "só os novos depois da sequência 3");
        run.frameworkLines(List.of(ObsFixtures.fw(FW, t0.plusMillis(200), "PHASE_STARTED", "phase", "PROCESS"),
                ObsFixtures.fw(FW, t0.plusMillis(210), "TRANSACTION_STARTED", "phase", "PROCESS", "transaction_id", "tx-1", "attempt", 1),
                ObsFixtures.fw(FW, t0.plusMillis(220), "ACTION_STARTED", "phase", "PROCESS", "transaction_id", "tx-1", "step_id", "search.fill_search_term", "action", "fill")));
        s.tick();
        ActivityView.Progress p = ActivityView.progress(all(s, run));
        assertEquals("PROCESS", p.phase());
        assertEquals("search.fill_search_term", p.currentStep().stepId());
        assertEquals(1, p.transactionsStarted());
        assertEquals(ExecutionState.RUNNING, s.view(run.id(), 0).orElseThrow().record().state(), "o último evento nunca encerra a execução");

        // o Framework diz que terminou, mas o estado continua o do supervisor até ele decidir
        run.frameworkLines(List.of(ObsFixtures.fw(FW, t0.plusMillis(300), "ENGINE_COMPLETED", "phase", "END", "outcome", "COMPLETED", "count", 1)));
        s.tick();
        assertTrue(s.view(run.id(), 0).orElseThrow().health().live());
        assertFalse(s.view(run.id(), 0).orElseThrow().health().completed());
        run.finish(ExecutionState.SUCCEEDED, t0.plusMillis(400));
        s.tick();
        ActivityView end = s.view(run.id(), 0).orElseThrow();
        assertTrue(end.health().completed());
        assertFalse(end.health().live());
        List<String> codes = end.events().stream().map(ActivityEvent::code).toList();
        assertEquals("SUCCEEDED", codes.get(codes.size() - 2));
        assertEquals("COLLECTION_COMPLETED", codes.get(codes.size() - 1));
        assertEquals(FW, end.health().frameworkExecutionId());
        assertTrue(seen > 0);
        assertNoDuplicates(end.events());
        assertTrue(ActivityLog.closedOnDisk(store.dir(run.id())));
    }

    @Test
    void restartResumesFromStoredPositionsWithoutDuplicatesOrRelaunch() throws Exception {
        Instant t0 = clock.instant();
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, t0);
        List<String> g1 = ObsFixtures.g1Events();
        ActivityService a = service();
        a.track(run.id());
        a.tick();
        // os eventos reais do G1 trazem horário de 22/10 03:35; a execução fabricada começa antes disso
        run.frameworkLines(rebase(g1.subList(0, 60), t0));
        a.tick();
        int before = all(a, run).size();
        a.close();
        // "queda": o Agent some; o processo continua escrevendo; o supervisor (novo) registra a reconciliação/adoção
        run.frameworkLines(rebase(g1.subList(60, g1.size()), t0));
        store.event(run.id(), clock.instant(), "RECONCILE", Map.of("state", "RUNNING"));
        store.event(run.id(), clock.instant(), "ADOPTED", Map.of("pid", 4242));
        ActivityService b = service();
        b.catchUp();
        b.tick();
        run.finish(ExecutionState.SUCCEEDED, clock.instant().plusSeconds(1));
        drain(b, 3);
        List<ActivityEvent> events = all(b, run);
        assertNoDuplicates(events);
        assertEquals(g1.size(), events.stream().filter(e -> e.source() == Source.FRAMEWORK).count(), "cada evento do Framework exatamente uma vez");
        assertTrue(events.size() > before);
        assertEquals(1, events.stream().filter(e -> e.code().equals("COLLECTION_RESUMED")).count());
        assertTrue(events.stream().anyMatch(e -> e.code().equals("ADOPTED")));
        ActivityEvent summary = events.get(events.size() - 1);
        assertEquals("COLLECTION_COMPLETED", summary.code());
        assertEquals(1L, summary.counts().get("restarts"));
        // reabrir de novo depois do fim: nada muda, nada é recoletado
        ActivityService c = service();
        c.catchUp();
        drain(c, 2);
        assertEquals(events, all(c, run));
    }

    /** Leva os horários reais do G1 para depois do início da execução fabricada (mantendo os intervalos). */
    static List<String> rebase(List<String> lines, Instant start) {
        Instant first = Instant.parse("2026-10-22T03:35:24.977Z");
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            int i = l.indexOf("\"timestamp_utc\":\"") + 17;
            Instant t = Instant.parse(l.substring(i, l.indexOf('"', i)));
            out.add(l.substring(0, i) + ObsFixtures.TS.format(start.plus(Duration.between(first, t)).plusMillis(50)) + l.substring(l.indexOf('"', i)));
        }
        return out;
    }

    @Test
    void genericProcessShowsOnlyLifecycleAndIgnoresAPlantedFrameworkFile() throws Exception {
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.LAB_GENERIC, clock.instant());
        run.frameworkLines(List.of(ObsFixtures.fw(FW, clock.instant(), "ACTION_STARTED", "step_id", "plantado.passo")));
        ActivityService s = service();
        s.track(run.id());
        s.tick();
        run.finish(ExecutionState.SUCCEEDED, clock.instant().plusSeconds(1));
        s.tick();
        ActivityView v = s.view(run.id(), 0).orElseThrow();
        assertFalse(v.health().frameworkExpected());
        assertEquals(ActivityView.FrameworkState.NOT_APPLICABLE, v.health().frameworkState());
        assertTrue(v.events().stream().noneMatch(e -> e.source() == Source.FRAMEWORK), "etapas nunca são inventadas para processo genérico");
        assertTrue(v.health().completed());
    }

    @Test
    void tenThousandEventsAreCollectedInBoundedRounds() throws Exception {
        Instant t0 = clock.instant();
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, t0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            b.append(ObsFixtures.fw(FW, t0.plusMillis(i), "ACTION_COMPLETED", "phase", "PROCESS", "transaction_id", "tx-" + (i / 50), "step_id", "lote.passo_" + (i % 7),
                    "action", "read_items", "attempt", 1, "elapsed_ms", i % 300, "outcome", "OK")).append('\n');
        }
        run.frameworkRaw(b.toString());
        ActivityService s = service();
        s.track(run.id());
        int rounds = 0;
        long t = System.nanoTime();
        do {
            s.tick();
            rounds++;
        } while (all(s, run).stream().filter(e -> e.source() == Source.FRAMEWORK).count() < 10_000 && rounds < 100);
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertEquals(10_000, all(s, run).stream().filter(e -> e.source() == Source.FRAMEWORK).count());
        assertTrue(rounds > 3, "contrapressão: o volume é lido em várias rodadas (" + rounds + ")");
        assertTrue(ms < 20_000, "10 mil eventos em " + ms + " ms");
        assertEquals(0, ActivityView.progress(all(s, run)).transactionsStarted(), "sem TRANSACTION_STARTED nos dados: nada é inventado");
        System.out.println("OBS-G4 10k: " + rounds + " rodadas, " + ms + " ms, " + Files.size(store.dir(run.id()).resolve(ActivityLog.FILE)) + " bytes");
    }

    @Test
    void aboveTheCapAGapIsRecordedAndTheDropIsCounted() throws Exception {
        Instant t0 = clock.instant();
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, t0);
        StringBuilder b = new StringBuilder();
        int n = ActivityLog.MAX_EVENTS + 2_000;
        for (int i = 0; i < n; i++) b.append(ObsFixtures.fw(FW, t0.plusMillis(i), "TARGET_RESOLVED", "step_id", "x.y")).append('\n');
        run.frameworkRaw(b.toString());
        ActivityService s = service();
        s.track(run.id());
        drain(s, 60);
        run.finish(ExecutionState.SUCCEEDED, clock.instant().plusSeconds(5));
        drain(s, 5);
        ActivityView v = s.view(run.id(), 0).orElseThrow();
        assertTrue(v.health().capped());
        assertTrue(v.health().completed());
        assertEquals(1, v.events().stream().filter(e -> e.code().equals("EVENT_GAP")).count());
        ActivityEvent summary = v.events().get(v.events().size() - 1);
        long fwKept = v.events().stream().filter(e -> e.source() == Source.FRAMEWORK).count();
        assertEquals(n, fwKept + summary.counts().get("dropped") - agentDropped(v), "guardados + descartados = recebidos do Framework");
        assertTrue(Files.size(store.dir(run.id()).resolve(ActivityLog.FILE)) <= ActivityLog.MAX_READ);
    }

    private static long agentDropped(ActivityView v) {
        // o evento final do supervisor (SUCCEEDED) também chega depois do limite e é contado como descartado
        return v.events().stream().anyMatch(e -> e.source() == Source.AGENT && "SUCCEEDED".equals(e.code())) ? 0 : 1;
    }

    @Test
    void corruptedTruncatedReplacedAndMissingSourcesAreSignalledNeverInvented() throws Exception {
        Instant t0 = clock.instant();
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, t0);
        run.frameworkRaw(ObsFixtures.fw(FW, t0, "ENGINE_STARTED") + "\n{corrompida\n" + "{\"schema\":\"nekkora-rpa/events\"," + "\"x\":\"" + "z".repeat(20_000)
                + "\"}\n" + ObsFixtures.fw("ex-ffffffffffffffffffff", t0, "ACTION_STARTED", "step_id", "outra.execucao") + "\n"
                + ObsFixtures.fw(FW, t0, "PHASE_STARTED", "phase", "INIT") + "\n");
        ActivityService s = service();
        s.track(run.id());
        drain(s, 3);
        List<ActivityEvent> ev = all(s, run);
        List<String> reasons = ev.stream().filter(e -> e.code().equals("EVENT_INVALID")).map(ActivityEvent::errorCode).toList();
        assertEquals(List.of("MALFORMED_JSON", "LINE_TOO_LONG", "CORRELATION_CONFLICT"), reasons);
        assertEquals(List.of("ENGINE_STARTED", "PHASE_STARTED"), ev.stream().filter(e -> e.source() == Source.FRAMEWORK).map(ActivityEvent::code).toList());
        assertTrue(ev.stream().noneMatch(e -> "outra.execucao".equals(e.stepId())), "nenhuma falsa correspondência");
        assertEquals(3, s.view(run.id(), 0).orElseThrow().health().invalid());
        // arquivo trocado/truncado durante a execução: a origem para, nada é relido
        Files.writeString(run.framework(), ObsFixtures.fw(FW, t0, "ACTION_STARTED", "step_id", "relido.depois") + "\n", StandardCharsets.UTF_8);
        drain(s, 2);
        ev = all(s, run);
        assertEquals(1, ev.stream().filter(e -> e.code().equals("SOURCE_REPLACED")).count());
        assertTrue(ev.stream().noneMatch(e -> "relido.depois".equals(e.stepId())));
        assertEquals(ActivityView.FrameworkState.REPLACED, s.view(run.id(), 0).orElseThrow().health().frameworkState());
        assertEquals(ExecutionState.RUNNING, s.view(run.id(), 0).orElseThrow().record().state(), "falha de telemetria não muda a execução");

        // execução do Studio que terminou sem nenhum arquivo de eventos
        ObsFixtures.Run silent = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, t0);
        s.track(silent.id());
        s.tick();
        silent.finish(ExecutionState.FAILED, clock.instant());
        drain(s, 2);
        ActivityView sv = s.view(silent.id(), 0).orElseThrow();
        assertEquals(ActivityView.FrameworkState.UNAVAILABLE, sv.health().frameworkState());
        assertEquals("AGT-OBS-001", sv.events().stream().filter(e -> e.code().equals("SOURCE_UNAVAILABLE")).findFirst().orElseThrow().errorCode());
        assertTrue(sv.health().completed());
    }

    @Test
    void supervisorEventLimitBecomesAnExplicitGap() throws Exception {
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.LAB_GENERIC, clock.instant());
        for (int i = 0; i < RunStore.MAX_EVENTS + 20; i++) store.event(run.id(), clock.instant(), i % 2 == 0 ? "SESSION_LOCKED" : "SESSION_UNLOCKED", Map.of());
        assertEquals(RunStore.MAX_EVENTS, store.events(run.id()).size());
        assertTrue(store.events(run.id()).get(RunStore.MAX_EVENTS - 1).contains(RunStore.LIMIT_MARKER));
        ActivityService s = service();
        s.track(run.id());
        drain(s, 3);
        ActivityEvent gap = all(s, run).stream().filter(e -> e.code().equals("EVENT_GAP")).findFirst().orElseThrow();
        assertEquals("AGT-OBS-006", gap.errorCode());
        assertEquals(Source.AGENT, gap.source());
    }

    @Test
    void legacyRunWithoutActivityFileIsCollectedOnceFromItsSources() throws Exception {
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, clock.instant());
        run.frameworkLines(rebase(ObsFixtures.g1Events(), clock.instant()));
        run.finish(ExecutionState.SUCCEEDED, clock.instant().plusSeconds(5));
        assertFalse(Files.exists(store.dir(run.id()).resolve(ActivityLog.FILE)), "execução anterior à OBS-001");
        ActivityService s = service();
        s.catchUp();
        drain(s, 4);
        ActivityView v = s.view(run.id(), 0).orElseThrow();
        assertTrue(v.health().completed());
        assertEquals(121, v.events().stream().filter(e -> e.source() == Source.FRAMEWORK).count());
    }

    @Test
    void totalQuotaPurgesOlderFinishedActivityButKeepsTheSupervisorRecord() throws Exception {
        ActivityService s = new ActivityService(store, clock, Duration.ofMillis(50), 64 * 1024);
        List<ObsFixtures.Run> runs = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            Instant t0 = clock.instant();
            ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, t0);
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < 300; i++) lines.add(ObsFixtures.fw(FW, t0.plusMillis(i), "ACTION_COMPLETED", "step_id", "a.b", "action", "fill"));
            run.frameworkLines(lines);
            run.finish(ExecutionState.SUCCEEDED, t0.plusSeconds(1));
            s.track(run.id());
            drain(s, 3);
            runs.add(run);
            clock.plusMillis(60_000);
        }
        ActivityView oldest = s.view(runs.get(0).id(), 0).orElseThrow();
        assertTrue(oldest.health().purged(), "a mais antiga perdeu o detalhe pela cota");
        assertEquals(1, oldest.events().size());
        assertEquals("AGT-OBS-010", oldest.events().get(0).errorCode());
        assertEquals(ExecutionState.SUCCEEDED, oldest.record().state(), "o registro do supervisor fica");
        assertFalse(s.view(runs.get(2).id(), 0).orElseThrow().health().purged(), "a mais nova fica inteira");
        assertNotNull(store.record(runs.get(0).id()).orElse(null));
        assertNull(ObsFixtures.leak(Files.readString(store.dir(runs.get(0).id()).resolve(ActivityLog.FILE))));
    }
}
