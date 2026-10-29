package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.storage.AtomicWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DESKTOP-001 G1/G3 com processos REAIS de laboratório (Python sem nekkora_rpa). Sem Python neste ambiente, os testes
 * são PULADOS e contados como pulados.
 */
class ExecutionControllerTest {
    @TempDir
    Path tmp;

    /** Relógio do sistema com um deslocamento ajustável (simula o salto de uma suspensão). */
    static final class ShiftClock extends Clock {
        final AtomicLong shift = new AtomicLong();

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plusMillis(shift.get());
        }
    }

    Path python;
    Path agentDir;
    Path out;
    ShiftClock clock;
    ExecFixtures.FakeSession session;
    ApprovalStore approvals;
    RunStore runs;
    ExecutionController controller;

    @BeforeEach
    void setUp() throws Exception {
        python = ExecFixtures.python();
        Assumptions.assumeTrue(python != null, "sem Python neste ambiente");
        agentDir = tmp.resolve("agent");
        out = Files.createDirectories(tmp.resolve("saida"));
        clock = new ShiftClock();
        session = new ExecFixtures.FakeSession();
        approvals = new ApprovalStore(agentDir.resolve("aprovacoes.json"), AtomicWriter.DEFAULT);
        runs = new RunStore(agentDir.resolve("execucoes"), AtomicWriter.DEFAULT);
        controller = new ExecutionController(runs, approvals, session, clock, agentDir, Duration.ofMillis(100), Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        if (controller != null) controller.close();
    }

    ExecutionPackage approved(String entry) throws Exception {
        ExecutionPackage p = ExecutionPackage.inspect(ExecFixtures.labPackage(tmp.resolve("pkg-" + entry), "Lab " + entry.replace(".py", ""), entry));
        approvals.approve(p, clock.instant());
        return p;
    }

    ExecutionController.Request request(ExecutionPackage p, Duration timeout) {
        return new ExecutionController.Request(p.dir(), p.fingerprint(), python, null, out, timeout);
    }

    ExecutionRecord waitEnd(Duration max) throws Exception {
        long deadline = System.nanoTime() + max.toNanos();
        while (controller.running() && System.nanoTime() < deadline) Thread.sleep(50);
        assertFalse(controller.running(), "terminou no prazo do teste");
        return controller.current().orElseThrow().record();
    }

    /** Tudo o que o Agent gravou desta execução (registro, eventos, aprovações), para procurar vazamento. */
    String agentFiles() throws Exception {
        StringBuilder b = new StringBuilder();
        try (Stream<Path> s = Files.walk(agentDir)) {
            for (Path p : s.filter(Files::isRegularFile).filter(f -> !f.toString().contains(Path.of("pacote").toString() + java.io.File.separator)
                    && !f.getFileName().toString().endsWith(".py")).toList()) {
                b.append(Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return b.toString();
    }

    @Test
    @DisplayName("sucesso confirmado pelo protocolo; a saída do processo não é guardada (sentinela ausente)")
    void sucesso() throws Exception {
        ExecutionController.Snapshot s = controller.start(request(approved("lab_sucesso.py"), null));
        assertEquals(ExecutionState.RUNNING, s.record().state());
        assertNotNull(s.record().owner());
        ExecutionRecord r = waitEnd(Duration.ofSeconds(60));
        assertEquals(ExecutionState.SUCCEEDED, r.state(), String.valueOf(r.code()));
        assertEquals(0, r.exitCode());
        assertEquals(Map.of("ITEMS", 3L), r.counts());
        assertTrue(r.outputBytes() > 0, "saída drenada e contada");
        assertFalse(agentFiles().contains("SENTINELA-VALOR-DA-AUTOMACAO"), "nenhum valor da automação no registro do Agent");
        List<String> ev = runs.events(r.id());
        assertTrue(ev.get(0).contains("STARTING") && ev.stream().anyMatch(l -> l.contains("\"RUNNING\"")) && ev.get(ev.size() - 1).contains("SUCCEEDED"), ev.toString());
        assertTrue(Files.isRegularFile(runs.dir(r.id()).resolve("pacote").resolve("lab_sucesso.py")), "rodou da cópia conferida");
    }

    @Test
    @DisplayName("falha informada pelo protocolo; sem protocolo com código 0 → resultado desconhecido (nada presumido)")
    void falhaESemProtocolo() throws Exception {
        controller.start(request(approved("lab_falha.py"), null));
        ExecutionRecord f = waitEnd(Duration.ofSeconds(60));
        assertEquals(ExecutionState.FAILED, f.state());
        assertEquals(ErrorCode.EXEC_REPORTED_FAILURE, f.code());
        assertEquals(1, f.exitCode());
        controller.start(request(approved("lab_sem_protocolo.py"), null));
        ExecutionRecord u = waitEnd(Duration.ofSeconds(60));
        assertEquals(ExecutionState.OUTCOME_UNKNOWN, u.state(), "código 0 sozinho não é sucesso");
        assertEquals(ErrorCode.EXEC_NO_PROTOCOL, u.code());
    }

    @Test
    @DisplayName("bloqueios antes de iniciar: não aprovado, pacote mudado, sessão bloqueada/indisponível, Python inexistente, saída inválida")
    void bloqueios() throws Exception {
        ExecutionPackage p = approved("lab_sucesso.py");
        ExecutionPackage notApproved = ExecutionPackage.inspect(ExecFixtures.labPackage(tmp.resolve("outro"), "Outro", "lab_falha.py"));
        assertEquals(ErrorCode.EXEC_NOT_APPROVED, assertThrows(ExecRefused.class, () -> controller.start(request(notApproved, null))).code());
        session.locked.set(true);
        assertEquals(ErrorCode.EXEC_SESSION_LOCKED, assertThrows(ExecRefused.class, () -> controller.start(request(p, null))).code());
        session.locked.set(false);
        session.problem.set(ErrorCode.EXEC_SESSION_UNAVAILABLE);
        assertEquals(ErrorCode.EXEC_SESSION_UNAVAILABLE, assertThrows(ExecRefused.class, () -> controller.start(request(p, null))).code(), "Session 0 / sem desktop");
        session.problem.set(null);
        ExecutionController.Request missing = new ExecutionController.Request(p.dir(), p.fingerprint(), tmp.resolve("nao").resolve("python.exe"), null, out, null);
        assertEquals(ErrorCode.EXEC_PYTHON_INVALID, assertThrows(ExecRefused.class, () -> controller.start(missing)).code(), "executável inexistente");
        ExecutionController.Request inside = new ExecutionController.Request(p.dir(), p.fingerprint(), python, null, agentDir, null);
        Files.createDirectories(agentDir);
        assertEquals(ErrorCode.EXEC_OUTPUT_INVALID, assertThrows(ExecRefused.class, () -> controller.start(inside)).code());
        // pacote mudado DEPOIS da aprovação (mesma pasta): a conferência na hora de iniciar recusa
        Files.writeString(p.dir().resolve("lab_sucesso.py"), "print('trocado')\n");
        assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED, assertThrows(ExecRefused.class, () -> controller.start(request(p, null))).code());
        assertFalse(controller.running(), "nada foi lançado");
        assertTrue(runs.all().stream().allMatch(r -> r.state() == ExecutionState.BLOCKED && r.owner() == null), "todos BLOCKED, sem processo");
    }

    @Test
    @DisplayName("uma execução por usuário: pedido concorrente recusado; cancelar encerra a árvore possuída (filho incluído)")
    void concorrenciaECancelamento() throws Exception {
        ExecutionPackage slow = approved("lab_lento.py");
        controller.start(request(slow, null));
        assertEquals(ErrorCode.EXEC_ALREADY_RUNNING, assertThrows(ExecRefused.class, () -> controller.start(request(slow, null))).code());
        assertEquals(ErrorCode.EXEC_ALREADY_RUNNING, assertThrows(ExecRefused.class, () -> controller.start(request(approved("lab_sucesso.py"), null))).code());
        Map<String, Long> pids = waitPids();
        assertTrue(controller.cancel());
        ExecutionRecord r = waitEnd(Duration.ofSeconds(30));
        assertEquals(ExecutionState.CANCELLED, r.state());
        assertEquals(ErrorCode.EXEC_CANCELLED, r.code());
        for (long pid : pids.values()) assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "processo " + pid + " encerrado");
        assertEquals(1, runs.all().stream().filter(x -> x.owner() != null).count(), "o pedido recusado não criou execução");
    }

    @Test
    @DisplayName("prazo esgotado encerra e registra TIMED_OUT")
    void prazo() throws Exception {
        controller.start(request(approved("lab_lento.py"), Duration.ofSeconds(3)));
        Map<String, Long> pids = waitPids();
        ExecutionRecord r = waitEnd(Duration.ofSeconds(40));
        assertEquals(ExecutionState.TIMED_OUT, r.state());
        for (long pid : pids.values()) assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    @DisplayName("saída volumosa (~33 MB em saída e erros) não trava; só a contagem de bytes fica")
    void volume() throws Exception {
        controller.start(request(approved("lab_volumoso.py"), null));
        ExecutionRecord r = waitEnd(Duration.ofSeconds(120));
        assertEquals(ExecutionState.SUCCEEDED, r.state());
        assertTrue(r.outputBytes() > 30_000_000L, String.valueOf(r.outputBytes()));
        assertFalse(agentFiles().contains("SENTINELA-VALOR-DA-AUTOMACAO"));
    }

    @Test
    @DisplayName("tela bloqueada e suspensão durante a execução são registradas; processo perdido sem protocolo → OUTCOME_UNKNOWN")
    void bloqueioESuspensao() throws Exception {
        controller.start(request(approved("lab_lento.py"), null));
        Map<String, Long> pids = waitPids();
        session.locked.set(true);
        Thread.sleep(600);
        session.locked.set(false);
        Thread.sleep(600);
        clock.shift.addAndGet(Duration.ofMinutes(5).toMillis()); // a máquina "dormiu" 5 minutos
        Thread.sleep(600);
        // o processo morre durante/depois da suspensão sem publicar resultado
        for (long pid : pids.values()) ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        ExecutionRecord r = waitEnd(Duration.ofSeconds(30));
        assertEquals(ExecutionState.OUTCOME_UNKNOWN, r.state(), "sem prova, nada presumido");
        assertEquals(ErrorCode.EXEC_SUSPENDED, r.code());
        assertEquals(1, r.suspensions());
        assertEquals(1, r.locks());
        List<String> ev = runs.events(r.id());
        assertTrue(ev.stream().anyMatch(l -> l.contains("SESSION_LOCKED")) && ev.stream().anyMatch(l -> l.contains("SESSION_UNLOCKED"))
                && ev.stream().anyMatch(l -> l.contains("SUSPENSION_DETECTED")), ev.toString());
    }

    @Test
    @DisplayName("reinício do Agent: processo ainda vivo é ADOTADO (e cancelável); morto → OUTCOME_UNKNOWN; nada é relançado")
    void reinicio() throws Exception {
        // 1) "o Agent caiu" depois de lançar: processo órfão vivo + registro RUNNING
        ExecutionPackage slow = approved("lab_lento.py");
        Path orphanOut = Files.createDirectories(out.resolve("orfao"));
        Process orphan = new ProcessBuilder(python.toString(), "-E", "-s", "-B", "lab_lento.py", "--output", orphanOut.toString())
                .directory(slow.dir().toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        ExecutionId id = ExecutionId.random();
        ProcessOwner owner = ProcessOwner.of(orphan, python.toString());
        runs.save(new ExecutionRecord(id, slow.kind(), slow.name(), slow.fingerprint(), ExecutionState.RUNNING, null, clock.instant(), clock.instant(), null,
                owner, null, Map.of(), 0, 0, false, 0));
        // 2) uma execução de efeito NÃO idempotente cujo processo morreu com o Agent
        ExecutionPackage effect = approved("lab_efeito.py");
        Path effectOut = Files.createDirectories(out.resolve("efeito").resolve("exec"));
        Process gone = new ProcessBuilder(python.toString(), "-E", "-s", "-B", "lab_efeito.py", "--output", effectOut.toString())
                .directory(effect.dir().toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Path efeitos = effectOut.getParent().resolve("efeitos.txt");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(efeitos) && System.nanoTime() < deadline) Thread.sleep(50);
        ExecutionId id2 = ExecutionId.random();
        runs.save(new ExecutionRecord(id2, effect.kind(), effect.name(), effect.fingerprint(), ExecutionState.RUNNING, null, clock.instant(), clock.instant(), null,
                ProcessOwner.of(gone, python.toString()), null, Map.of(), 0, 0, false, 0));
        gone.destroyForcibly().waitFor();
        // 3) registro STARTING sem processo (queda entre gravar e lançar)
        ExecutionId id3 = ExecutionId.random();
        runs.save(new ExecutionRecord(id3, slow.kind(), slow.name(), slow.fingerprint(), ExecutionState.STARTING, null, clock.instant(), null, null, null, null,
                Map.of(), 0, 0, false, 0));
        ExecutionController restarted = new ExecutionController(runs, approvals, session, clock, agentDir, Duration.ofMillis(100), Duration.ofMillis(200));
        try {
            assertEquals(3, restarted.reconcile());
            assertTrue(restarted.running(), "o processo vivo foi adotado");
            assertTrue(restarted.current().orElseThrow().record().adopted());
            Thread.sleep(2000);
            assertEquals(1, Files.readAllLines(efeitos).size(), "o efeito não idempotente NÃO foi repetido");
            Map<ExecutionId, ExecutionRecord> by = new java.util.HashMap<>();
            runs.all().forEach(r -> by.put(r.id(), r));
            assertEquals(ExecutionState.OUTCOME_UNKNOWN, by.get(id2).state());
            assertEquals(ErrorCode.EXEC_AGENT_RESTARTED, by.get(id2).code());
            assertEquals(ExecutionState.OUTCOME_UNKNOWN, by.get(id3).state(), "STARTING sem processo: pode ter começado; nada presumido");
            assertTrue(restarted.cancel());
            long d = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (restarted.running() && System.nanoTime() < d) Thread.sleep(50);
            assertFalse(orphan.isAlive(), "a adoção permite cancelar o processo possuído");
            assertEquals(ExecutionState.CANCELLED, restarted.current().orElseThrow().record().state());
        } finally {
            restarted.close();
            orphan.destroyForcibly();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> waitPids() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Stream<Path> s = Files.walk(out)) {
                for (Path p : s.filter(f -> f.getFileName().toString().equals("pids.json")).toList()) {
                    Map<String, Object> m = JsonReader.object(Files.readString(p));
                    if (m.get("filho") instanceof Long) return Map.of("pai", (Long) m.get("pai"), "filho", (Long) m.get("filho"));
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("pids.json não apareceu");
    }
}
