package com.nekkora.agent.exec;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Posse de um processo lançado pelo Agent: PID + INSTANTE DE INÍCIO + executável. Um PID reutilizado pelo Windows (outro
 * início) não é posse; processo externo de mesmo nome nunca é tocado. Só se encerra o processo possuído e os seus
 * descendentes que começaram depois dele.
 */
public record ProcessOwner(long pid, long startMillis, String executable) {

    /** Posse do processo recém-lançado. */
    public static ProcessOwner of(Process p, String executable) {
        ProcessHandle h = p.toHandle();
        long start = h.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
        return new ProcessOwner(h.pid(), start, executable);
    }

    /** O processo com este PID ainda é o MESMO que o Agent lançou (mesmo início e mesmo executável)? */
    public Optional<ProcessHandle> handle() {
        if (startMillis < 0) return Optional.empty();
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).filter(h -> h.info().startInstant().map(Instant::toEpochMilli).orElse(-2L) == startMillis)
                .filter(h -> h.info().command().map(c -> sameExecutable(c, executable)).orElse(false));
    }

    public boolean alive() {
        return handle().isPresent();
    }

    /**
     * Encerra os descendentes possuídos (iniciados depois do processo) e o processo. Espera até {@code wait}. Devolve
     * quantos processos foram encerrados.
     */
    public int terminate(Duration wait) {
        Optional<ProcessHandle> root = handle();
        if (root.isEmpty()) return 0;
        List<ProcessHandle> owned = new ArrayList<>();
        root.get().descendants().forEach(d -> {
            long s = d.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
            if (s >= startMillis) owned.add(d); // PID reaproveitado por outro programa começou antes: não é nosso
        });
        int n = 0;
        // de baixo para cima: os netos primeiro, para nada ser reparentado no meio
        for (int i = owned.size() - 1; i >= 0; i--) if (owned.get(i).destroyForcibly()) n++;
        if (root.get().destroyForcibly()) n++;
        long deadline = System.nanoTime() + wait.toNanos();
        for (ProcessHandle h : owned) waitGone(h, deadline);
        waitGone(root.get(), deadline);
        return n;
    }

    private static void waitGone(ProcessHandle h, long deadline) {
        while (h.isAlive() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static boolean sameExecutable(String a, String b) {
        return a != null && b != null && a.replace('/', '\\').equalsIgnoreCase(b.replace('/', '\\'));
    }
}
