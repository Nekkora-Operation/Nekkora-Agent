package com.nekkora.agent.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-AGT-01: gravação atômica com o alvo aberto por OUTRO processo (bloqueio real do Windows) e queda no meio da
 * gravação. A troca é tentada de novo por pouco tempo (recuperação controlada); depois disso a falha é explícita e o
 * alvo fica como estava.
 */
class AtomicWriterTest {
    @TempDir
    Path tmp;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("leitor aberto em outro processo e liberado logo: a troca é concluída (bloqueio real do Windows)")
    void leitorLiberadoLogo() throws Exception {
        Path target = tmp.resolve("estado.json");
        Files.writeString(target, "antigo");
        try (FileHolder.Handle h = FileHolder.Handle.start(target)) {
            h.hold();
            Thread release = h.releaseAfter(Duration.ofMillis(150));
            AtomicWriter.DEFAULT.write(target, "novo".getBytes(StandardCharsets.UTF_8));
            release.join(5000);
        }
        assertEquals("novo", Files.readString(target));
        assertEquals(0, temporaries(), "nenhum temporário sobra");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("leitor que não solta: falha EXPLÍCITA em tempo limitado; o alvo fica como estava e nenhum temporário sobra")
    void leitorQueNaoSolta() throws Exception {
        Path target = tmp.resolve("estado.json");
        Files.writeString(target, "antigo");
        try (FileHolder.Handle h = FileHolder.Handle.start(target)) {
            h.hold();
            long t0 = System.nanoTime();
            assertThrows(AccessDeniedException.class, () -> AtomicWriter.DEFAULT.write(target, "novo".getBytes(StandardCharsets.UTF_8)));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 3000, "tentativas limitadas (" + ms + " ms)");
            h.release();
        }
        assertEquals("antigo", Files.readString(target), "nenhuma gravação incerta: o alvo é o anterior");
        assertEquals(0, temporaries());
    }

    @Test
    @DisplayName("queda do processo no meio da gravação (morto 6 vezes em instantes aleatórios): o alvo é sempre uma versão inteira")
    void quedaNoMeioDaGravacao() throws Exception {
        Path target = tmp.resolve("estado.json");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Random rnd = new Random(20261024);
        for (int round = 0; round < 6; round++) {
            Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), WriterLoop.class.getName(), target.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals("READY", r.readLine());
                Thread.sleep(150 + rnd.nextInt(400));
                p.destroyForcibly().waitFor();
            }
            if (!Files.exists(target)) continue; // morto antes da primeira troca: nada foi gravado, e isso também é inteiro
            byte[] got = Files.readAllBytes(target);
            assertEquals(WriterLoop.SIZE, got.length, "tamanho de uma versão inteira (rodada " + round + ")");
            byte[] expected = new byte[WriterLoop.SIZE];
            Arrays.fill(expected, got[0]);
            assertArrayEquals(expected, got, "uma única versão, sem mistura (rodada " + round + ")");
        }
    }

    private long temporaries() throws Exception {
        try (Stream<Path> s = Files.list(tmp)) {
            return s.filter(f -> f.getFileName().toString().endsWith(".tmp")).count();
        }
    }
}
