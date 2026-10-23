package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.obs.ActivityEvent;
import com.nekkora.agent.obs.ActivityExport;
import com.nekkora.agent.obs.ActivityService;
import com.nekkora.agent.obs.ActivityView;
import com.nekkora.agent.storage.AtomicWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OBS-001 com PROCESSOS REAIS pelo supervisor do Agent: G2 (processo genérico com ~32 MB de stdout/stderr), G3
 * (sentinelas sensíveis em campos livres, stdout/stderr, linhas hostis) com controles negativos, G5 (queda do Agent,
 * adoção e retomada da coleta sem duplicar nem relançar) e G7 (correlação Agent↔Framework e passo do Studio). Os
 * eventos válidos vêm do {@code EventLogger} real do {@code nekkora_rpa} 0.3.0. Sem Python, PULADOS.
 */
class ObservabilityProcessTest {
    @TempDir
    Path tmp;

    static final List<String> SENTINELS = List.of("123.456.789-09", "12345678909", "12.345.678/0001-95", "maria.sigilosa@cliente.example",
            "tok_SIGILOSO_OBS_9Z", "Senha#Sigilosa9Z", "ClienteSigiloso9Z", "VALOR-EXCEL-9Z-4471", "token=abc9Z", "cliente.example/api",
            "Traceback (most recent call last)", "SENTINELA-VALOR-DA-AUTOMACAO-7Q2");
    static final String FILL_STEP_ID = ObsStudioPackage.FILL_STEP_ID;

    Path python;
    Path agentDir;
    Path out;
    ExecFixtures.FakeSession session;
    ApprovalStore approvals;
    RunStore runs;
    ExecutionController controller;
    ActivityService activity;

    @BeforeEach
    void setUp() throws Exception {
        python = ExecFixtures.python();
        Assumptions.assumeTrue(python != null, "sem Python neste ambiente");
        agentDir = tmp.resolve("agent");
        out = Files.createDirectories(tmp.resolve("saida"));
        session = new ExecFixtures.FakeSession();
        approvals = new ApprovalStore(agentDir.resolve("aprovacoes.json"), AtomicWriter.DEFAULT);
        runs = new RunStore(agentDir.resolve("execucoes"), AtomicWriter.DEFAULT);
        controller = new ExecutionController(runs, approvals, session, Clock.systemUTC(), agentDir, Duration.ofMillis(100), Duration.ofMillis(200));
        activity = new ActivityService(runs, Clock.systemUTC());
        controller.addListener(activity.controllerListener());
        activity.start();
    }

    @AfterEach
    void tearDown() {
        if (controller != null) controller.close();
        if (activity != null) activity.close();
    }

    /** Pacote no formato do Studio cujo main.py publica eventos reais do Framework (aprovado nesta máquina). */
    ExecutionPackage studioPackage(String name, String config) throws Exception {
        ExecutionPackage p = ExecutionPackage.inspect(ObsStudioPackage.create(tmp.resolve(name), config));
        approvals.approve(p, Clock.systemUTC().instant());
        return p;
    }

    ExecutionController.Request request(ExecutionPackage p) {
        return new ExecutionController.Request(p.dir(), p.fingerprint(), python, null, out, Duration.ofMinutes(3));
    }

