package com.nekkora.agent.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** OBS-G4 (leitor): incremental, linha parcial, linha enorme, UTF-8 inválido, truncamento, troca, ligação/junção, permissão. */
class SourceFileTest {
    @TempDir
    Path tmp;

    private Path base() throws IOException {
        return Files.createDirectories(tmp.resolve("ex-run"));
    }

    private static void append(Path f, String s) throws IOException {
        Files.createDirectories(f.getParent());
        Files.writeString(f, s, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    void readsOnlyCompleteLinesIncrementallyAndDeclaresTheFinalPartialLine() throws Exception {
        Path base = base();
        Path f = base.resolve("execucao").resolve("events.jsonl");
        SourceFile s = new SourceFile(base, List.of("execucao", "events.jsonl"), 100, 0);
        assertEquals(SourceFile.Problem.MISSING, s.read(1000, false).problem());
        append(f, "a\nb\nc-incompleta");
        SourceFile.Batch b = s.read(1000, false);
        assertEquals(List.of("a", "b"), b.lines().stream().map(SourceFile.Line::text).toList());
        assertEquals(4, s.position(), "a linha incompleta fica para a próxima leitura");
        append(f, "-agora\nd\r\n");
        b = s.read(1000, false);
        assertEquals(List.of("c-incompleta-agora", "d"), b.lines().stream().map(SourceFile.Line::text).toList());
        assertEquals(Files.size(f), b.lines().get(1).end());
        append(f, "sem-quebra");
        assertTrue(s.read(1000, false).lines().isEmpty());
        b = s.read(1000, true);
        assertEquals(SourceFile.Invalid.PARTIAL, b.lines().get(0).invalid(), "na leitura final a linha sem quebra é declarada parcial");
        assertNull(b.lines().get(0).text());
    }

    @Test
    void budgetLimitsEachReadAndReportsMore() throws Exception {
        Path base = base();
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < 1000; i++) t.append("linha-").append(i).append('\n');
        append(base.resolve("e.jsonl"), t.toString());
        SourceFile s = new SourceFile(base, List.of("e.jsonl"), 100, 0);
        int lines = 0;
        int reads = 0;
        SourceFile.Batch b;
        do {
            b = s.read(512, false);
            lines += b.lines().size();
            reads++;
        } while (b.more());
        assertEquals(1000, lines);
        assertTrue(reads > 10, "contrapressão: várias leituras pequenas (" + reads + ")");
    }

    @Test
    void oversizedLineIsSkippedOnceAndInvalidUtf8IsRejected() throws Exception {
        Path base = base();
        Path f = base.resolve("e.jsonl");
        append(f, "ok1\n" + "x".repeat(5000));
        SourceFile s = new SourceFile(base, List.of("e.jsonl"), 100, 0);
        assertEquals(List.of("ok1"), s.read(1000, false).lines().stream().map(SourceFile.Line::text).toList());
        assertTrue(s.read(1000, false).lines().isEmpty());
        append(f, "y".repeat(3000) + "\nok2\n");
        Files.write(f, new byte[] {(byte) 0xC3, (byte) 0x28, '\n'}, StandardOpenOption.APPEND);
        List<SourceFile.Line> got = new java.util.ArrayList<>();
        SourceFile.Batch b;
        do {
            b = s.read(1000, false);
            got.addAll(b.lines());
        } while (b.more());
        assertEquals(SourceFile.Invalid.TOO_LONG, got.get(0).invalid());
        assertEquals("ok2", got.get(1).text());
        assertEquals(SourceFile.Invalid.ENCODING, got.get(2).invalid());
        assertEquals(3, got.size(), "a linha enorme conta uma vez só");
    }

    @Test
    void truncationOrReplacementStopsTheSourceInsteadOfRereading() throws Exception {
        Path base = base();
        Path f = base.resolve("e.jsonl");
        append(f, "{\"primeira\":1}\nb\n");
        SourceFile s = new SourceFile(base, List.of("e.jsonl"), 100, 0);
        assertEquals(2, s.read(1000, false).lines().size());
        Files.writeString(f, "c\n", StandardCharsets.UTF_8); // truncado (rotação simulada)
        assertEquals(SourceFile.Problem.REPLACED, s.read(1000, false).problem());

        SourceFile s2 = new SourceFile(base, List.of("e.jsonl"), 100, 0);
        Files.writeString(f, "{\"primeira\":1}\nb\n", StandardCharsets.UTF_8);
        assertEquals(2, s2.read(1000, false).lines().size());
        Files.writeString(f, "{\"outraaaaa\":1}\nbb\nmais\n", StandardCharsets.UTF_8); // trocado por outro, maior
        assertEquals(SourceFile.Problem.REPLACED, s2.read(1000, false).problem(), "primeira linha mudou: não é o mesmo arquivo");
    }

    @Test
    void symbolicLinksAreRefused() throws Exception {
        Path base = base();
        Path real = Files.createDirectories(tmp.resolve("fora")).resolve("events.jsonl");
        Files.writeString(real, "segredo\n", StandardCharsets.UTF_8);
        Files.createDirectories(base.resolve("execucao"));
        try {
            Files.createSymbolicLink(base.resolve("execucao").resolve("events.jsonl"), real);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("sem privilégio para criar ligação simbólica neste ambiente");
        }
        SourceFile s = new SourceFile(base, List.of("execucao", "events.jsonl"), 100, 0);
        SourceFile.Batch b = s.read(1000, true);
        assertEquals(SourceFile.Problem.UNSAFE, b.problem());
        assertTrue(b.lines().isEmpty());
    }

    @Test
    void windowsJunctionInThePathIsRefused() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "junção é do Windows");
        Path base = base();
        Path target = Files.createDirectories(tmp.resolve("alvo-da-juncao"));
        Files.writeString(target.resolve("events.jsonl"), "segredo\n", StandardCharsets.UTF_8);
        Process p = new ProcessBuilder(System.getenv("SystemRoot") + "\\System32\\cmd.exe", "/c", "mklink", "/J", base.resolve("execucao").toString(),
                target.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        Assumptions.assumeTrue(p.exitValue() == 0, "mklink /J indisponível");
        try {
            assertTrue(Files.isRegularFile(base.resolve("execucao").resolve("events.jsonl")), "a junção funciona para quem a segue");
            SourceFile s = new SourceFile(base, List.of("execucao", "events.jsonl"), 100, 0);
            assertEquals(SourceFile.Problem.UNSAFE, s.read(1000, true).problem(), "ponto de nova análise (junção) não é seguido");
        } finally {
            Files.delete(base.resolve("execucao")); // remove só a junção (a limpeza do JUnit não a desfaz)
        }
    }

