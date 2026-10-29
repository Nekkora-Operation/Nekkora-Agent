package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DESKTOP-001 G3: posse de processos por PID + início + executável (processos Java: não depende de Python). */
class ProcessOwnerTest {

    /** Filho de teste: dorme; com "neto", lança antes outro igual (descendente). */
    public static final class Sleeper {
        public static void main(String[] args) throws Exception {
            if (args.length > 0 && args[0].equals("neto")) start(false);
            Thread.sleep(120_000);
        }
    }

    private final List<Process> started = new ArrayList<>();

    static String java() {
        return ProcessHandle.current().info().command().orElseThrow();
    }

    static Process start(boolean grandchild) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(java(), "-cp", System.getProperty("java.class.path"), Sleeper.class.getName()));
        if (grandchild) cmd.add("neto");
        return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    @AfterEach
    void cleanup() {
        for (Process p : started) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    private Process track(Process p) {
        started.add(p);
        return p;
    }

    /** Descendentes Java (no Windows cada processo de console tem também um conhost.exe, que também é nosso). */
    private static List<ProcessHandle> javaDescendants(Process p) {
        return p.descendants().filter(h -> h.info().command().map(c -> ProcessOwner.sameExecutable(c, java())).orElse(false)).toList();
    }

    private static void waitJavaDescendants(Process p, int n) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (javaDescendants(p).size() < n && System.nanoTime() < deadline) Thread.sleep(50);
    }

    @Test
    @DisplayName("encerra o processo possuído e o descendente; processo externo do MESMO executável fica intacto")
    void arvorePropria() throws Exception {
        Process own = track(start(true));
        Process external = track(start(false));
        waitJavaDescendants(own, 1);
        assertEquals(1, javaDescendants(own).size(), "o neto existe");
        List<ProcessHandle> kids = own.descendants().toList();
        ProcessOwner owner = ProcessOwner.of(own, java());
        assertTrue(owner.alive());
        int n = owner.terminate(Duration.ofSeconds(10));
        assertTrue(n >= 2, "o processo e o neto (e os conhost deles, no Windows): " + n);
        assertFalse(own.isAlive());
        assertTrue(kids.stream().noneMatch(ProcessHandle::isAlive), "descendente encerrado");
        assertTrue(external.isAlive(), "processo de fora com o mesmo executável não é tocado");
        assertFalse(owner.alive());
        assertEquals(0, owner.terminate(Duration.ofSeconds(1)), "nada mais a encerrar");
    }

    @Test
    @DisplayName("PID com outro instante de início ou outro executável não é posse (PID reutilizado)")
    void pidReutilizado() throws Exception {
        Process p = track(start(false));
        ProcessOwner real = ProcessOwner.of(p, java());
        ProcessOwner reused = new ProcessOwner(real.pid(), real.startMillis() - 60_000, real.executableDigest());
        ProcessOwner other = new ProcessOwner(real.pid(), real.startMillis(), ProcessOwner.digest("C:\\Outro\\programa.exe"));
        assertFalse(reused.alive());
        assertFalse(other.alive());
        assertEquals(0, reused.terminate(Duration.ofSeconds(1)));
        assertEquals(0, other.terminate(Duration.ofSeconds(1)));
        assertTrue(p.isAlive(), "o processo continua: não era posse daquela identidade");
        assertTrue(real.alive());
    }
}
