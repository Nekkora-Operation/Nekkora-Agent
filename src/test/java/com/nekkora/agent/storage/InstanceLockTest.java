package com.nekkora.agent.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A05 e A06 (parte automatizada): disputa entre processos reais com sincronização explícita
 * (barreira READY/GO, sem sleeps) e recuperação após queda (processo morto à força).
 */
class InstanceLockTest {

    @TempDir
    Path dir;

    private final List<Probe> probes = new ArrayList<>();

    @AfterEach
    void killProbes() throws InterruptedException {
        for (Probe p : probes) p.process.destroyForcibly();
        for (Probe p : probes) p.process.waitFor(30, TimeUnit.SECONDS); // a pasta temporária só é apagada sem handles abertos
        // limpeza (não é parte da verificação): o Windows pode concluir o fechamento dos handles de um processo
        // morto alguns instantes depois de sinalizar o término; tenta apagar por até 10 s antes do @TempDir
        Path f = dir.resolve("agent.lock");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                Files.deleteIfExists(f);
                break;
            } catch (IOException e) {
                if (System.nanoTime() > deadline) break;
                Thread.onSpinWait();
            }
        }
    }

    @Test
    void sameProcessCannotAcquireTwiceAndFileSurvivesRelease() throws IOException {
        Path f = dir.resolve("agent.lock");
        InstanceLock.Acquired a = assertInstanceOf(InstanceLock.Acquired.class, InstanceLock.tryAcquire(f));
        assertInstanceOf(InstanceLock.HeldElsewhere.class, InstanceLock.tryAcquire(f));
        assertTrue(a.lock().isHeld());
        a.lock().close();
        a.lock().close(); // idempotente
        assertFalse(a.lock().isHeld());
        assertTrue(Files.exists(f), "o arquivo da trava nunca é apagado");
        InstanceLock.Acquired b = assertInstanceOf(InstanceLock.Acquired.class, InstanceLock.tryAcquire(f));
        b.lock().close();
    }

    @Test
    void activeLockFileCannotBeDeletedOnWindows() {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        Path f = dir.resolve("agent.lock");
        InstanceLock.Acquired a = assertInstanceOf(InstanceLock.Acquired.class, InstanceLock.tryAcquire(f));
        try {
            assertThrows(IOException.class, () -> Files.delete(f), "apagar a trava ativa não pode liberar outra instância");
            assertTrue(Files.exists(f));
        } finally {
            a.lock().close();
        }
    }

    @Test
    void simultaneousProcessesProduceExactlyOneOwner() throws Exception {
        Path f = dir.resolve("agent.lock");
        int n = 8;
        for (int i = 0; i < n; i++) probes.add(Probe.start(f));
        for (Probe p : probes) assertEquals("READY", p.readLine());
        for (Probe p : probes) p.send("GO"); // todos liberados juntos
        int acquired = 0;
        int held = 0;
        for (Probe p : probes) {
            String r = p.readLine();
            if ("ACQUIRED".equals(r)) acquired++;
            else if ("HELD".equals(r)) held++;
            else throw new AssertionError("resultado inesperado: " + r);
        }
        assertEquals(1, acquired, "um único proprietário");
        assertEquals(n - 1, held);
        assertTrue(Files.exists(f));
    }

    @Test
    void crashedOwnerDoesNotLeaveAStuckLock() throws Exception {
        Path f = dir.resolve("agent.lock");
        Probe owner = Probe.start(f);
        probes.add(owner);
        assertEquals("READY", owner.readLine());
        owner.send("GO");
        assertEquals("ACQUIRED", owner.readLine());

        Probe blocked = Probe.start(f);
        probes.add(blocked);
        assertEquals("READY", blocked.readLine());
        blocked.send("GO");
        assertEquals("HELD", blocked.readLine(), "enquanto o dono vive, ninguém mais adquire");

        owner.process.destroyForcibly(); // TerminateProcess: sem liberação ordenada
        assertTrue(owner.process.onExit().get(30, TimeUnit.SECONDS).isAlive() == false);
        assertTrue(Files.exists(f), "o arquivo continua no disco após a queda");

        Probe next = Probe.start(f);
        probes.add(next);
        assertEquals("READY", next.readLine());
        next.send("GO");
        assertEquals("ACQUIRED", next.readLine(), "recuperação imediata após a queda, sem apagar o arquivo");
    }

    private record Probe(Process process, BufferedReader out, Writer in) {
        static Probe start(Path lockFile) throws IOException {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), LockProbe.class.getName(), lockFile.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return new Probe(p, new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)),
                    new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        }

        String readLine() throws IOException {
            return out.readLine();
        }

        void send(String line) throws IOException {
            in.write(line + "\n");
            in.flush();
        }
    }
}