    @Test
    void permissionFailureIsReportedAndRecovers() throws Exception {
        Path base = base();
        Path f = base.resolve("e.jsonl");
        append(f, "a\n");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Assumptions.assumeTrue(windows || !"root".equals(System.getProperty("user.name")), "root ignora permissões");
        deny(f, windows, true);
        try {
            SourceFile s = new SourceFile(base, List.of("e.jsonl"), 100, 0);
            assertEquals(SourceFile.Problem.UNREADABLE, s.read(1000, false).problem());
            deny(f, windows, false);
            assertEquals(List.of("a"), s.read(1000, false).lines().stream().map(SourceFile.Line::text).toList());
        } finally {
            deny(f, windows, false);
        }
    }

    static void deny(Path f, boolean windows, boolean on) throws Exception {
        if (windows) {
            String user = System.getenv("USERNAME");
            List<String> cmd = on ? List.of(System.getenv("SystemRoot") + "\\System32\\icacls.exe", f.toString(), "/deny", user + ":(R)")
                    : List.of(System.getenv("SystemRoot") + "\\System32\\icacls.exe", f.toString(), "/remove:d", user);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            assertTrue(p.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, p.exitValue());
        } else {
            Files.setPosixFilePermissions(f, on ? java.util.Set.of() : java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void sourceNeverResolvesPathsFromContent() throws Exception {
        Path base = base();
        append(base.resolve("e.jsonl"), "{\"path\":\"..\\\\..\\\\fora\\\\events.jsonl\"}\n");
        SourceFile s = new SourceFile(base, List.of("e.jsonl"), 100, 0);
        SourceFile.Batch b = s.read(1000, false);
        assertNull(b.problem());
        assertFalse(b.lines().isEmpty(), "o conteúdo é só texto para o mapeador; nenhum caminho é seguido");
    }
}