    static void waitFor(BooleanSupplier c, Duration max, String what) throws Exception {
        long deadline = System.nanoTime() + max.toNanos();
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("tempo esgotado: " + what);
            Thread.sleep(50);
        }
    }

    ActivityView collected(ActivityService s, ExecutionId id) throws Exception {
        waitFor(() -> s.view(id, 0).map(v -> v.health().completed()).orElse(false), Duration.ofSeconds(60), "coleta encerrada");
        return s.view(id, 0).orElseThrow();
    }

    static String leak(String text) {
        for (String s : SENTINELS) if (text.contains(s)) return s;
        return null;
    }

    @Test
    @DisplayName("OBS-G3/G7: sentinelas em campos livres, stdout/stderr e linhas hostis não chegam à atividade nem à exportação")
    void sentinelasNaoVazam() throws Exception {
        ExecutionPackage p = studioPackage("pkg-sentinela", "{\"mode\": \"sentinel\", \"items\": 2, \"delay_ms\": 50}");
        ExecutionId id = controller.start(request(p)).record().id();
        waitFor(() -> !controller.running(), Duration.ofSeconds(90), "fim da execução");
        ExecutionRecord r = controller.current().orElseThrow().record();
        assertEquals(ExecutionState.SUCCEEDED, r.state(), String.valueOf(r.code()));
        ActivityView v = collected(activity, id);

        // a atividade real chegou: fases, etapas, transações, passo do Studio
        List<ActivityEvent> fw = v.events().stream().filter(e -> e.source() == ActivityEvent.Source.FRAMEWORK).toList();
        assertTrue(fw.stream().anyMatch(e -> "ENGINE_COMPLETED".equals(e.code())));
        ActivityEvent fill = fw.stream().filter(e -> "search.fill_search_term".equals(e.stepId()) && "ACTION_COMPLETED".equals(e.code())).findFirst().orElseThrow();
        assertEquals(FILL_STEP_ID, fill.studioStepId(), "G7: step_id rastreável até o passo do Studio pelo manifesto conferido");
        assertNull(fw.stream().filter(e -> "profile.read_birth_date".equals(e.stepId())).findFirst().orElseThrow().studioStepId(),
                "passo fora do manifesto: sem correspondência inventada");
        ActivityView.Progress prog = ActivityView.progress(v.events());
        assertEquals(2, prog.transactionsStarted());
        assertEquals(2, prog.transactionsSucceeded());
        assertTrue(v.health().invalid() >= 3, "linhas corrompida, aninhada e enorme recusadas: " + v.health().invalid());
        assertTrue(v.health().unknownFields() > 0 && v.health().excludedFields() > 0);

        // G7: correlação Agent ↔ Framework, sem presumir igualdade
        Path raw = out.resolve(id.value()).resolve("execucao").resolve("events.jsonl");
        String rawText = Files.readString(raw, StandardCharsets.UTF_8);
        String fwId = rawText.substring(rawText.indexOf("\"execution_id\": \"") + 17, rawText.indexOf("\"execution_id\": \"") + 40).split("\"")[0];
        assertEquals(fwId, v.health().frameworkExecutionId());
        assertNotEquals(id.value(), fwId);
        assertTrue(fw.stream().allMatch(e -> fwId.equals(e.frameworkExecutionId())));

        // zero sentinela: histórico persistido, eventos e registro do supervisor, exportação
        Path dir = runs.dir(id);
        for (String f : List.of("atividade.jsonl", "eventos.jsonl", "estado.json")) {
            assertNull(leak(Files.readString(dir.resolve(f), StandardCharsets.UTF_8)), f);
        }
        byte[] export = ActivityExport.jsonl(v.events());
        assertNull(leak(new String(export, StandardCharsets.UTF_8)), "exportação");

        // controles negativos: os sentinelas EXISTIRAM (no arquivo bruto e na saída do processo) e o detector os acharia
        assertNotNull(leak(rawText), "o arquivo bruto do Framework tem os sentinelas — o filtro é que os barrou");
        assertTrue(r.outputBytes() > 0, "stdout/stderr com sentinelas foram drenados e só contados");
        assertNotNull(leak(new String(export, StandardCharsets.UTF_8) + rawText.lines().filter(l -> l.contains("detail")).findFirst().orElseThrow()),
                "vazamento introduzido de propósito na exportação seria detectado");
        Path planted = dir.resolve("atividade-com-vazamento.jsonl");
        Files.writeString(planted, Files.readString(dir.resolve("atividade.jsonl")) + rawText.lines().findFirst().orElseThrow().replace("ENGINE_STARTED",
                "ENGINE_STARTED\",\"detail\":\"" + SENTINELS.get(4)));
        assertNotNull(leak(Files.readString(planted)), "vazamento introduzido de propósito no histórico seria detectado");
    }

    @Test
    @DisplayName("OBS-G2: processo genérico com ~32 MB de stdout/stderr: só ciclo de vida, etapas 'não disponíveis', nada da saída guardado")
    void genericoVolumoso() throws Exception {
        ExecutionPackage p = ExecutionPackage.inspect(ExecFixtures.labPackage(tmp.resolve("pkg-vol"), "Lab volumoso", "lab_volumoso.py"));
        approvals.approve(p, Clock.systemUTC().instant());
        long t = System.nanoTime();
        ExecutionId id = controller.start(request(p)).record().id();
        // a consulta da atividade continua respondendo enquanto a saída é drenada
        long worst = 0;
        while (controller.running()) {
            long q = System.nanoTime();
            activity.view(id, 0);
            worst = Math.max(worst, (System.nanoTime() - q) / 1_000_000);
            Thread.sleep(100);
        }
        ExecutionRecord r = controller.current().orElseThrow().record();
        assertEquals(ExecutionState.SUCCEEDED, r.state());
        assertTrue(r.outputBytes() > 30_000_000, "~32 MB drenados: " + r.outputBytes());
        ActivityView v = collected(activity, id);
        assertFalse(v.health().frameworkExpected());
        assertEquals(ActivityView.FrameworkState.NOT_APPLICABLE, v.health().frameworkState());
        assertTrue(v.events().stream().noneMatch(e -> e.source() == ActivityEvent.Source.FRAMEWORK), "nenhuma etapa inventada");
        assertEquals(List.of("STARTING", "RUNNING", "SUCCEEDED"), v.events().stream().filter(e -> e.source() == ActivityEvent.Source.AGENT)
                .map(ActivityEvent::code).toList());
        ActivityEvent end = v.events().stream().filter(e -> "SUCCEEDED".equals(e.code())).findFirst().orElseThrow();
        assertEquals(r.outputBytes(), end.counts().get("output_bytes"), "só a contagem de bytes");
        assertTrue(Files.size(runs.dir(id).resolve("atividade.jsonl")) < 4096, "o histórico não cresce com a saída");
        assertNull(leak(Files.readString(runs.dir(id).resolve("atividade.jsonl"))));
        assertTrue(worst < 1000, "consulta da atividade durante a drenagem: pior caso " + worst + " ms");
        System.out.println("OBS-G2: " + r.outputBytes() + " bytes drenados em " + (System.nanoTime() - t) / 1_000_000 + " ms; consulta pior caso " + worst + " ms");
    }

    @Test
    @DisplayName("OBS-G5: queda do Agent no meio, reconcile()/ADOPTED e retomada da coleta: nada relançado, nada duplicado")
    void quedaAdocaoERetomada() throws Exception {
        ExecutionPackage p = studioPackage("pkg-lento", "{\"mode\": \"slow\", \"items\": 6, \"delay_ms\": 300}");
        // 1) o Agent "antigo" lança como o controlador faria e cai: registro RUNNING + processo órfão vivo
        ExecutionId id = ExecutionId.random();
        java.time.Instant now = Clock.systemUTC().instant();
        ExecutionRecord r = new ExecutionRecord(id, p.kind(), "pacote " + p.shortFingerprint(), p.fingerprint(), ExecutionState.READY, null, now, null, null, null,
                null, Map.of(), 0, 0, false, 0).moveTo(ExecutionState.STARTING, null, now);
        runs.save(r);
        runs.event(id, now, "STARTING", Map.of("kind", p.kind().name(), "mode", p.mode().name(), "session", 1));
        Path copy = Launch.snapshot(p, runs.dir(id));
        Path output = Files.createDirectories(out.resolve(id.value()));
        Files.writeString(runs.dir(id).resolve(ExecutionController.OUTPUT_REF), output.toString(), StandardCharsets.UTF_8);
        Launch.Spec spec = Launch.spec(p, python, copy, null, output);
        ProcessBuilder pb = new ProcessBuilder(spec.command()).directory(spec.workDir().toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        PythonRuntime.minimalEnvironment(pb.environment());
        Process orphan = pb.start();
        r = r.withOwner(ProcessOwner.of(orphan, python.toString())).moveTo(ExecutionState.RUNNING, null, Clock.systemUTC().instant());
        runs.save(r);
        runs.event(id, Clock.systemUTC().instant(), "RUNNING", Map.of("pid", orphan.pid()));
        try {
            activity.track(id);
            waitFor(() -> activity.view(id, 0).map(v -> v.events().stream().filter(e -> e.source() == ActivityEvent.Source.FRAMEWORK).count() >= 8)
                    .orElse(false), Duration.ofSeconds(60), "etapas antes da queda");
            int before = activity.view(id, 0).orElseThrow().events().size();
            activity.close(); // queda: a coleta para no meio
            controller.close();
            controller = null;

            // 2) novo Agent: reconcilia (adota), retoma a coleta pela posição gravada
            ExecutionController restarted = new ExecutionController(runs, approvals, session, Clock.systemUTC(), agentDir, Duration.ofMillis(100),
                    Duration.ofMillis(200));
            ActivityService again = new ActivityService(runs, Clock.systemUTC());
            restarted.addListener(again.controllerListener());
            try {
                assertEquals(1, restarted.reconcile());
                assertTrue(restarted.running() && restarted.current().orElseThrow().record().adopted(), "o processo vivo foi ADOTADO");
                again.start();
                waitFor(() -> !restarted.running(), Duration.ofSeconds(90), "fim do processo adotado");
                ActivityView v = collected(again, id);
                assertEquals(ExecutionState.SUCCEEDED, v.record().state(), "desfecho pelo protocolo (summary.json), não pelo último log");
                List<ActivityEvent> all = v.events();
                long prev = 0;
                Set<Long> fwPositions = new HashSet<>();
                for (ActivityEvent e : all) {
                    assertEquals(prev + 1, e.sequence(), "sequência estável e contínua através da queda");
                    prev = e.sequence();
                    if (e.source() == ActivityEvent.Source.FRAMEWORK) assertTrue(fwPositions.add(e.sourcePosition()), "evento do Framework duplicado");
                }
                long rawLines = Files.readAllLines(output.resolve("execucao").resolve("events.jsonl")).size();
                assertEquals(rawLines, fwPositions.size(), "cada linha do Framework exatamente uma vez");
                assertEquals(1, all.stream().filter(e -> "ENGINE_STARTED".equals(e.code())).count(), "nada foi relançado");
                assertEquals(1, all.stream().filter(e -> "COLLECTION_RESUMED".equals(e.code())).count());
                assertTrue(all.stream().anyMatch(e -> "ADOPTED".equals(e.code())));
                assertTrue(all.size() > before);
                System.out.println("OBS-G5: antes da queda " + before + " eventos; depois " + all.size() + "; Framework " + fwPositions.size());
            } finally {
                restarted.close();
                again.close();
            }
        } finally {
            orphan.destroyForcibly();
        }
    }
}
