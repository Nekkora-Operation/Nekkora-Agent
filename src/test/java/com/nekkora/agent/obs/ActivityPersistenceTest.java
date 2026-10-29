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

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void reopeningWhileAnotherProgramDeniesReadingDefersTheCollectionNeverRestartingTheSequenceOrRereadingTheSources() throws Exception {
        ObsFixtures.Run run = ObsFixtures.running(store, saida, ExecutionPackage.Kind.STUDIO_PYTHON, clock.instant());
        List<String> fwLines = ActivityServiceTest.rebase(ObsFixtures.g1Events(), clock.instant());
        run.frameworkLines(fwLines.subList(0, 30));
        ActivityService first = new ActivityService(store, clock, Duration.ofMillis(50), ActivityService.MAX_TOTAL_BYTES);
        first.track(run.id());
        first.tick();
        assertEquals(0, first.view(run.id(), 0).orElseThrow().health().pendingWrites());
        // queda do Agent com tudo gravado; a automação segue
        Path dir = store.dir(run.id());
        List<Long> before = diskSequences(dir, run.id().value());
        run.frameworkLines(fwLines.subList(30, 70));
        ActivityService second = new ActivityService(store, clock, Duration.ofMillis(50), ActivityService.MAX_TOTAL_BYTES);
        second.catchUp();
        try (FileChannel other = FileChannel.open(dir.resolve(ActivityLog.FILE), StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ,
                ExtendedOpenOption.NOSHARE_WRITE)) {
            for (int i = 0; i < 4; i++) {
                clock.plusMillis(1000);
                second.tick();
            }
        }
        assertEquals(before, diskSequences(dir, run.id().value()), "com a leitura negada nada é gravado (nem COLLECTION_STARTED de novo)");
        run.frameworkLines(fwLines.subList(70, fwLines.size()));
        run.finish(ExecutionState.SUCCEEDED, clock.instant());
        for (int i = 0; i < 6; i++) {
            clock.plusMillis(1000);
            second.tick();
        }
        List<Long> seqs = diskSequences(dir, run.id().value());
        assertEquals(range(1, seqs.size()), seqs, "sequência contínua, sem recomeçar em 1");
        ActivityLog onDisk = ActivityLog.read(dir, run.id().value());
        assertEquals(fwLines.size(), onDisk.all().stream().filter(e -> e.source() == Source.FRAMEWORK).count(),
                "cada evento do Framework uma vez: nada relido do começo das origens");
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

    /** Saúde como a coleta a entrega à tela (só os campos de gravação variam aqui). */
    private static ActivityView.Health health(ActivityLog log, boolean completed) {
        return new ActivityView.Health(!completed, completed, false, ActivityView.FrameworkState.NOT_APPLICABLE, log.dropped(), 0, 0, 0, 0, log.capped(),
                log.sequenceGap(), log.unreadable(), false, log.storeFailed(), log.pendingCount(), 0, null, false);
    }

    @Test
    void longOutageWithAFullQueueKeepsOneAccumulatedNoticeBoundedMemoryAndAnIncompleteTimeline() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.append(fws(id, 1, 2), T0);
        log.writer(FAILING);
        Instant t = T0;
        long fed = 0;
        long pos = 3;
        int maxMemory = 0;
        // indisponibilidade longa: muitos ciclos completos de 12 falhas, com eventos chegando e a fila enchendo
        for (int round = 0; round < 10 * (ActivityLog.MAX_ATTEMPTS + 1); round++) {
            t = t.plus(ActivityLog.MAX_RETRY);
            if (round % 7 == 0) {
                int n = round == 14 ? ActivityLog.MAX_PENDING + 50 : 3; // numa rodada, mais do que a fila comporta
                log.append(fws(id, pos, n), t);
                pos += n;
                fed += n;
            } else {
                log.flush(t);
            }
            assertTrue(log.pendingCount() <= ActivityLog.MAX_PENDING + 1, "fila limitada");
            assertTrue(log.unwrittenGapNotices() <= 1, "no máximo um aviso não gravado");
            assertTrue(log.all().stream().filter(e -> "EVENT_GAP".equals(e.code())).count() <= 1, "a memória não acumula avisos");
            maxMemory = Math.max(maxMemory, log.size());
            // a tela durante a falha: nunca completa
            ActivityView.Completeness c = ActivityView.completeness(health(log, true), log.all());
            assertEquals(Integrity.INCOMPLETE, c.integrity());
            assertTrue(c.gaps().contains(Gap.STORE_PENDING) || c.gaps().contains(Gap.STORE_FAILED), c.gaps().toString());
        }
        for (int i = 0; i <= ActivityLog.MAX_ATTEMPTS; i++) { // mais um ciclo completo sem eventos novos: o último lote também cai
            t = t.plus(ActivityLog.MAX_RETRY);
            log.flush(t);
        }
        assertEquals(1, log.pendingCount(), "só o aviso único espera o disco");
        assertTrue(log.writeFailures() >= 8L * ActivityLog.MAX_ATTEMPTS, "vários ciclos completos: " + log.writeFailures());
        assertTrue(maxMemory <= 2 + fed + 1, "memória = eventos aceitos + no máximo 1 aviso: " + maxMemory);
        assertEquals(fed, log.lost(), "todos os aceitos durante a indisponibilidade foram perdidos e contados, nem mais nem menos");
        assertTrue(log.storeFailed());
        ActivityEvent notice = log.all().stream().filter(e -> "EVENT_GAP".equals(e.code())).findFirst().orElseThrow();
        assertEquals(fed, notice.counts().get("lost"), "o aviso único cobre a perda ACUMULADA");

        log.writer(ActivityLog.DISK); // o disco volta
        log.flush(t.plus(ActivityLog.MAX_RETRY));
        assertEquals(0, log.pendingCount());
        ActivityLog reopened = ActivityLog.read(tmp, id);
        List<ActivityEvent> onDisk = reopened.all();
        assertEquals(3, onDisk.size(), "1, 2 e o aviso");
        assertEquals("AGT-OBS-009", onDisk.get(2).errorCode());
        assertEquals(fed, onDisk.get(2).counts().get("lost"));
        assertTrue(onDisk.get(2).sequence() > 2, "sequência monotônica, lacuna à vista");
        assertTrue(reopened.sequenceGap());
        assertTrue(reopened.storeFailed());
        ActivityView.Completeness after = ActivityView.completeness(health(reopened, true), onDisk);
        assertEquals(Integrity.INCOMPLETE, after.integrity());
        assertTrue(after.gaps().containsAll(List.of(Gap.STORE_FAILED, Gap.SEQUENCE_GAP)), after.gaps().toString());
    }

    @Test
    void partialWriteThenFailedSyncIsCompletedThenSyncedOnceWithoutRewriting() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.append(fws(id, 1, 1), T0);
        Path file = tmp.resolve(ActivityLog.FILE);
        long base = Files.size(file);
        log.writer((f, data) -> { // 1ª tentativa: só parte entra
            Files.write(f, Arrays.copyOf(data, data.length / 3), StandardOpenOption.APPEND);
            throw new IOException("escrita interrompida");
        });
        log.append(fws(id, 2, 3), T0);
        long partial = Files.size(file) - base;
        List<Integer> sizes = new ArrayList<>();
        log.writer((f, data) -> { // 2ª: o restante entra, a sincronização falha
            sizes.add(data.length);
            Files.write(f, data, StandardOpenOption.APPEND);
            throw new IOException("fsync");
        });
        log.flush(T0.plus(ActivityLog.FIRST_RETRY));
        assertEquals(range(1, 4), diskSequences(tmp, id), "conteúdo completo visível");
        assertEquals(3, log.pendingCount(), "visível, mas sem sincronização confirmada: pendente");
        ActivityView.Completeness c = ActivityView.completeness(health(log, true), log.all());
        assertEquals(Integrity.INCOMPLETE, c.integrity());
        assertTrue(c.gaps().contains(Gap.STORE_PENDING));
        long full = Files.size(file) - base;
        assertEquals(List.of((int) (full - partial)), sizes, "só o que faltava foi escrito (a parte conciliada não)");
        log.writer(recording(sizes, true)); // 3ª: sincronização falha de novo
        log.flush(T0.plus(ActivityLog.MAX_RETRY));
        assertEquals(3, log.pendingCount());
        log.writer(recording(sizes, false)); // 4ª: sincroniza
        log.flush(T0.plus(ActivityLog.MAX_RETRY).plus(ActivityLog.MAX_RETRY));
        assertEquals(List.of((int) (full - partial), 0, 0), sizes, "depois de tudo visível, só sincronização: nada regravado");
        assertEquals(0, log.pendingCount());
        assertEquals(range(1, 4), diskSequences(tmp, id));
        ActivityLog reopened = ActivityLog.read(tmp, id);
        assertFalse(reopened.sequenceGap());
        assertEquals(0, reopened.unreadable());
        assertEquals(Integrity.COMPLETE, ActivityView.completeness(health(reopened, true), reopened.all()).integrity());
    }

    // ------------------------------------------------------------------- B-OBS-03: fila cheia com fsync que só falha

    /** Escritor em que todo byte chega ao arquivo e a sincronização falha sempre (conta as chamadas). */
    private static ActivityLog.Writer visibleButNeverSynced(int[] calls) {
        return (f, data) -> {
            calls[0]++;
            Files.write(f, data, StandardOpenOption.APPEND);
            throw new IOException("fsync");
        };
    }

    @Test
    void fullQueueOfVisibleLinesWithFsyncAlwaysFailingStaysWithinTheLimitsLosesNothingAndConfirmsOnlyAfterRecovery() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.append(fws(id, 1, 2), T0); // sincronizados
        int[] calls = {0};
        log.writer(visibleButNeverSynced(calls));
        Instant t = T0;
        long pos = 3;
        long accepted = 2;
        int round = 0;
        // fase 1: cada lote chega ao arquivo antes do próximo, então a fila enche só de linhas visíveis (o cenário do
        // parecer); fase 2: chegada mais rápida que o intervalo entre tentativas, a fila enche com linhas ainda não escritas
        for (int phase = 1; phase <= 2; phase++) {
            int total = 2 * ActivityLog.MAX_PENDING + 3 * 64; // a fila enche duas vezes em cada fase
            int batch = phase == 1 ? 64 : 97;
            Duration step = phase == 1 ? ActivityLog.MAX_RETRY : ActivityLog.FIRST_RETRY;
            for (int fed = 0; fed < total; ) {
                t = t.plus(step);
                int n = Math.min(batch, total - fed);
                List<ActivityEvent> got = log.append(fws(id, pos, n), t); // a coleta nunca recebe exceção
                assertEquals(n, got.size(), "a falha de gravação não recusa evento");
                pos += n;
                fed += n;
                accepted += n;
                String at = " (fase " + phase + ", " + accepted + " aceitos)";
                assertTrue(log.queuedLines() <= ActivityLog.MAX_PENDING, "linhas na memória: " + log.queuedLines() + at);
                assertTrue(log.queuedBytes() <= ActivityLog.MAX_PENDING_BYTES + ActivityCodec.MAX_LINE, "bytes na memória: " + log.queuedBytes() + at);
                assertEquals(accepted, log.size(), "memória = eventos aceitos: sem cópia e sem aviso" + at);
                assertEquals(accepted - 2, log.pendingCount(), "nenhuma confirmação indevida: tudo desde a falha aguarda sincronização" + at);
                assertEquals(0, log.lost(), "nada perdido: a escrita funciona, só a sincronização falha" + at);
                assertFalse(log.storeFailed());
                assertEquals(0, log.unwrittenGapNotices());
                ActivityView.Completeness c = ActivityView.completeness(health(log, true), log.all());
                assertEquals(Integrity.INCOMPLETE, c.integrity());
                assertTrue(c.gaps().contains(Gap.STORE_PENDING) && !c.gaps().contains(Gap.STORE_FAILED), c.gaps().toString());
                if (++round % 16 == 0) {
                    List<Long> disk = diskSequences(tmp, id);
                    assertEquals(range(1, disk.size()), disk, "no arquivo, cada linha uma vez e em ordem" + at);
                    assertTrue(disk.size() <= accepted);
                }
            }
        }
        t = t.plus(ActivityLog.MAX_RETRY);
        log.flush(t); // o que faltava chega ao arquivo; a sincronização falha de novo
        assertEquals(accepted - 2, log.pendingCount());
        assertTrue(calls[0] >= 3, "a gravação foi tentada (e a sincronização falhou) várias vezes: " + calls[0]);
        assertEquals(range(1, accepted), diskSequences(tmp, id), "nada perdido, nada repetido");

        List<Integer> sizes = new ArrayList<>();
        log.writer(recording(sizes, false)); // a sincronização volta
        t = t.plus(ActivityLog.MAX_RETRY);
        assertTrue(log.flush(t));
        assertEquals(List.of(0), sizes, "tudo já estava no arquivo: a recuperação só sincroniza, nada é regravado");
        assertEquals(0, log.pendingCount());
        assertEquals(0, log.queuedLines());
        assertEquals(0, log.queuedBytes());
        log.append(fws(id, pos, 1), t);
        assertEquals(range(1, accepted + 1), diskSequences(tmp, id));
        ActivityLog reopened = ActivityLog.read(tmp, id);
        assertEquals(range(1, accepted + 1), reopened.all().stream().map(ActivityEvent::sequence).toList());
        assertFalse(reopened.sequenceGap());
        assertFalse(reopened.storeFailed());
        assertEquals(0, reopened.unreadable());
        assertEquals(Integrity.COMPLETE, ActivityView.completeness(health(reopened, true), reopened.all()).integrity());
    }

    /** Evento grande (passo longo e 16 contagens): a fila enche pelos bytes antes das linhas. */
    private static ActivityEvent big(String id, long pos) {
        ActivityEvent.Builder b = ActivityEvent.of(id, T0, Source.FRAMEWORK, "ACTION_COMPLETED", Level.INFO, Category.ACTIVITY)
                .step("a".repeat(60) + "." + "b".repeat(59)).position(pos);
        for (int i = 0; i < ActivityEvent.MAX_COUNTS; i++) b.count(String.valueOf((char) ('a' + i)).repeat(40), 999_999_999_999L);
        return b.build();
    }

    @Test
    void queueFullByBytesWithFsyncAlwaysFailingStaysWithinTheByteLimitAndLosesNothing() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.append(fws(id, 1, 1), T0);
        log.writer(visibleButNeverSynced(new int[1]));
        int line = (ActivityCodec.encode(big(id, 2).withSequence(2)) + "\n").getBytes(StandardCharsets.UTF_8).length;
        int total = (int) (2 * ActivityLog.MAX_PENDING_BYTES / line) + 100; // os bytes enchem a fila duas vezes
        assertTrue(total < ActivityLog.MAX_PENDING, "é o limite de bytes que enche, não o de linhas");
        Instant t = T0;
        for (int i = 0; i < total; i += 50) {
            t = t.plus(ActivityLog.FIRST_RETRY);
            List<ActivityEvent> batch = new ArrayList<>();
            for (int k = i; k < Math.min(total, i + 50); k++) batch.add(big(id, 2L + k));
            log.append(batch, t);
            assertTrue(log.queuedBytes() <= ActivityLog.MAX_PENDING_BYTES + line, "bytes na memória: " + log.queuedBytes());
            assertEquals(0, log.lost());
        }
        assertEquals(total, log.pendingCount(), "todas aguardando sincronização, nenhuma confirmada");
        log.writer(ActivityLog.DISK);
        assertTrue(log.flush(t.plus(ActivityLog.MAX_RETRY)));
        assertEquals(range(1, 1L + total), diskSequences(tmp, id));
        assertEquals(0, log.queuedBytes());
    }

    /** Quebras de linha do arquivo a partir do byte {@code from}. */
    private static int newlinesFrom(Path file, long from) throws IOException {
        byte[] b = Files.readAllBytes(file);
        int n = 0;
        for (int i = (int) from; i < b.length; i++) if (b[i] == '\n') n++;
        return n;
    }

    /** Início da primeira linha a partir do byte {@code from}. */
    private static long lineStartFrom(Path file, long from) throws IOException {
        byte[] b = Files.readAllBytes(file);
        int i = (int) from;
        while (b[i - 1] != '\n') i++;
        return i;
    }

    @Test
    void linesReleasedFromMemoryWhileAwaitingSyncAreLostOnlyIfTheFileProvesIt() throws Exception {
        String id = ExecutionId.random().value();
        ActivityLog log = ActivityLog.open(tmp, id);
        log.append(fws(id, 1, 2), T0);
        Path file = tmp.resolve(ActivityLog.FILE);
        long synced = Files.size(file);
        log.writer(visibleButNeverSynced(new int[1]));
        int n = ActivityLog.MAX_PENDING + 500;
        log.append(fws(id, 3, n), T0); // enche a fila: as visíveis saem da memória, continuam aguardando sincronização
        assertTrue(log.queuedLines() <= ActivityLog.MAX_PENDING);
        log.flush(T0.plus(ActivityLog.FIRST_RETRY)); // o resto também chega ao arquivo, sem sincronizar
        assertEquals(range(1, 2L + n), diskSequences(tmp, id));
        assertEquals(n, log.pendingCount());

        // outro programa acrescenta no fim: nada do que é deste histórico sumiu, nada é perdido
        Files.writeString(file, "{\"lixo\":", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        log.flush(T0.plus(ActivityLog.MAX_RETRY));
        assertEquals(0, log.lost(), "o arquivo ainda tem todas as linhas: nenhuma perda sem prova");
        assertEquals(n, log.pendingCount());

        // outro programa corta o arquivo no meio das linhas não sincronizadas: só as que sumiram são perdidas
        long cut = lineStartFrom(file, synced + (Files.size(file) - synced) / 2) + 7; // no meio de uma linha
        int gone = newlinesFrom(file, cut); // a cortada ao meio e as seguintes (o resto alheio não tem quebra)
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(cut);
        }
        log.writer(ActivityLog.DISK);
        log.flush(T0.plus(ActivityLog.MAX_RETRY).plus(ActivityLog.MAX_RETRY));
        assertEquals(gone, log.lost(), "perdidas = exatamente as linhas que o arquivo não tem mais");
        assertTrue(log.storeFailed());
        log.flush(T0.plus(Duration.ofMinutes(1)));
        assertEquals(0, log.pendingCount());
        ActivityLog reopened = ActivityLog.read(tmp, id);
        List<ActivityEvent> onDisk = reopened.all();
        ActivityEvent notice = onDisk.get(onDisk.size() - 1);
        assertEquals("AGT-OBS-009", notice.errorCode());
        assertEquals((long) gone, notice.counts().get("lost"));
        assertEquals(2 + n - gone + 1, onDisk.size(), "as sobreviventes + o aviso, cada uma uma vez");
        assertEquals(range(1, 2L + n - gone), onDisk.subList(0, onDisk.size() - 1).stream().map(ActivityEvent::sequence).toList());
        assertEquals(1, reopened.unreadable(), "a linha cortada ao meio virou uma linha ilegível, separada");
        assertTrue(reopened.sequenceGap());
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
