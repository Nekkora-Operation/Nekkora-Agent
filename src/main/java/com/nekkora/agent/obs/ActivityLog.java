package com.nekkora.agent.obs;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.obs.ActivityEvent.Category;
import com.nekkora.agent.obs.ActivityEvent.Level;
import com.nekkora.agent.obs.ActivityEvent.Source;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
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
 */
final class ActivityLog {
    static final String FILE = "atividade.jsonl";
    static final int MAX_EVENTS = 20_000;
    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final int RESERVE = 64;
    /** Maior arquivo que a leitura do histórico aceita (limite + reserva com folga). */
    static final long MAX_READ = MAX_BYTES + RESERVE * (long) ActivityCodec.MAX_LINE;

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
                if (ev.source() == Source.COLLECTOR) reserveUsed++;
            }
            nextSequence = prev + 1;
        } catch (IOException | RuntimeException e) {
            storeFailed = true;
        }
    }

    /**
     * Grava um lote em ordem e devolve o que foi aceito (com a sequência). Acima do limite os eventos das origens são
     * descartados e contados; os do coletor usam a reserva.
     */
    synchronized List<ActivityEvent> append(List<ActivityEvent> batch, Instant now) {
        if (batch.isEmpty() || tooLarge) {
            if (tooLarge) dropped += batch.size();
            return List.of();
        }
        List<ActivityEvent> accepted = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        long added = 0;
        for (ActivityEvent e : batch) {
            boolean collector = e.source() == Source.COLLECTOR;
            boolean full = events.size() + accepted.size() >= MAX_EVENTS || bytes + added >= MAX_BYTES;
            if (!collector && (capped || full)) {
                dropped++;
                if (!capped) {
                    capped = true;
                    ActivityEvent gap = ActivityEvent.of(id, now, Source.COLLECTOR, "EVENT_GAP", Level.WARNING, Category.TELEMETRY)
                            .error(ErrorCode.OBS_RETENTION_LIMIT.code()).status("ACTIVITY_STORE").count("max_events", MAX_EVENTS).build();
                    added += add(gap, accepted, out);
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
            added += add(e, accepted, out);
        }
        if (accepted.isEmpty()) return List.of();
        try (FileOutputStream fo = new FileOutputStream(file.toFile(), true)) {
            fo.write(out.toString().getBytes(StandardCharsets.UTF_8));
            fo.getFD().sync();
        } catch (IOException | RuntimeException ex) {
            storeFailed = true; // continua em memória (a tela segue); no disco fica a lacuna de sequência, que é detectada ao reabrir
        }
        bytes += added;
        events.addAll(accepted);
        for (ActivityEvent e : accepted) {
            if (e.sourcePosition() != null) positions.merge(origin(e), e.sourcePosition(), Math::max);
        }
        return accepted;
    }

    /** A origem cuja posição o evento marca (o aviso do coletor diz em {@code status} de qual origem falou). */
    static Source origin(ActivityEvent e) {
        if (e.source() != Source.COLLECTOR) return e.source();
        return ActivityCollector.AGENT_ORIGIN.equals(e.status()) ? Source.AGENT : Source.FRAMEWORK;
    }

    private long add(ActivityEvent e, List<ActivityEvent> accepted, StringBuilder out) {
        ActivityEvent s = e.withSequence(nextSequence++);
        String line = ActivityCodec.encode(s) + "\n";
        out.append(line);
        accepted.add(s);
        return line.getBytes(StandardCharsets.UTF_8).length;
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
