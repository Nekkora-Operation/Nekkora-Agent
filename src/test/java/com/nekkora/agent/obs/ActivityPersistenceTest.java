package com.nekkora.agent.obs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.obs.ActivityEvent.Category;
import com.nekkora.agent.obs.ActivityEvent.Level;
import com.nekkora.agent.obs.ActivityEvent.Source;
import com.nekkora.agent.obs.ActivityView.Gap;
import com.nekkora.agent.obs.ActivityView.Integrity;
import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * OBS-PERSISTENCE-HARDENING: gravação do {@code atividade.jsonl} que falha. Leitor real do Windows que impede a
 * escrita; falha persistente; fila cheia; escrita parcial; confirmação perdida; arquivo alterado por outro; queda com
 * pendências e reabertura. Em nenhum caso a automação é tocada, nada é gravado duas vezes e nenhuma lacuna é escondida.
 */
class ActivityPersistenceTest {
    @TempDir
    Path tmp;

    ObsFixtures.TestClock clock;
    RunStore store;
    Path saida;

    static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        clock = new ObsFixtures.TestClock();
        store = ObsFixtures.store(tmp.resolve("agent"));
        saida = Files.createDirectories(tmp.resolve("saida"));
    }

    private static ActivityEvent fw(String id, long pos) {
        return ActivityEvent.of(id, T0, Source.FRAMEWORK, "ACTION_COMPLETED", Level.INFO, Category.ACTIVITY).step("s.a").position(pos).build();
    }

    private static List<ActivityEvent> fws(String id, long from, int n) {
        List<ActivityEvent> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(fw(id, from + i));
        return l;
    }

    /** Sequências gravadas no disco, na ordem, e a garantia de que cada linha é um envelope válido. */
    private static List<Long> diskSequences(Path dir, String id) throws IOException {
        List<Long> seqs = new ArrayList<>();
        for (String line : Files.readString(dir.resolve(ActivityLog.FILE), StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            seqs.add(ActivityCodec.decode(line).filter(e -> e.agentExecutionId().equals(id)).orElseThrow(() -> new AssertionError("linha inválida: " + line))
                    .sequence());
        }
        return seqs;
    }

    /** Na memória, sempre na ordem da sequência (a tela busca os novos por sequência). */
    private static void assertOrdered(ActivityLog log) {
        long prev = 0;
        for (ActivityEvent e : log.all()) {
            assertTrue(e.sequence() > prev, "memória fora de ordem em " + e.sequence());
            prev = e.sequence();
        }
    }

    private static List<Long> range(long from, long to) {
        List<Long> l = new ArrayList<>();
        for (long i = from; i <= to; i++) l.add(i);
        return l;
    }

    private static final ActivityLog.Writer FAILING = (f, data) -> {
        throw new IOException("O arquivo já está sendo usado por outro processo");
    };

    /** Diagnósticos recebidos pela linha de fundo (o que iria para o log do Agent). */
    static final class Diag implements ActivityService.StoreDiagnostics {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void delayed(ExecutionId id, int pending) {
            calls.add("delayed");
        }

        @Override
        public void recovered(ExecutionId id) {
            calls.add("recovered");
        }

        @Override
        public void lost(ExecutionId id, long lost) {
            calls.add("lost:" + lost);
        }
    }

    // ------------------------------------------------------------------------------------- leitor real do Windows

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void readerThatDeniesWritingOnWindowsDelaysTheHistoryWhichIsWrittenOnceInOrderWhenReleased() throws Exception {
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, clock.instant());
        List<String> fwLines = ActivityServiceTest.rebase(ObsFixtures.g1Events(), clock.instant());
        run.frameworkLines(fwLines.subList(0, 40));
        ActivityService s = new ActivityService(store, clock, Duration.ofMillis(50), ActivityService.MAX_TOTAL_BYTES);
        Diag diag = new Diag();
        s.storeDiagnostics(diag);
        s.track(run.id());
        s.tick();
        Path dir = store.dir(run.id());
        Path file = dir.resolve(ActivityLog.FILE);
        long before = Files.size(file);
        byte[] recordBefore = Files.readAllBytes(dir.resolve("estado.json"));
        byte[] supervisorBefore = Files.readAllBytes(dir.resolve("eventos.jsonl"));

        // (a) outro programa abre o histórico SEM compartilhar a escrita (como ReadAllLines/Get-Content no Windows)
        try (FileChannel reader = FileChannel.open(file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_WRITE)) {
            run.frameworkLines(fwLines.subList(40, 80));
            s.tick();
            assertEquals(before, Files.size(file), "a gravação falhou de verdade (nada entrou no arquivo)");
            // (b) a pendência fica visível: na saúde, na completude e no diagnóstico
            ActivityView v = s.view(run.id(), 0).orElseThrow();
            assertEquals(40, v.health().pendingWrites());
            ActivityView.Completeness c = ActivityView.completeness(v.health(), v.events());
            assertEquals(Integrity.INCOMPLETE, c.integrity());
            assertTrue(c.gaps().contains(Gap.STORE_PENDING));
            assertEquals(List.of("delayed"), diag.calls);
            // novas tentativas enquanto o leitor segura o arquivo: limitadas pelo intervalo, nada se perde ainda
            for (int i = 0; i < 4; i++) {
                clock.plusMillis(1000);
                s.tick();
            }
            assertEquals(before, Files.size(file));
            assertEquals(40, s.view(run.id(), 0).orElseThrow().health().pendingWrites());
            assertFalse(s.view(run.id(), 0).orElseThrow().health().storeFailed());
            assertEquals(List.of("delayed"), diag.calls, "o log recebe a transição, não cada tentativa");
        }
        // (e) a automação não foi tocada: registro e eventos do supervisor intactos durante todo o bloqueio
        assertArrayEquals(recordBefore, Files.readAllBytes(dir.resolve("estado.json")));
        assertArrayEquals(supervisorBefore, Files.readAllBytes(dir.resolve("eventos.jsonl")));
        assertEquals(ExecutionState.RUNNING, run.record().state());

        // (c) o leitor liberou o arquivo; (d) as pendentes entram na ordem, uma só vez, com a mesma sequência
        List<Long> shown = s.view(run.id(), 0).orElseThrow().events().stream().map(ActivityEvent::sequence).toList();
        clock.plusMillis(6000);
        s.tick();
        assertEquals(0, s.view(run.id(), 0).orElseThrow().health().pendingWrites());
        assertEquals(List.of("delayed", "recovered"), diag.calls);
        assertEquals(shown, diskSequences(dir, run.id().value()).subList(0, shown.size()), "as sequências mostradas são as gravadas");

        run.frameworkLines(fwLines.subList(80, fwLines.size()));
        run.finish(ExecutionState.SUCCEEDED, clock.instant());
        for (int i = 0; i < 6; i++) {
            clock.plusMillis(1000);
            s.tick();
        }
        List<Long> seqs = diskSequences(dir, run.id().value());
        assertEquals(range(1, seqs.size()), seqs, "sequência contínua no disco, sem repetição");
        ActivityLog onDisk = ActivityLog.read(dir, run.id().value());
        assertEquals(fwLines.size(), onDisk.all().stream().filter(e -> e.source() == Source.FRAMEWORK).count(), "cada evento do Framework uma vez");
        Set<Long> positions = new HashSet<>();
        for (ActivityEvent e : onDisk.all()) if (e.source() == Source.FRAMEWORK) assertTrue(positions.add(e.sourcePosition()));
        ActivityView after = s.view(run.id(), 0).orElseThrow();
        assertEquals(Integrity.COMPLETE, ActivityView.completeness(after.health(), after.events()).integrity(), "completa só com a prova no disco");
        assertEquals(ExecutionState.SUCCEEDED, run.record().state(), "o desfecho é o do supervisor");
        s.close();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void agentKilledWhileWritesArePendingRecollectsFromTheSourcesWithoutGapsOrRepeats() throws Exception {
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, clock.instant());
        List<String> fwLines = ActivityServiceTest.rebase(ObsFixtures.g1Events(), clock.instant());
        run.frameworkLines(fwLines.subList(0, 30));
        ActivityService first = new ActivityService(store, clock, Duration.ofMillis(50), ActivityService.MAX_TOTAL_BYTES);
        first.track(run.id());
        first.tick();
        Path dir = store.dir(run.id());
        try (FileChannel reader = FileChannel.open(dir.resolve(ActivityLog.FILE), StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_WRITE)) {
            run.frameworkLines(fwLines.subList(30, 70));
            first.tick();
            assertEquals(40, first.view(run.id(), 0).orElseThrow().health().pendingWrites());
            // queda do Agent: nada de close(), as pendentes somem com o processo
        }
        run.frameworkLines(fwLines.subList(70, fwLines.size()));
        run.finish(ExecutionState.SUCCEEDED, clock.instant());
        ActivityService second = new ActivityService(store, clock, Duration.ofMillis(50), ActivityService.MAX_TOTAL_BYTES);
        second.catchUp();
        for (int i = 0; i < 6; i++) {
            clock.plusMillis(1000);
            second.tick();
        }
        List<Long> seqs = diskSequences(dir, run.id().value());
        assertEquals(range(1, seqs.size()), seqs);
        ActivityLog onDisk = ActivityLog.read(dir, run.id().value());
        assertEquals(fwLines.size(), onDisk.all().stream().filter(e -> e.source() == Source.FRAMEWORK).count(),
                "o que estava só na memória foi relido das origens a partir das posições gravadas");
        ActivityView v = second.view(run.id(), 0).orElseThrow();
        assertEquals(Integrity.COMPLETE, ActivityView.completeness(v.health(), v.events()).integrity());
        second.close();
    }

    // ------------------------------------------------------------------------------------- negativos (todas as plataformas)

    @Test
    void persistentFailureGivesUpAfterTheLimitCountsTheLossAndKeepsTheGapAfterReopening() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.append(fws(id, 1, 2), T0);
        log.writer(FAILING);
        log.append(fws(id, 3, 3), T0);
        Instant t = T0;
        for (int i = 0; i < ActivityLog.MAX_ATTEMPTS + 2 && log.lost() == 0; i++) {
            t = t.plus(ActivityLog.MAX_RETRY);
            log.flush(t);
        }
        assertEquals(3, log.lost(), "as 3 pendentes foram descartadas e contadas");
        assertTrue(log.storeFailed());
        assertEquals(1, log.pendingCount(), "só o aviso EVENT_GAP (AGT-OBS-009) aguarda gravação");
        assertOrdered(log);
        log.writer(ActivityLog.DISK);
        log.flush(t.plus(ActivityLog.MAX_RETRY));
        assertEquals(0, log.pendingCount());
        assertEquals(List.of(1L, 2L, 6L), diskSequences(tmp, id), "a lacuna 3..5 fica no disco: nada fabricado");

        ActivityLog reopened = ActivityLog.read(tmp, id);
        assertTrue(reopened.sequenceGap());
        assertTrue(reopened.storeFailed(), "o motivo sobrevive ao reinício");
        ActivityEvent gap = reopened.all().get(2);
        assertEquals("EVENT_GAP", gap.code());
        assertEquals("AGT-OBS-009", gap.errorCode());
        assertEquals(3L, gap.counts().get("lost"));
        assertTrue(ActivityView.completeness(new ActivityView.Health(false, true, false, ActivityView.FrameworkState.NOT_APPLICABLE, 0, 0, 0, 0, 0,
                false, reopened.sequenceGap(), 0, false, reopened.storeFailed(), 0, 0, null, false), reopened.all()).gaps().contains(Gap.STORE_FAILED));
    }

    @Test
    void retriesAreSpacedAndBoundedNeverABusyLoop() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        int[] calls = {0};
        log.writer((f, d) -> {
            calls[0]++;
            throw new IOException("bloqueado");
        });
        log.append(fws(id, 1, 1), T0);
        for (int i = 0; i < 1000; i++) log.flush(T0.plusMillis(100)); // antes do intervalo: nenhuma tentativa
        assertEquals(1, calls[0]);
        log.flush(T0.plus(ActivityLog.FIRST_RETRY));
        assertEquals(2, calls[0]);
        assertEquals(Duration.ofMillis(400), ActivityLog.backoff(2));
        assertEquals(ActivityLog.MAX_RETRY, ActivityLog.backoff(30), "intervalo com teto");
    }

    @Test
    void fullQueueDiscardsAndCountsInsteadOfGrowingWithoutLimit() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.writer(FAILING);
        int total = ActivityLog.MAX_PENDING + 10;
        for (int i = 0; i < total; i += 100) {
            log.append(fws(id, i + 1L, Math.min(100, total - i)), T0);
            assertTrue(log.pendingCount() <= ActivityLog.MAX_PENDING, "memória limitada");
        }
        assertEquals(ActivityLog.MAX_PENDING, log.lost());
        assertTrue(log.storeFailed());
        assertEquals(11, log.pendingCount(), "o aviso de perda e os 10 seguintes");
        assertOrdered(log);
        log.writer(ActivityLog.DISK);
        log.flush(T0.plus(ActivityLog.MAX_RETRY));
        List<Long> seqs = diskSequences(tmp, id);
        assertEquals(range(ActivityLog.MAX_PENDING + 1L, total + 1L), seqs, "aviso + 10 seguintes; a lacuna fica à vista");
        assertTrue(ActivityLog.read(tmp, id).sequenceGap());
    }

    @Test
    void partialWriteIsCompletedNotRepeated() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.append(fws(id, 1, 2), T0);
        log.writer((f, data) -> { // grava 1,5 linha e falha
            int cut = data.length / 2;
            Files.write(f, Arrays.copyOf(data, cut), StandardOpenOption.APPEND);
            throw new IOException("disco cheio no meio da escrita");
        });
        log.append(fws(id, 3, 3), T0);
        assertEquals(3, log.pendingCount());
        log.writer(ActivityLog.DISK);
        log.flush(T0.plus(ActivityLog.FIRST_RETRY));
        assertEquals(0, log.pendingCount());
        assertEquals(range(1, 5), diskSequences(tmp, id), "cada linha uma vez, inteira, na ordem");
        ActivityLog reopened = ActivityLog.read(tmp, id);
        assertFalse(reopened.sequenceGap());
        assertEquals(0, reopened.unreadable());
        assertFalse(reopened.storeFailed());
    }

    /** Escritor que registra o tamanho de cada chamada (0 = só sincronização) e delega ou falha. */
    private static ActivityLog.Writer recording(List<Integer> sizes, boolean fail) {
        return (f, data) -> {
            sizes.add(data.length);
            if (fail) throw new IOException("fsync");
            ActivityLog.DISK.append(f, data);
        };
    }

    @Test
    void visibleBytesAreNotDurableUntilASyncIsConfirmedAndAreNeverRewritten() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.writer((f, data) -> { // a escrita entra no arquivo, a sincronização falha
            Files.write(f, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            throw new IOException("fsync");
        });
        log.append(fws(id, 1, 4), T0);
        assertEquals(range(1, 4), diskSequences(tmp, id), "bytes visíveis no arquivo");
        assertEquals(4, log.pendingCount(), "mas a gravação não está confirmada: continua pendente");

        List<Integer> sizes = new ArrayList<>();
        log.writer(recording(sizes, true)); // a sincronização continua falhando
        log.flush(T0.plus(ActivityLog.FIRST_RETRY));
        assertEquals(List.of(0), sizes, "só sincroniza: nada é regravado");
        assertEquals(4, log.pendingCount(), "enquanto a sincronização falhar, o aviso continua");

        log.writer(recording(sizes, false));
        log.flush(T0.plus(ActivityLog.MAX_RETRY));
        assertEquals(List.of(0, 0), sizes, "a recuperação termina com uma sincronização confirmada, sem regravar");
        assertEquals(0, log.pendingCount());
        assertFalse(log.storeFailed());
        assertEquals(range(1, 4), diskSequences(tmp, id), "cada linha uma vez");
        log.append(fws(id, 5, 1), T0.plus(ActivityLog.MAX_RETRY));
        assertEquals(range(1, 5), diskSequences(tmp, id));
    }

    @Test
    void linesThatReachedTheFileAreNotCountedAsLostWhenSyncKeepsFailing() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.writer((f, data) -> {
            Files.write(f, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            throw new IOException("fsync");
        });
        log.append(fws(id, 1, 3), T0);
        Instant t = T0;
        for (int i = 0; i < ActivityLog.MAX_ATTEMPTS + 3; i++) {
            t = t.plus(ActivityLog.MAX_RETRY);
            log.flush(t);
        }
        assertEquals(0, log.lost(), "as linhas estão no arquivo: não são perdidas");
        assertFalse(log.storeFailed());
        assertEquals(0, log.unwrittenGapNotices(), "sem perda, sem aviso de perda");
        assertEquals(3, log.pendingCount(), "continuam pendentes até sincronizar");
        assertEquals(range(1, 3), diskSequences(tmp, id), "nada regravado em nenhuma tentativa");
        log.writer(ActivityLog.DISK);
        log.flush(t.plus(ActivityLog.MAX_RETRY));
        assertEquals(0, log.pendingCount());
        assertEquals(range(1, 3), diskSequences(tmp, id));
    }

    @Test
    void severalFailureCyclesKeepOneNoticeWithTheAccumulatedLossThroughRecoveryAndReopening() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.append(fws(id, 1, 2), T0);
        log.writer(FAILING);
        log.append(fws(id, 3, 3), T0);
        Instant t = T0;
        for (int round = 0; round < 6 * (ActivityLog.MAX_ATTEMPTS + 1); round++) {
            t = t.plus(ActivityLog.MAX_RETRY);
            if (round == 20) log.append(fws(id, 6, 2), t); // mais eventos durante a indisponibilidade
            log.flush(t);
            assertTrue(log.unwrittenGapNotices() <= 1, "no máximo um aviso não gravado");
            assertTrue(log.all().stream().filter(e -> "EVENT_GAP".equals(e.code())).count() <= 1, "a memória não acumula avisos");
        }
        assertTrue(log.writeFailures() >= 4L * ActivityLog.MAX_ATTEMPTS, "vários ciclos completos de falha: " + log.writeFailures());
        assertEquals(5, log.lost(), "3 + 2 perdidos");
        ActivityEvent notice = log.all().stream().filter(e -> "EVENT_GAP".equals(e.code())).findFirst().orElseThrow();
        assertEquals(5L, notice.counts().get("lost"), "o aviso cobre a perda ACUMULADA, não só o último ciclo");

        log.writer(ActivityLog.DISK);
        log.flush(t.plus(ActivityLog.MAX_RETRY));
        assertEquals(0, log.pendingCount());
        ActivityLog reopened = ActivityLog.read(tmp, id);
        List<ActivityEvent> gaps = reopened.all().stream().filter(e -> "EVENT_GAP".equals(e.code())).toList();
        assertEquals(1, gaps.size(), "um único aviso no disco");
        assertEquals(5L, gaps.get(0).counts().get("lost"));
        assertEquals("AGT-OBS-009", gaps.get(0).errorCode());
        assertTrue(reopened.storeFailed());
        assertTrue(reopened.sequenceGap());
        assertEquals(List.of(1L, 2L), reopened.all().stream().map(ActivityEvent::sequence).toList().subList(0, 2));

        // uma nova perda depois do aviso gravado gera um aviso só dela
        log.writer(FAILING);
        log.append(fws(id, 8, 1), t.plus(ActivityLog.MAX_RETRY));
        Instant u = t.plus(ActivityLog.MAX_RETRY);
        for (int i = 0; i < ActivityLog.MAX_ATTEMPTS + 1; i++) {
            u = u.plus(ActivityLog.MAX_RETRY);
            log.flush(u);
        }
        log.writer(ActivityLog.DISK);
        log.flush(u.plus(ActivityLog.MAX_RETRY));
        List<Long> lostCounts = ActivityLog.read(tmp, id).all().stream().filter(e -> "EVENT_GAP".equals(e.code())).map(e -> e.counts().get("lost")).toList();
        assertEquals(List.of(5L, 1L), lostCounts);
    }

    @Test
    void tornLineLeftByAFailedWriteIsSeparatedAndOnlyWhatIsMissingIsLost() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.append(fws(id, 1, 1), T0);
        boolean[] first = {true};
        log.writer((f, data) -> { // na primeira tentativa grava 1,5 linha; depois nada passa
            if (first[0]) {
                first[0] = false;
                int one = 0;
                while (data[one] != '\n') one++;
                Files.write(f, Arrays.copyOf(data, one + 1 + 20), StandardOpenOption.APPEND);
            }
            throw new IOException("bloqueado");
        });
        log.append(fws(id, 2, 3), T0);
        Instant t = T0;
        for (int i = 0; i < ActivityLog.MAX_ATTEMPTS + 1 && log.lost() == 0; i++) {
            t = t.plus(ActivityLog.MAX_RETRY);
            log.flush(t);
        }
        assertEquals(2, log.lost(), "a linha inteira no arquivo (seq 2) não é perdida; a pela metade (3) e a 4 são");
        log.writer(ActivityLog.DISK);
        log.flush(t.plus(ActivityLog.MAX_RETRY));
        assertEquals(0, log.pendingCount());
        ActivityLog reopened = ActivityLog.read(tmp, id);
        assertEquals(List.of(1L, 2L, 5L), reopened.all().stream().map(ActivityEvent::sequence).toList());
        assertEquals(1, reopened.unreadable(), "o resto da linha 3 virou uma linha ilegível, separada");
        assertEquals(2L, reopened.all().get(2).counts().get("lost"));
    }

    @Test
    void fileChangedByAnotherProgramIsNeverPatchedToLookContinuous() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.append(fws(id, 1, 2), T0);
        log.writer(FAILING);
        log.append(fws(id, 3, 2), T0);
        Files.writeString(tmp.resolve(ActivityLog.FILE), "{\"lixo\":", StandardCharsets.UTF_8, StandardOpenOption.APPEND); // outro escreveu no fim
        log.writer(ActivityLog.DISK);
        log.flush(T0.plus(ActivityLog.FIRST_RETRY));
        assertEquals(2, log.lost(), "o fim não confere: as pendentes não são coladas no que outro escreveu");
        assertOrdered(log);
        assertTrue(log.storeFailed());
        log.flush(T0.plus(ActivityLog.MAX_RETRY));
        assertEquals(0, log.pendingCount());
        ActivityLog reopened = ActivityLog.read(tmp, id);
        assertEquals(1, reopened.unreadable(), "o resto alheio virou uma linha ilegível, separada");
        assertTrue(reopened.sequenceGap());
        assertTrue(reopened.storeFailed());
        assertEquals(List.of(1L, 2L, 5L), reopened.all().stream().map(ActivityEvent::sequence).toList());
    }

    @Test
    void crashWithPendingWritesReopensFromWhatIsOnDisk() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.append(List.of(fw(id, 100), fw(id, 200)), T0);
        log.writer(FAILING);
        log.append(List.of(fw(id, 300), fw(id, 400)), T0);
        assertEquals(2, log.pendingCount());
        // queda: o objeto some sem gravar
        ActivityLog reopened = ActivityLog.open(tmp, id);
        assertEquals(2, reopened.size());
        assertEquals(200, reopened.position(Source.FRAMEWORK), "a coleta volta a ler a origem depois do que está no disco");
        assertFalse(reopened.sequenceGap());
        assertEquals(3, reopened.append(List.of(fw(id, 300)), T0).get(0).sequence(), "a sequência continua sem buraco nem repetição");
        assertEquals(range(1, 3), diskSequences(tmp, id));
    }
}
