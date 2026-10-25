package com.nekkora.agent.exec;

import com.nekkora.agent.storage.AtomicWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processo auxiliar do teste de QUEDA (F-AGT-01): um Agent de verdade (outra JVM) cuja gravação do {@code estado.json}
 * passa só na primeira vez (STARTING) e falha daí em diante, como um disco ou um bloqueio que não volta. Lança o pacote,
 * imprime {@code STARTED} (ou {@code REFUSED:<código>}) e fica vivo até o teste o matar — a queda é um
 * {@code destroyForcibly}, sem {@code close()}.
 * Argumentos: pasta do Agent, pasta do pacote, Python, pasta de saída.
 */
public final class CrashingAgent {
    private CrashingAgent() {}

    public static void main(String[] args) throws Exception {
        Path agentDir = Path.of(args[0]);
        AtomicInteger estado = new AtomicInteger();
        AtomicWriter failing = (target, data) -> {
            if (target.getFileName().toString().equals("estado.json") && estado.incrementAndGet() > 1) {
                throw new java.nio.file.AccessDeniedException(target.toString());
            }
            AtomicWriter.DEFAULT.write(target, data);
        };
        ApprovalStore approvals = new ApprovalStore(agentDir.resolve("aprovacoes.json"), AtomicWriter.DEFAULT);
        RunStore runs = new RunStore(agentDir.resolve("execucoes"), failing);
        ExecutionController c = new ExecutionController(runs, approvals, new ExecFixtures.FakeSession(), Clock.systemUTC(), agentDir, Duration.ofMillis(100),
                Duration.ofMillis(200));
        ExecutionPackage p = ExecutionPackage.inspect(Path.of(args[1]));
        approvals.approve(p, Instant.now());
        try {
            c.start(new ExecutionController.Request(p.dir(), p.fingerprint(), Path.of(args[2]), null, Path.of(args[3]), null));
            System.out.println("STARTED");
        } catch (ExecRefused e) {
            System.out.println("REFUSED:" + e.code().code());
        }
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
