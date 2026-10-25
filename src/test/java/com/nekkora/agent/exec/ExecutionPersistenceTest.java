package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.FileHolder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-AGT-01/F-AGT-02: a gravação do {@code estado.json} falha depois que o processo já foi lançado (bloqueio do Windows,
 * disco). O processo lançado nunca fica sem dono, nada é relançado, nada incerto é dado por gravado, a falha que não
 * passa é sinalizada e o desfecho da automação não muda por causa do registro.
 */
class ExecutionPersistenceTest {
    @TempDir
    Path tmp;

    Path python;
    Path agentDir;
    Path out;
    ExecFixtures.FakeSession session;
    ApprovalStore approvals;
    ExecutionController controller;

    @BeforeEach
    void setUp() throws Exception {
        python = ExecFixtures.python();
        Assumptions.assumeTrue(python != null, "sem Python neste ambiente");
        agentDir = tmp.resolve("agent");
        out = Files.createDirectories(tmp.resolve("saida"));
        session = new ExecFixtures.FakeSession();
        approvals = new ApprovalStore(agentDir.resolve("aprovacoes.json"), AtomicWriter.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        if (controller != null) controller.close();
    }

    /** Gravador do {@code estado.json} roteirizado pela ordem da gravação (1 = STARTING, 2 = RUNNING, 3 = terminal...). */
    static final class Scripted implements AtomicWriter {
        final AtomicInteger estado = new AtomicInteger();
        volatile IntPredicate fail = n -> false;
        volatile java.util.function.IntConsumer before = n -> {};

        @Override
        public void write(Path target, byte[] data) throws java.io.IOException {
            if (target.getFileName().toString().equals("estado.json")) {
                int n = estado.incrementAndGet();
                before.accept(n);
                if (fail.test(n)) throw new AccessDeniedException(target.toString());
            }
            AtomicWriter.DEFAULT.write(target, data);
        }
    }

    RunStore store(AtomicWriter w) {
        return new RunStore(agentDir.resolve("execucoes"), w);
    }

    /** Diagnóstico da gravação, como o log do Agent o receberia. */
    final List<String> diag = new java.util.concurrent.CopyOnWriteArrayList<>();

    ExecutionController controller(RunStore runs) {
        ExecutionController c = new ExecutionController(runs, approvals, session, Clock.systemUTC(), agentDir, Duration.ofMillis(100), Duration.ofMillis(200),
                Duration.ofSeconds(1));
        c.recordDiagnostics(new ExecutionController.RecordDiagnostics() {
            @Override
            public void delayed(ExecutionId id, ExecutionState state) {
                diag.add("delayed:" + state);
            }

            @Override
            public void recovered(ExecutionId id, ExecutionState state) {
                diag.add("recovered:" + state);
            }

            @Override
            public void lost(ExecutionId id, ExecutionState state) {
                diag.add("lost:" + state);
            }
        });
        return c;
    }

    /** Espera a tela poder dizer "registro gravado" (ou o prazo do teste acabar). */
    void waitSaved(ExecutionController c, Duration max) throws Exception {
        long deadline = System.nanoTime() + max.toNanos();
        while (!c.current().orElseThrow().recordSaved() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(c.current().orElseThrow().recordSaved(), "o registro chegou ao disco");
    }

    List<String> events(ExecutionId id) {
        return store(AtomicWriter.DEFAULT).events(id).stream().map(l -> {
            try {
                return JsonReader.object(l).get("event").toString();
            } catch (JsonReader.Invalid e) {
                throw new AssertionError(e);
            }
        }).toList();
    }

    ExecutionPackage approved(String entry) throws Exception {
        ExecutionPackage p = ExecutionPackage.inspect(ExecFixtures.labPackage(tmp.resolve("pkg-" + entry), "Lab " + entry.replace(".py", ""), entry));
        approvals.approve(p, java.time.Instant.now());
        return p;
    }

    ExecutionController.Request request(ExecutionPackage p) {
        return new ExecutionController.Request(p.dir(), p.fingerprint(), python, null, out, null);
    }

    ExecutionRecord waitEnd(ExecutionController c, Duration max) throws Exception {
        long deadline = System.nanoTime() + max.toNanos();
        while (c.running() && System.nanoTime() < deadline) Thread.sleep(50);
        assertFalse(c.running(), "terminou no prazo do teste");
        return c.current().orElseThrow().record();
    }

    /** O que está EM DISCO (lido por um RunStore novo, como depois de um reinício). */
    ExecutionRecord onDisk(ExecutionId id) {
        return store(AtomicWriter.DEFAULT).record(id).orElseThrow();
    }

    long lines(Path f) throws Exception {
        return Files.exists(f) ? Files.readAllLines(f).size() : 0;
    }

    @Test
    @DisplayName("F-AGT-01: gravar RUNNING falha UMA vez depois do lançamento → start() não falha, o processo é supervisionado e o registro se recupera")
    void falhaTransitoriaAoGravarRunning() throws Exception {
        Scripted w = new Scripted();
        w.fail = n -> n == 2;
        controller = controller(store(w));
        ExecutionPackage p = approved("lab_efeito_curto.py");
        ExecutionController.Snapshot s = controller.start(request(p));
        assertTrue(controller.running(), "o processo lançado tem dono: está sob supervisão");
        assertEquals(ExecutionState.RUNNING, s.record().state());
        assertFalse(s.recordSaved(), "a tela sabe que o RUNNING ainda não está em disco");
        ExecutionRecord end = waitEnd(controller, Duration.ofSeconds(60));
        waitSaved(controller, Duration.ofSeconds(5));
        assertEquals(ExecutionState.SUCCEEDED, end.state(), "o desfecho é o do protocolo; a falha do registro não o altera");
        assertEquals(ExecutionState.SUCCEEDED, onDisk(end.id()).state(), "em disco: o estado final");
        assertEquals(1, lines(out.resolve("efeitos.txt")), "um único envio");
        assertEquals(List.of("delayed:RUNNING", "recovered:RUNNING"), diag, "uma linha por mudança, não por tentativa");
        assertEquals(List.of("STARTING", "RUNNING", "RECORD_SAVE_DELAYED", "RECORD_SAVE_RECOVERED", "SUCCEEDED"), events(end.id()));
        assertTrue(Files.isRegularFile(runs(end.id()).resolve(ExecutionController.PROCESS_REF)), "a posse do processo ficou gravada à parte");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("F-AGT-01 com bloqueio REAL do Windows: outro processo segura o estado.json na troca para RUNNING por 1,5 s")
    void bloqueioRealNaTrocaParaRunning() throws Exception {
        Scripted w = new Scripted();
        AtomicBoolean held = new AtomicBoolean();
        controller = controller(store(w));
        ExecutionPackage p = approved("lab_efeito_curto.py");
        FileHolder.Handle[] holder = new FileHolder.Handle[1];
        w.before = n -> {
            if (n != 2) return;
            // o estado.json já existe (STARTING): outro processo o abre bem na hora da troca para RUNNING
            Path estado = onlyEstado();
            holder[0] = FileHolder.Handle.start(estado);
            holder[0].hold();
            held.set(true);
            holder[0].releaseAfter(Duration.ofMillis(1500));
        };
        try {
            ExecutionController.Snapshot s = controller.start(request(p));
            assertTrue(held.get(), "o bloqueio real aconteceu na troca para RUNNING");
            assertTrue(controller.running(), "supervisionado apesar do bloqueio");
            assertFalse(s.recordSaved(), "1,5 s de bloqueio passa das tentativas da gravação atômica (~0,6 s): o supervisor assume");
            ExecutionRecord end = waitEnd(controller, Duration.ofSeconds(60));
            waitSaved(controller, Duration.ofSeconds(5));
            assertEquals(ExecutionState.SUCCEEDED, end.state());
            assertEquals(List.of("delayed:RUNNING", "recovered:RUNNING"), diag);
            assertEquals(ExecutionState.SUCCEEDED, onDisk(s.record().id()).state());
            assertEquals(1, lines(out.resolve("efeitos.txt")));
        } finally {
            if (holder[0] != null) holder[0].close();
        }
    }

    @Test
    @DisplayName("F-AGT-01 persistente: o registro não volta → o processo segue supervisionado até o fim, nada é relançado; reinício fecha como OUTCOME_UNKNOWN")
    void falhaPersistenteDepoisDoLancamento() throws Exception {
        Scripted w = new Scripted();
        w.fail = n -> n >= 2;
        RunStore runs = store(w);
        controller = controller(runs);
        ExecutionPackage p = approved("lab_efeito_curto.py");
        ExecutionController.Snapshot s = controller.start(request(p));
        assertTrue(controller.running());
        ExecutionRecord end = waitEnd(controller, Duration.ofSeconds(60));
        assertEquals(ExecutionState.SUCCEEDED, end.state(), "o desfecho em memória é o do protocolo (o registro não o muda)");
        long d = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!diag.contains("lost:SUCCEEDED") && System.nanoTime() < d) Thread.sleep(50);
        assertEquals(List.of("delayed:RUNNING", "lost:SUCCEEDED"), diag, "a falha persistente é SINALIZADA");
        assertFalse(controller.current().orElseThrow().recordSaved(), "a tela continua avisando");
        assertTrue(events(end.id()).contains("RECORD_SAVE_FAILED"));
        assertEquals(ExecutionState.STARTING, onDisk(s.record().id()).state(), "nada incerto é dado por gravado: o disco fica no último estado gravado");
        controller.close();
        controller = null;
        // reinício com o disco bom: o STARTING é fechado sem inventar resultado e sem relançar
        ExecutionController restarted = controller(store(AtomicWriter.DEFAULT));
        try {
            assertEquals(1, restarted.reconcile());
            assertFalse(restarted.running(), "o processo já terminou: nada a adotar");
            Thread.sleep(1500);
            ExecutionRecord r = onDisk(s.record().id());
            assertEquals(ExecutionState.OUTCOME_UNKNOWN, r.state(), "sem prova gravada, nada é presumido");
            assertEquals(ErrorCode.EXEC_AGENT_RESTARTED, r.code());
            assertEquals(s.record().owner(), r.owner(), "o processo lançado continua rastreável no registro fechado");
            assertEquals(1, lines(out.resolve("efeitos.txt")), "nada foi relançado");
        } finally {
            restarted.close();
        }
    }

    @Test
    @DisplayName("F-AGT-02: a gravação do estado FINAL falha uma vez → é tentada de novo e o disco termina no estado final")
    void falhaTransitoriaNoEstadoFinal() throws Exception {
        Scripted w = new Scripted();
        w.fail = n -> n == 3;
        controller = controller(store(w));
        ExecutionPackage p = approved("lab_efeito_curto.py");
        ExecutionController.Snapshot s = controller.start(request(p));
        ExecutionRecord end = waitEnd(controller, Duration.ofSeconds(60));
        waitSaved(controller, Duration.ofSeconds(5));
        assertEquals(ExecutionState.SUCCEEDED, end.state());
        assertEquals(ExecutionState.SUCCEEDED, onDisk(s.record().id()).state(), "o estado final chega ao disco");
        assertEquals(List.of("delayed:SUCCEEDED", "recovered:SUCCEEDED"), diag);
    }

    @Test
    @DisplayName("F-AGT-02 persistente: estado final perdido no prazo → sinalizado; gravado na próxima ocasião (novo início), sem repetir nada")
    void estadoFinalGravadoNaProximaOcasiao() throws Exception {
        Scripted w = new Scripted();
        AtomicBoolean diskBack = new AtomicBoolean();
        w.fail = n -> n >= 3 && !diskBack.get();
        controller = controller(store(w));
        ExecutionPackage p = approved("lab_efeito_curto.py");
        ExecutionController.Snapshot s = controller.start(request(p));
        waitEnd(controller, Duration.ofSeconds(60));
        long d = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!diag.contains("lost:SUCCEEDED") && System.nanoTime() < d) Thread.sleep(50);
        assertEquals(List.of("delayed:SUCCEEDED", "lost:SUCCEEDED"), diag);
        assertEquals(ExecutionState.RUNNING, onDisk(s.record().id()).state(), "o disco ficou no último estado gravado");
        diskBack.set(true);
        ExecutionPackage other = approved("lab_sucesso.py");
        controller.start(request(other));
        assertEquals(ExecutionState.SUCCEEDED, onDisk(s.record().id()).state(), "o estado final pendente foi gravado antes do novo início");
        assertEquals(List.of("delayed:SUCCEEDED", "lost:SUCCEEDED", "recovered:SUCCEEDED"), diag);
        waitEnd(controller, Duration.ofSeconds(60));
        assertEquals(1, lines(out.resolve("efeitos.txt")), "a primeira automação não foi repetida");
    }

    @Test
    @DisplayName("queda REAL do Agent (outra JVM morta) com o disco em STARTING e o processo vivo: o novo Agent ADOTA o processo e recusa outra execução")
    void quedaComRegistroEmStartingEProcessoVivo() throws Exception {
        ExecutionPackage slow = approved("lab_lento.py");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process agent = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), CrashingAgent.class.getName(), agentDir.toString(),
                slow.dir().toString(), python.toString(), out.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Map<String, Long> pids;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(agent.getInputStream(), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            pids = waitPids();
            System.out.println("Agent auxiliar: " + line + "; processo " + pids);
            agent.destroyForcibly().waitFor(); // queda: sem close(), sem gravar mais nada
        }
        Optional<ProcessHandle> orphan = ProcessHandle.of(pids.get("pai"));
        Optional<ProcessHandle> child = ProcessHandle.of(pids.get("filho"));
        try {
            assertTrue(orphan.map(ProcessHandle::isAlive).orElse(false), "o processo lançado sobreviveu à queda do Agent");
            ExecutionRecord before = store(AtomicWriter.DEFAULT).all().get(0);
            assertEquals(ExecutionState.STARTING, before.state(), "em disco só ficou o STARTING");
            ExecutionController restarted = controller(store(AtomicWriter.DEFAULT));
            controller = restarted;
            assertEquals(1, restarted.reconcile());
            assertTrue(restarted.running(), "o processo vivo é ADOTADO (continua rastreável)");
            assertTrue(restarted.current().orElseThrow().record().adopted());
            ExecutionPackage other = approved("lab_sucesso.py");
            ExecRefused e = assertThrows(ExecRefused.class, () -> restarted.start(request(other)), "controle de duplicidade: uma execução ativa");
            assertEquals(ErrorCode.EXEC_ALREADY_RUNNING, e.code());
            assertTrue(restarted.cancel());
            ExecutionRecord end = waitEnd(restarted, Duration.ofSeconds(30));
            assertEquals(ExecutionState.CANCELLED, end.state());
            assertFalse(orphan.get().isAlive(), "a adoção permite encerrar o processo possuído");
            assertEquals(ExecutionState.CANCELLED, onDisk(before.id()).state());
        } finally {
            orphan.ifPresent(ProcessHandle::destroyForcibly);
            child.ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    @DisplayName("escrita parcial: temporário de uma gravação interrompida e estado.json truncado → nada é presumido nem relançado")
    void escritaParcial() throws Exception {
        RunStore runs = store(AtomicWriter.DEFAULT);
        ExecutionPackage p = approved("lab_sucesso.py");
        java.time.Instant now = java.time.Instant.now();
        // (a) gravação interrompida: sobrou um temporário pela metade ao lado de um estado.json bom
        ExecutionId a = ExecutionId.random();
        ExecutionRecord ra = new ExecutionRecord(a, p.kind(), "pacote", p.fingerprint(), ExecutionState.READY, null, now, null, null, null, null, Map.of(),
                0, 0, false, 0).moveTo(ExecutionState.BLOCKED, ErrorCode.EXEC_NOT_APPROVED, now);
        runs.save(ra);
        String full = RunStore.json(ra);
        Files.writeString(runs.dir(a).resolve(".estado.json.123.tmp"), full.substring(0, full.length() / 2));
        // (b) estado.json truncado (gravação não atômica de outra ferramenta, disco)
        ExecutionId b = ExecutionId.random();
        Files.createDirectories(runs.dir(b));
        String running = RunStore.json(new ExecutionRecord(b, p.kind(), "pacote", p.fingerprint(), ExecutionState.RUNNING, null, now, now, null, null, null,
                Map.of(), 0, 0, false, 0));
        Files.writeString(runs.dir(b).resolve("estado.json"), running.substring(0, running.length() / 2));
        // (c) STARTING com a posse do processo gravada pela metade: sem prova de posse, nada é adotado
        ExecutionId c = ExecutionId.random();
        runs.save(new ExecutionRecord(c, p.kind(), "pacote", p.fingerprint(), ExecutionState.STARTING, null, now, null, null, null, null, Map.of(), 0, 0,
                false, 0));
        Files.writeString(runs.dir(c).resolve(ExecutionController.PROCESS_REF), "{\"pid\": " + ProcessHandle.current().pid() + ", \"start\": ");
        RunStore.Listing l = runs.list();
        assertEquals(java.util.Set.of(a, c), java.util.Set.copyOf(l.records().stream().map(ExecutionRecord::id).toList()),
                "o temporário não é lido como registro");
        assertEquals(1, l.unreadable(), "o truncado aparece como ilegível, não some nem vira outro estado");
        controller = controller(runs);
        assertEquals(1, controller.reconcile(), "só o STARTING legível é reconciliado");
        assertFalse(controller.running(), "nada foi lançado nem adotado");
        assertEquals(ExecutionState.BLOCKED, onDisk(a).state());
        assertEquals(ExecutionState.OUTCOME_UNKNOWN, onDisk(c).state(), "posse pela metade não é prova: nada presumido");
        assertEquals(null, onDisk(c).owner());
        try (Stream<Path> s = Files.list(out)) {
            assertEquals(0, s.count(), "nenhuma saída: nada rodou");
        }
    }

    private Path runs(ExecutionId id) {
        return agentDir.resolve("execucoes").resolve(id.value());
    }

    private Path onlyEstado() {
        try (Stream<Path> s = Files.walk(agentDir.resolve("execucoes"))) {
            return s.filter(f -> f.getFileName().toString().equals("estado.json")).findFirst().orElseThrow();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
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
