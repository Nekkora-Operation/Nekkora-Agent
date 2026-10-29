package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionController.CancelResult;
import com.nekkora.agent.exec.ExecutionRecord.CancelOutcome;
import com.nekkora.agent.exec.ExecutionRecord.CancelSource;
import com.nekkora.agent.exec.ExecutionRecord.Origin;
import com.nekkora.agent.storage.AtomicWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D-SA-04 (cancelamento por identificador, idempotente, pedido × confirmação) e D-SA-05 (origem do pedido no histórico),
 * com processos REAIS de laboratório. Sem Python neste ambiente, os testes de processo são PULADOS.
 */
class CancellationTest {
    @TempDir
    Path tmp;

    Path python;
    Path agentDir;
    Path out;
    ApprovalStore approvals;
    RunStore runs;
    ExecutionController controller;
    final List<ExecutionController.Snapshot> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        python = ExecFixtures.python();
        agentDir = tmp.resolve("agent");
        out = Files.createDirectories(tmp.resolve("saida"));
        approvals = new ApprovalStore(agentDir.resolve("aprovacoes.json"), AtomicWriter.DEFAULT);
        runs = new RunStore(agentDir.resolve("execucoes"), AtomicWriter.DEFAULT);
        controller = controller(Duration.ofMillis(100));
    }

    ExecutionController controller(Duration tick) {
        ExecutionController c = new ExecutionController(runs, approvals, new ExecFixtures.FakeSession(), Clock.systemUTC(), agentDir, tick, Duration.ofMillis(200));
        c.addListener(seen::add);
        return c;
    }

    @AfterEach
    void tearDown() {
        if (controller != null) controller.close();
    }

    ExecutionPackage approved(String entry) throws Exception {
        ExecutionPackage p = ExecutionPackage.inspect(ExecFixtures.labPackage(tmp.resolve("pkg-" + entry), "Lab", entry));
        approvals.approve(p, Clock.systemUTC().instant());
        return p;
    }

    ExecutionController.Request request(ExecutionPackage p) {
        return new ExecutionController.Request(p.dir(), p.fingerprint(), python, null, out, Duration.ofMinutes(3));
    }

    ExecutionRecord waitEnd() throws Exception {
        PartialProgressTest.waitFor(() -> !controller.running(), Duration.ofSeconds(60), "fim");
        return controller.current().orElseThrow().record();
    }

    @Test
    @DisplayName("cancelar por identificador: REQUESTED, depois idempotente; confirmado só depois de encerrar a árvore; o primeiro pedido vale")
    void idempotenteEConfirmado() throws Exception {
        Assumptions.assumeTrue(python != null, "sem Python neste ambiente");
        ExecutionId id = controller.start(request(approved("lab_lento.py"))).record().id();
        Instant before = Instant.now();
        assertEquals(CancelResult.REQUESTED, controller.cancel(id, CancelSource.LOCAL_UI));
        assertEquals(CancelResult.ALREADY_REQUESTED, controller.cancel(id, CancelSource.LOCAL_UI), "segundo pedido: nada muda");
        assertEquals(CancelResult.ALREADY_REQUESTED, controller.cancel(id, CancelSource.AGENT_SHUTDOWN), "nem a origem do primeiro pedido");
        ExecutionRecord end = waitEnd();
        assertEquals(ExecutionState.CANCELLED, end.state());
        assertEquals(ErrorCode.EXEC_CANCELLED, end.code());
        assertEquals(CancelOutcome.CONFIRMED, end.cancellation().outcome());
        assertEquals(CancelSource.LOCAL_UI, end.cancellation().source());
        assertFalse(end.cancellation().requested().isBefore(before.minusSeconds(1)));
        assertEquals(end, runs.record(id).orElseThrow(), "o disco tem o mesmo registro");
        // durante: a tela recebeu o estado "em andamento, cancelamento PENDENTE" antes do CANCELLED confirmado
        assertTrue(seen.stream().anyMatch(s -> s.running() && s.record().id().equals(id) && s.record().cancellation() != null
                && s.record().cancellation().outcome() == CancelOutcome.PENDING), "PENDING visível antes da confirmação");
        List<String> ev = runs.events(id);
        int req = index(ev, "CANCEL_REQUESTED");
        int term = index(ev, "TERMINATED");
        int conf = index(ev, "CANCEL_CONFIRMED");
        assertTrue(req >= 0 && req < term && term < conf && conf < ev.size() - 1, "pedido → encerramento → confirmação → estado final: " + ev);
        assertEquals(1, ev.stream().filter(l -> l.contains("CANCEL_REQUESTED")).count(), "um pedido só, mesmo com três chamadas");
        assertEquals(CancelResult.ALREADY_ENDED, controller.cancel(id, CancelSource.LOCAL_UI), "depois do fim: nada a cancelar");
    }

    @Test
    @DisplayName("identificador desconhecido, execução terminada, registro aberto que não é o ativo: recusas distintas; a ativa não é tocada")
    void recusas() throws Exception {
        Assumptions.assumeTrue(python != null, "sem Python neste ambiente");
        assertEquals(CancelResult.UNKNOWN_EXECUTION, controller.cancel(ExecutionId.random(), CancelSource.LOCAL_UI));
        assertFalse(controller.cancel(), "sem execução ativa");
        ExecutionId done = controller.start(request(approved("lab_sucesso.py"))).record().id();
        assertEquals(ExecutionState.SUCCEEDED, waitEnd().state());
        // registro aberto (RUNNING) de outra sessão do Agent, ainda não reconciliado
        ExecutionRecord open = new ExecutionRecord(ExecutionId.random(), ExecutionPackage.Kind.LAB_GENERIC, "pacote", "sha256:" + "a".repeat(64),
                ExecutionState.READY, null, Instant.now(), null, null, null, null, Map.of(), 0, 0, false, 0).moveTo(ExecutionState.STARTING, null, Instant.now())
                .moveTo(ExecutionState.RUNNING, null, Instant.now());
        runs.save(open);
        ExecutionId active = controller.start(request(approved("lab_lento.py"))).record().id();
        assertEquals(CancelResult.ALREADY_ENDED, controller.cancel(done, CancelSource.LOCAL_UI));
        assertEquals(CancelResult.NOT_ACTIVE, controller.cancel(open.id(), CancelSource.LOCAL_UI));
        assertEquals(CancelResult.UNKNOWN_EXECUTION, controller.cancel(ExecutionId.random(), CancelSource.LOCAL_UI));
        Thread.sleep(500);
        assertTrue(controller.running(), "nenhuma recusa tocou a execução ativa");
        assertNull(controller.current().orElseThrow().record().cancellation());
        assertEquals(CancelResult.REQUESTED, controller.cancel(active, CancelSource.LOCAL_UI));
        assertEquals(ExecutionState.CANCELLED, waitEnd().state());
    }

    @Test
    @DisplayName("pedido que chega quando o processo já termina sozinho: NÃO EFETIVADO e o desfecho é o do protocolo (SUCCEEDED)")
    void naoEfetivado() throws Exception {
        Assumptions.assumeTrue(python != null, "sem Python neste ambiente");
        controller.close();
        // volta longa: o supervisor só olha o pedido depois que o processo termina (waitFor devolve no fim do processo)
        controller = controller(Duration.ofSeconds(60));
        ExecutionId id = controller.start(request(approved("lab_fim_anunciado.py"))).record().id();
        PartialProgressTest.waitFor(() -> Files.exists(out.resolve(id.value()).resolve("fim-anunciado")), Duration.ofSeconds(30), "fim anunciado");
        assertEquals(CancelResult.REQUESTED, controller.cancel(id, CancelSource.LOCAL_UI), "aceitar o pedido não é confirmar");
        ExecutionRecord end = waitEnd();
        assertEquals(ExecutionState.SUCCEEDED, end.state(), "o processo terminou por conta própria: vale o protocolo");
        assertEquals(0, end.exitCode());
        assertEquals(CancelOutcome.NOT_EFFECTIVE, end.cancellation().outcome());
        assertTrue(ExecutionSetup.cancellationLabel(end.cancellation())
                .matches("Não efetivado \\(o processo terminou antes\\) · pedido pela pessoa operadora às \\d\\d:\\d\\d:\\d\\d"), ExecutionSetup.cancellationLabel(end.cancellation()));
        assertTrue(ExecutionSetup.cancellationLabel(end.cancellation().resolved(CancelOutcome.PENDING)).startsWith("Aguardando confirmação · pedido "));
        assertTrue(ExecutionSetup.cancellationLabel(new ExecutionRecord.Cancellation(Instant.now(), CancelSource.AGENT_SHUTDOWN, CancelOutcome.CONFIRMED))
                .startsWith("Confirmado · pedido pelo encerramento do Agent às "));
        assertTrue(runs.events(id).stream().anyMatch(l -> l.contains("CANCEL_NOT_EFFECTIVE")));
        assertFalse(runs.events(id).stream().anyMatch(l -> l.contains("TERMINATED")), "nada foi encerrado");
    }

    @Test
    @DisplayName("pedidos concorrentes (8 threads): exatamente um REQUESTED, os demais ALREADY_REQUESTED, um único evento de pedido")
    void concorrente() throws Exception {
        Assumptions.assumeTrue(python != null, "sem Python neste ambiente");
        ExecutionId id = controller.start(request(approved("lab_lento.py"))).record().id();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<CancelResult>> f = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) f.add(pool.submit(() -> {
                go.await();
                return controller.cancel(id, CancelSource.LOCAL_UI);
            }));
            go.countDown();
            long requested = 0;
            for (Future<CancelResult> x : f) {
                CancelResult r = x.get();
                assertTrue(r.accepted(), r.name());
                if (r == CancelResult.REQUESTED) requested++;
            }
            assertEquals(1, requested);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(ExecutionState.CANCELLED, waitEnd().state());
        assertEquals(1, runs.events(id).stream().filter(l -> l.contains("CANCEL_REQUESTED")).count());
    }

    @Test
    @DisplayName("encerramento do Agent com execução ativa: cancelamento com origem AGENT_SHUTDOWN, confirmado")
    void encerramentoDoAgent() throws Exception {
        Assumptions.assumeTrue(python != null, "sem Python neste ambiente");
        ExecutionId id = controller.start(request(approved("lab_lento.py"))).record().id();
        controller.close();
        ExecutionRecord r = runs.record(id).orElseThrow();
        controller = null;
        assertEquals(ExecutionState.CANCELLED, r.state());
        assertEquals(CancelSource.AGENT_SHUTDOWN, r.cancellation().source());
        assertEquals(CancelOutcome.CONFIRMED, r.cancellation().outcome());
    }

    @Test
    @DisplayName("D-SA-05: execução iniciada e recusa antes de iniciar ficam com a origem LOCAL_UI; registro 1.0.0 lido como UNKNOWN")
    void origem() throws Exception {
        ExecutionPackage notApproved = ExecutionPackage.inspect(ExecFixtures.labPackage(tmp.resolve("nao-aprovado"), "Lab", "lab_sucesso.py"));
        Path py = python != null ? python : tmp.resolve("python.exe");
        ExecRefused e = assertThrows(ExecRefused.class, () -> controller.start(new ExecutionController.Request(notApproved.dir(), notApproved.fingerprint(), py,
                null, out, null)));
        assertEquals(ErrorCode.EXEC_NOT_APPROVED, e.code());
        ExecutionRecord blocked = runs.all().get(0);
        assertEquals(ExecutionState.BLOCKED, blocked.state());
        assertEquals(Origin.LOCAL_UI, blocked.origin(), "recusa também registra quem pediu");
        assertEquals("Janela do Agent (pessoa operadora)", ExecutionSetup.originLabel(blocked.origin()));
        assertEquals("Não registrada (execução anterior a esta versão)", ExecutionSetup.originLabel(Origin.UNKNOWN));
        if (python != null) {
            ExecutionId id = controller.start(request(approved("lab_sucesso.py"))).record().id();
            ExecutionRecord r = waitEnd();
            assertEquals(Origin.LOCAL_UI, r.origin());
            assertEquals(Origin.LOCAL_UI, runs.record(id).orElseThrow().origin());
            assertNotNull(r);
        }
    }

    private static int index(List<String> events, String name) {
        for (int i = 0; i < events.size(); i++) if (events.get(i).contains("\"" + name + "\"")) return i;
        return -1;
    }
}
