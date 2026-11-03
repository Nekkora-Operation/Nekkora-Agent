package com.nekkora.agent.app;

import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExternalRuns;
import com.nekkora.agent.exec.RunStore;
import java.io.File;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * AGENT-PYTHON-001: liga as execuções criadas FORA deste processo (lançador do Python externo) às partes do Agent que já
 * existem, sem que o executor e o transporte se chamem: noticia cada pasta nova à coleta da atividade (que já sabe ler
 * {@code estado.json}/{@code eventos.jsonl} de qualquer execução) e, de tempos em tempos, reconcilia as execuções cujo
 * lançador morreu ({@link ExternalRuns}). Só lê nomes de pasta; nunca toca no processo do cliente.
 */
final class ExternalWatcher implements AutoCloseable {
    static final Duration SCAN = Duration.ofSeconds(2);
    static final Duration RECONCILE = Duration.ofSeconds(30);

    private final RunStore runs;
    private final Consumer<ExecutionId> track;
    private final Clock clock;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService exec;
    private Instant nextReconcile = Instant.MIN;

    ExternalWatcher(RunStore runs, Consumer<ExecutionId> track, Clock clock) {
        this.runs = runs;
        this.track = track;
        this.clock = clock;
    }

    synchronized void start() {
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nekkora-agent-externo");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        exec.scheduleWithFixedDelay(this::tick, 0, SCAN.toMillis(), TimeUnit.MILLISECONDS);
    }

    void tick() {
        try {
            File[] dirs = runs.root().toFile().listFiles(File::isDirectory);
            if (dirs != null) {
                for (File f : dirs) {
                    if (ExecutionId.valid(f.getName()) && seen.add(f.getName())) track.accept(new ExecutionId(f.getName()));
                }
            }
            Instant now = clock.instant();
            if (!now.isBefore(nextReconcile)) {
                nextReconcile = now.plus(RECONCILE);
                ExternalRuns.reconcile(runs, clock);
            }
        } catch (RuntimeException e) {
            // a linha de fundo continua; nenhuma automação é afetada
        }
    }

    @Override
    public synchronized void close() {
        if (exec != null) exec.shutdownNow();
        exec = null;
    }
}
