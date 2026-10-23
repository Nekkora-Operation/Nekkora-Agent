package com.nekkora.agent.obs;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.obs.ActivityEvent.Category;
import com.nekkora.agent.obs.ActivityEvent.Level;
import com.nekkora.agent.obs.ActivityEvent.Source;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Histórico de atividade de UMA execução: {@code <dados>/execucoes/<id>/atividade.jsonl}, envelopes já saneados, um
 * por linha, gravados só por acréscimo (com {@code fsync} por lote). A sequência é atribuída aqui, na ordem de gravação,
 * e é estável depois de um reinício: ao abrir, o arquivo é relido, a última linha incompleta (queda no meio da escrita)
 * é cortada e a próxima sequência e a posição já consumida de cada origem são recuperadas DO PRÓPRIO ARQUIVO (cada
 * evento carrega a posição da sua origem) — nada é gravado duas vezes e nada é relido do zero.
 *
 * <p>Limites explícitos por execução: {@link #MAX_EVENTS} eventos e {@link #MAX_BYTES} bytes. Ao atingir o limite, um
 * único {@code EVENT_GAP} ({@code AGT-OBS-005}) é gravado e os eventos seguintes são DESCARTADOS e contados (o total
 * entra no {@code COLLECTION_COMPLETED}). Há uma reserva de {@link #RESERVE} linhas só para esses avisos do coletor.
 *
 * <p>Gravação que falha (outro programa abriu o arquivo sem compartilhar a escrita, disco momentaneamente
 * indisponível): as linhas ficam PENDENTES, em ordem e com a sequência já atribuída, numa fila limitada
 * ({@link #MAX_PENDING} linhas, {@link #MAX_PENDING_BYTES} bytes) e são gravadas de novo, uma só vez, com intervalo
 * crescente ({@link #FIRST_RETRY} até {@link #MAX_RETRY}) e no máximo {@link #MAX_ATTEMPTS} tentativas — sempre na
 * linha da coleta, nunca na interface, e sem tocar na automação. Antes de cada nova tentativa o fim do arquivo é
 * CONFERIDO contra o que estava pendente (uma tentativa anterior pode ter gravado tudo ou parte antes de falhar): o
 * que já está lá não é regravado e uma linha gravada pela metade é completada. Bytes visíveis no arquivo NÃO bastam:
 * uma pendente só sai da fila depois de uma sincronização ({@code fsync}) confirmada — se só ela falhou, a próxima
 * tentativa sincroniza sem regravar.
 *
 * <p>Se o fim não confere (arquivo alterado por outro), se as tentativas se esgotam ou se a fila enche, as pendentes que
 * não estão no arquivo são DESCARTADAS e contadas e UM {@code EVENT_GAP} ({@code AGT-OBS-009}) com a perda ACUMULADA
 * ainda não registrada no disco entra na fila; num novo ciclo de falha ele é SUBSTITUÍDO (nunca acumulado), então há no
 * máximo um aviso não gravado na memória. Depois de gravado, a lacuna de sequência e o aviso ficam no disco e a linha do
 * tempo continua incompleta inclusive depois de um reinício. Se o Agent terminar ANTES de gravá-lo, nada posterior à
 * perda está no disco (a fila é ordenada): na reabertura os eventos das origens são relidos das posições gravadas
 * (enquanto as origens existirem); os avisos do próprio coletor desse intervalo não são recuperáveis. Nada é inventado
 * para parecer contínuo.
 */
final class ActivityLog {
    static final String FILE = "atividade.jsonl";
    static final int MAX_EVENTS = 20_000;
    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final int RESERVE = 64;
    /** Maior arquivo que a leitura do histórico aceita (limite + reserva com folga). */
    static final long MAX_READ = MAX_BYTES + RESERVE * (long) ActivityCodec.MAX_LINE;
    static final int MAX_PENDING = 4096;
    static final long MAX_PENDING_BYTES = 2L * 1024 * 1024;
    static final int MAX_ATTEMPTS = 12;
    static final Duration FIRST_RETRY = Duration.ofMillis(200);
    static final Duration MAX_RETRY = Duration.ofSeconds(5);
    static final String STORE_STATUS = "ACTIVITY_STORE";

    /**
     * Acréscimo seguido de sincronização ({@code fsync}); com {@code data} vazio, só sincroniza (linhas já visíveis no
     * arquivo, cuja sincronização não foi confirmada). Os testes simulam escrita parcial e sincronização que falha.
     */
    interface Writer {
        void append(Path file, byte[] data) throws IOException;
    }

    static final Writer DISK = (f, data) -> {
        try (FileOutputStream fo = new FileOutputStream(f.toFile(), true)) {
            if (data.length > 0) fo.write(data);
            fo.getFD().sync();
        }
    };

    /**
     * Linha à espera de gravação. {@code sequence} 0 = só completa um resto sem quebra de linha no fim do arquivo;
     * {@code reports} ≥ 0 = é o aviso de perda e cobre essa quantidade de eventos perdidos (-1 nos demais).
     */
    private record Pending(long sequence, byte[] line, long reports) {
        boolean gap() {
            return reports >= 0;
        }
    }

    /** O fim do arquivo não é o que este histórico gravou: as pendentes não podem ser completadas com segurança. */
    private static final class Mismatch extends Exception {
        private static final long serialVersionUID = 1L;

        Mismatch() {
            super(null, null, false, false);
        }
    }

    private final Path file;
    private final String id;
    private final List<ActivityEvent> events = new ArrayList<>();
    private final Map<Source, Long> positions = new EnumMap<>(Source.class);
    private long nextSequence = 1;
    private long bytes;
    private boolean capped;
    private long dropped;
    private int reserveUsed;
    private boolean sequenceGap;
    private int unreadable;
    private boolean storeFailed;
    private boolean tooLarge;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private long pendingBytes;
    private long diskBytes;
    private int attempts;
    private Instant retryAt;
    private long lost;
    /** Perdas ainda não cobertas por um aviso sincronizado no disco. */
    private long unreported;
    private long writeFailures;
    private Writer writer = DISK;

    private ActivityLog(Path file, String id) {
        this.file = file;
        this.id = id;
    }

    /** Abre para gravar (corta uma última linha incompleta). */
    static ActivityLog open(Path runDir, String id) {
        ActivityLog l = new ActivityLog(runDir.resolve(FILE), id);
        l.load(true);
        return l;
    }

    /** Lê para consulta, sem alterar nada no disco. */
    static ActivityLog read(Path runDir, String id) {
        ActivityLog l = new ActivityLog(runDir.resolve(FILE), id);
        l.load(false);
        return l;
    }

    private void load(boolean repair) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) return;
            long size = Files.size(file);
            if (size > MAX_READ) {
                tooLarge = true; // não é deste Agent (ou foi alterado): não é carregado nem ampliado
                capped = true;
                return;
            }
            byte[] data = Files.readAllBytes(file);
            int lastNewline = -1;
            for (int i = data.length - 1; i >= 0; i--) {
                if (data[i] == '\n') {
                    lastNewline = i;
                    break;
                }
            }
            if (lastNewline < data.length - 1) {
                unreadable++; // linha final sem quebra: escrita interrompida
                if (repair) {
                    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                        ch.truncate(lastNewline + 1L);
                    }
                }
            }
            bytes = lastNewline + 1L;
            String text = new String(data, 0, lastNewline + 1, StandardCharsets.UTF_8);
            long prev = 0;
            for (String line : text.split("\n")) {
                if (line.isEmpty()) continue;
                var e = ActivityCodec.decode(line).filter(x -> x.agentExecutionId().equals(id));
                if (e.isEmpty()) {
                    unreadable++;
                    continue;
                }
                ActivityEvent ev = e.get();
                if (ev.sequence() != prev + 1) sequenceGap = true;
                prev = Math.max(prev, ev.sequence());
                events.add(ev);
                if (ev.sourcePosition() != null) positions.merge(origin(ev), ev.sourcePosition(), Math::max);
                if ("EVENT_GAP".equals(ev.code()) && ErrorCode.OBS_RETENTION_LIMIT.code().equals(ev.errorCode())) capped = true;
                if ("EVENT_GAP".equals(ev.code()) && ErrorCode.OBS_STORE_FAILED.code().equals(ev.errorCode())) storeFailed = true; // perda antes de um reinício
                if (ev.source() == Source.COLLECTOR) reserveUsed++;
            }
            nextSequence = prev + 1;
            diskBytes = bytes;
        } catch (IOException | RuntimeException e) {
            storeFailed = true;
        }
    }

    /**
     * Grava um lote em ordem e devolve o que foi aceito (com a sequência). Acima do limite os eventos das origens são
     * descartados e contados; os do coletor usam a reserva. O aceito entra na memória (a tela segue) mesmo que a
     * gravação fique pendente; um lote vazio só tenta gravar as pendentes.
     */
    synchronized List<ActivityEvent> append(List<ActivityEvent> batch, Instant now) {
        if (tooLarge) {
            dropped += batch.size();
            return List.of();
        }
        List<ActivityEvent> accepted = new ArrayList<>();
        for (ActivityEvent e : batch) {
            boolean collector = e.source() == Source.COLLECTOR;
            boolean full = events.size() >= MAX_EVENTS || bytes >= MAX_BYTES;
            if (!collector && (capped || full)) {
                dropped++;
                if (!capped) {
                    capped = true;
                    add(ActivityEvent.of(id, now, Source.COLLECTOR, "EVENT_GAP", Level.WARNING, Category.TELEMETRY)
                            .error(ErrorCode.OBS_RETENTION_LIMIT.code()).status(STORE_STATUS).count("max_events", MAX_EVENTS).build(), accepted, now);
                }
                continue;
            }
            // o fechamento da coleta sempre cabe: sem ele a execução seria recoletada a cada abertura do Agent
            boolean closing = "COLLECTION_COMPLETED".equals(e.code()) || "HISTORY_PURGED".equals(e.code());
            if (collector && reserveUsed >= RESERVE && !closing) {
                dropped++;
                continue;
            }
            if (collector) reserveUsed++;
            add(e, accepted, now);
        }
        flush(now, false);
        return accepted;
    }

    /** Tenta gravar as pendentes (respeitando o intervalo entre tentativas). Devolve se nada ficou pendente. */
    synchronized boolean flush(Instant now) {
        return flush(now, false);
    }

    /** Última tentativa ao fechar o Agent: ignora o intervalo (uma só tentativa). */
    synchronized boolean flushNow(Instant now) {
        return flush(now, true);
    }

    private boolean flush(Instant now, boolean force) {
        if (pending.isEmpty()) return true;
        if (!force && retryAt != null && now.isBefore(retryAt)) return false;
        try {
            long done = reconcile();
            byte[] data = new byte[Math.toIntExact(pendingBytes - done)];
            int off = 0;
            long skip = done;
            for (Pending p : pending) {
                int from = (int) Math.min(skip, p.line().length);
                skip -= from;
                System.arraycopy(p.line(), from, data, off, p.line().length - from);
                off += p.line().length - from;
            }
            writer.append(file, data); // o resto (se houver) e a sincronização de TUDO o que estava pendente
            diskBytes += pendingBytes;
            for (Pending p : pending) {
                if (p.gap()) unreported -= p.reports();
            }
            pending.clear();
            pendingBytes = 0;
            attempts = 0;
            retryAt = null;
            return true;
        } catch (Mismatch m) {
            abandon(now);
        } catch (IOException | RuntimeException ex) {
            writeFailures++;
            attempts++;
            if (attempts >= MAX_ATTEMPTS) abandon(now);
            else retryAt = now.plus(backoff(attempts));
        }
        return false;
    }

    static Duration backoff(int attempt) {
        Duration d = FIRST_RETRY.multipliedBy(1L << Math.min(attempt - 1, 10));
        return d.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : d;
    }

    /**
     * Confere o que uma tentativa anterior pode ter gravado antes de falhar: o que está no arquivo depois da última
     * gravação sincronizada tem de ser o começo exato das pendentes. Devolve quantos bytes delas já estão lá (não serão
     * regravados). Não tira nada da fila: só a sincronização confirmada faz isso.
     */
    private long reconcile() throws IOException, Mismatch {
        long size;
        try {
            size = Files.size(file);
        } catch (NoSuchFileException e) {
            size = 0;
        }
        if (size < diskBytes) throw new Mismatch(); // truncado ou trocado por outro
        long extra = size - diskBytes;
        if (extra == 0) return 0;
        if (extra > pendingBytes) throw new Mismatch(); // há mais do que este histórico tentou gravar
        byte[] got = new byte[(int) extra];
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer b = ByteBuffer.wrap(got);
            long pos = diskBytes;
            while (b.hasRemaining()) {
                int n = ch.read(b, pos);
                if (n < 0) throw new Mismatch();
                pos += n;
            }
        }
        int off = 0;
        for (Pending p : pending) {
            if (off >= got.length) break;
            int n = Math.min(p.line().length, got.length - off);
            if (!Arrays.equals(got, off, off + n, p.line(), 0, n)) throw new Mismatch();
            off += n;
        }
        return extra;
    }

    /**
     * Desiste do que não está no arquivo: essas pendentes são contadas como perdidas (ficam só na memória desta sessão;
     * no disco fica a lacuna de sequência). Linhas que já estão inteiras no arquivo continuam na fila só para a
     * sincronização (não são perdidas nem regravadas); um resto de linha gravado pela metade é completado com a quebra
     * (vira linha ilegível). Um aviso de perda ainda não gravado é SUBSTITUÍDO pelo novo, que cobre toda a perda ainda
     * não registrada no disco — a contagem nunca encolhe e a memória nunca acumula avisos.
     */
    private void abandon(Instant now) {
        long present;
        try {
            present = reconcile();
        } catch (Mismatch | IOException | RuntimeException e) {
            present = -1; // fim desconhecido ou alheio: nada da fila é dado como gravado
        }
        ArrayDeque<Pending> keep = new ArrayDeque<>();
        long kept = 0;
        long covered = 0;
        long n = 0;
        byte[] torn = null;
        for (Pending p : pending) {
            if (present >= 0 && kept + p.line().length <= present) {
                keep.addLast(p); // inteira no arquivo: falta só a sincronização
                kept += p.line().length;
                if (p.gap()) covered += p.reports();
                continue;
            }
            if (present > kept && torn == null) torn = Arrays.copyOf(p.line(), (int) (present - kept)); // gravada pela metade
            if (p.gap()) removeEvent(p.sequence()); // aviso não gravado: substituído abaixo, nunca acumulado
            else if (p.sequence() > 0) n++;
        }
        lost += n;
        unreported += n;
        pending.clear();
        pendingBytes = 0;
        attempts = 0;
        retryAt = now.plus(backoff(1));
        keep.forEach(this::enqueue);
        if (torn != null) {
            byte[] fix = Arrays.copyOf(torn, torn.length + 1);
            fix[torn.length] = '\n';
            enqueue(new Pending(0, fix, -1));
        } else if (present < 0) {
            try { // daqui em diante vale o arquivo como está; um resto sem quebra de linha fica separado (linha ilegível)
                long size = Files.size(file);
                diskBytes = size;
                if (size > 0 && !endsWithNewline(size)) enqueue(new Pending(0, new byte[] {'\n'}, -1));
            } catch (IOException | RuntimeException e) {
                // a próxima conferência decide
            }
        }
        long report = unreported - covered;
        if (report <= 0) return;
        storeFailed = true;
        ActivityEvent gap = ActivityEvent.of(id, now, Source.COLLECTOR, "EVENT_GAP", Level.WARNING, Category.TELEMETRY)
                .error(ErrorCode.OBS_STORE_FAILED.code()).status(STORE_STATUS).count("lost", report).build().withSequence(nextSequence++);
        byte[] line = (ActivityCodec.encode(gap) + "\n").getBytes(StandardCharsets.UTF_8);
        enqueue(new Pending(gap.sequence(), line, report));
        bytes += line.length;
        events.add(gap);
    }

    /** Tira da memória um aviso que nunca foi gravado (a lista está em ordem de sequência). */
    private void removeEvent(long sequence) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).sequence() == sequence) {
                events.remove(i);
                return;
            }
            if (events.get(i).sequence() < sequence) return;
        }
    }

    private boolean endsWithNewline(long size) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer b = ByteBuffer.allocate(1);
            return ch.read(b, size - 1) == 1 && b.get(0) == '\n';
        }
    }

    private boolean queueFull() {
        return pending.size() >= MAX_PENDING || pendingBytes >= MAX_PENDING_BYTES;
    }

    private void enqueue(Pending p) {
        pending.addLast(p);
        pendingBytes += p.line().length;
    }

    /** A origem cuja posição o evento marca (o aviso do coletor diz em {@code status} de qual origem falou). */
    static Source origin(ActivityEvent e) {
        if (e.source() != Source.COLLECTOR) return e.source();
        return ActivityCollector.AGENT_ORIGIN.equals(e.status()) ? Source.AGENT : Source.FRAMEWORK;
    }

    private void add(ActivityEvent e, List<ActivityEvent> accepted, Instant now) {
        // fila cheia: grava antes (lote grande e saudável); só descarta se a gravação estiver falhando — nunca cresce sem limite
        if (queueFull() && !flush(now, false) && queueFull()) abandon(now);
        ActivityEvent s = e.withSequence(nextSequence++);
        byte[] line = (ActivityCodec.encode(s) + "\n").getBytes(StandardCharsets.UTF_8);
        enqueue(new Pending(s.sequence(), line, -1));
        // na memória na ordem da sequência (a busca por sequência depende disso), mesmo antes de chegar ao disco
        events.add(s);
        if (s.sourcePosition() != null) positions.merge(origin(s), s.sourcePosition(), Math::max);
        accepted.add(s);
        bytes += line.length;
    }

    /** Eventos com sequência maior que {@code after}, em ordem. */
    synchronized List<ActivityEvent> since(long after) {
        int lo = 0;
        int hi = events.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (events.get(mid).sequence() <= after) lo = mid + 1;
            else hi = mid;
        }
        return List.copyOf(events.subList(lo, events.size()));
    }

    synchronized List<ActivityEvent> all() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    synchronized long position(Source s) {
        return positions.getOrDefault(s, 0L);
    }

    synchronized boolean has(String code) {
        for (ActivityEvent e : events) if (e.code().equals(code)) return true;
        return false;
    }

    synchronized boolean hasSourceProblem(String code, String origin) {
        for (ActivityEvent e : events) if (e.source() == Source.COLLECTOR && e.code().equals(code) && origin.equals(e.status())) return true;
        return false;
    }

    synchronized int count(String code) {
        int n = 0;
        for (ActivityEvent e : events) if (e.code().equals(code)) n++;
        return n;
    }

    synchronized String firstFrameworkExecution() {
        for (ActivityEvent e : events) if (e.source() == Source.FRAMEWORK && e.frameworkExecutionId() != null) return e.frameworkExecutionId();
        return null;
    }

    synchronized int size() {
        return events.size();
    }

    synchronized long bytes() {
        return bytes;
    }

    synchronized boolean capped() {
        return capped;
    }

    synchronized long dropped() {
        return dropped;
    }

    synchronized boolean sequenceGap() {
        return sequenceGap;
    }

    synchronized int unreadable() {
        return unreadable;
    }

    synchronized boolean storeFailed() {
        return storeFailed;
    }

    synchronized boolean tooLarge() {
        return tooLarge;
    }

    /** Eventos aceitos ainda sem gravação sincronizada no disco (a gravação será tentada de novo). */
    synchronized int pendingCount() {
        return (int) pending.stream().filter(p -> p.sequence() > 0).count();
    }

    /** Avisos de perda na memória ainda não gravados (no máximo um). */
    synchronized int unwrittenGapNotices() {
        return (int) pending.stream().filter(Pending::gap).count();
    }

    /** Eventos que não puderam ser gravados e foram descartados do disco (contados). */
    synchronized long lost() {
        return lost;
    }

    synchronized long writeFailures() {
        return writeFailures;
    }

    synchronized void writer(Writer w) {
        this.writer = w;
    }

    /** O arquivo termina com o fechamento da coleta? (lê só o fim; não carrega o histórico) */
    static boolean closedOnDisk(Path runDir) {
        return lastEvent(runDir).map(e -> "COLLECTION_COMPLETED".equals(e.code()) || "HISTORY_PURGED".equals(e.code())).orElse(false);
    }

    /** Sequência do último evento gravado (0 = nenhum). */
    static long lastSequence(Path runDir) {
        return lastEvent(runDir).map(ActivityEvent::sequence).orElse(0L);
    }

    private static java.util.Optional<ActivityEvent> lastEvent(Path runDir) {
        Path f = runDir.resolve(FILE);
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return java.util.Optional.empty();
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f.toFile(), "r")) {
                long len = raf.length();
                int n = (int) Math.min(2L * ActivityCodec.MAX_LINE, len);
                byte[] b = new byte[n];
                raf.seek(len - n);
                raf.readFully(b);
                if (n == 0 || b[n - 1] != 0x0A) return java.util.Optional.empty();
                String t = new String(b, 0, n - 1, StandardCharsets.UTF_8);
                return ActivityCodec.decode(t.substring(t.lastIndexOf(0x0A) + 1));
            }
        } catch (IOException | RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    Path file() {
        return file;
    }
}
