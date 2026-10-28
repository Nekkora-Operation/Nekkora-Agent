package com.nekkora.agent.platform;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.obs.ActivityCodec;
import com.nekkora.agent.obs.ActivityEvent;
import com.nekkora.agent.platform.DeliveryStore.Range;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Leitura do histórico local ({@code atividade.jsonl}) para a entrega, SÓ LEITURA: o arquivo continua sendo gravado
 * apenas pela coleta. Monta o próximo lote a partir de {@code settledThrough + 1}, em ordem de sequência, com as linhas
 * como estão gravadas (o envelope já saneado; nada é reescrito).
 * <p>
 * O que NÃO é perda: linha final sem quebra (gravação em andamento), evento ainda não sincronizado no disco (acima de
 * {@code durableThrough}), arquivo ainda inexistente. A leitura para e tenta de novo depois.
 * <p>
 * Perda definitiva, com faixa exata (contrato, sec. 8.2), só em dois casos, ambos registrados pelo próprio Agent no
 * histórico: (1) uma lacuna de sequência seguida IMEDIATAMENTE do aviso {@code EVENT_GAP}/{@code AGT-OBS-009} (a coleta
 * descartou do disco os números que estavam pendentes e grava o aviso logo depois deles); (2) o arquivo substituído
 * pela retenção local por um único {@code HISTORY_PURGED} que diz até que sequência apagou. Qualquer outra lacuna para a
 * entrega ({@link Stop#HOLE_UNEXPLAINED}) e espera uma decisão da pessoa: nada é declarado por suposição.
 */
final class EventReader {
    static final int MAX_EVENTS = 500;
    /** Abaixo dos 2 MiB do lote no servidor (500 envelopes de até 4 KiB cabem com folga). */
    static final int MAX_BYTES = 1_500_000;
    static final String FILE = "atividade.jsonl";
    private static final int MAX_LINE = 4096;

    /** Uma linha do histórico: sequência, o JSON como está gravado e o fim dela no arquivo. */
    record Line(long sequence, String raw, long end) {}

    enum Stop {
        /** Chegou ao fim do que está gravado. */
        END,
        /** Lote cheio (eventos ou bytes). */
        LIMIT,
        /** Linha final sem quebra: gravação em andamento. */
        PARTIAL_LINE,
        /** O próximo evento ainda não está sincronizado no disco. */
        NOT_DURABLE,
        /** Lacuna sem perda registrada: a entrega para nela. */
        HOLE_UNEXPLAINED
    }

    /**
     * O próximo lote. {@code missing}: faixas de perda definitiva ANTES de eventos do lote (declaradas junto).
     * {@code baseOffset}/{@code baseSequence}: fim da última linha já liquidada vista (de onde a leitura partiu ou até
     * onde avançou). {@code closed}: o último evento gravado é o fechamento da coleta ({@code COLLECTION_COMPLETED} ou
     * {@code HISTORY_PURGED}) e a leitura chegou até ele. {@code rescanned}: a posição guardada não conferia (arquivo
     * substituído) e a leitura recomeçou do início.
     */
    record Batch(List<Line> events, List<Range> missing, Stop stop, Long holeAt, long baseOffset, long baseSequence, boolean closed, long lastSequence,
                 boolean rescanned, boolean exists) {
        boolean empty() {
            return events.isEmpty() && missing.isEmpty();
        }
    }

    private EventReader() {}

    static Batch next(Path runDir, String executionId, long settledThrough, long readOffset, long readSequence, OptionalLong durableThrough,
                      List<Range> declared) throws IOException {
        Path f = runDir.resolve(FILE);
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return new Batch(List.of(), List.of(), Stop.END, null, 0, 0, false, 0, false, false);
        long size = Files.size(f);
        boolean rescanned = false;
        long start = 0;
        long baseSeq = 0;
        ActivityEvent base = null;
        if (readOffset > 0) {
            base = readOffset <= size ? lineEndingAt(f, readOffset, executionId) : null;
            if (base != null && base.sequence() == readSequence && readSequence <= settledThrough) {
                start = readOffset;
                baseSeq = readSequence;
            } else {
                base = null;
                rescanned = true; // a retenção local trocou o arquivo (ou ele foi alterado): recomeça do início
            }
        }
        List<Line> events = new ArrayList<>();
        List<Range> missing = new ArrayList<>();
        long expected = settledThrough + 1;
        long bytes = 0;
        long baseOffset = start;
        // a linha-base (já liquidada) conta como o último evento visto até aqui
        long lastSeq = base == null ? 0 : base.sequence();
        String lastCode = base == null ? null : base.code();
        Stop stop = Stop.END;
        Long holeAt = null;
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ); InputStream in = new BufferedInputStream(Channels.newInputStream(ch.position(start)), 64 * 1024)) {
            long pos = start;
            byte[] buf = new byte[MAX_LINE + 2];
            while (true) {
                int n = 0;
                int c;
                boolean overlong = false;
                while ((c = in.read()) != -1 && c != '\n') {
                    if (n < buf.length) buf[n] = (byte) c;
                    n++;
                    if (n > MAX_LINE) overlong = true;
                }
                if (c == -1) {
                    if (n > 0) stop = Stop.PARTIAL_LINE; // gravação em andamento: não é perda
                    break;
                }
                long end = pos + n + 1;
                pos = end;
                if (overlong) continue; // ilegível: se esconder uma sequência, a lacuna aparece adiante
                String raw = new String(buf, 0, n, StandardCharsets.UTF_8);
                var decoded = ActivityCodec.decode(raw).filter(e -> e.agentExecutionId().equals(executionId));
                if (decoded.isEmpty()) continue;
                ActivityEvent e = decoded.get();
                long seq = e.sequence();
                lastSeq = Math.max(lastSeq, seq);
                lastCode = e.code();
                if (seq < expected) {
                    if (seq <= settledThrough && events.isEmpty() && missing.isEmpty()) {
                        baseOffset = end; // já liquidado: a próxima leitura pode partir daqui
                        baseSeq = seq;
                    }
                    continue;
                }
                // faixa já declarada perdida que começa aqui (repetida no reenvio, ou desistência decidida pela pessoa):
                // vai como ausência, e as linhas que ainda existam dentro dela não são enviadas
                Range dr = startingAt(declared, expected);
                if (dr != null && seq <= dr.to()) {
                    if (!missing.contains(dr)) {
                        if (events.size() >= MAX_EVENTS) {
                            stop = Stop.LIMIT;
                            break;
                        }
                        missing.add(dr);
                    }
                    if (seq < dr.to()) continue;
                    expected = dr.to() + 1;
                    continue;
                }
                if (dr != null) {
                    if (!missing.contains(dr)) missing.add(dr);
                    expected = dr.to() + 1;
                }
                if (durableThrough.isPresent() && seq > durableThrough.getAsLong()) {
                    stop = Stop.NOT_DURABLE;
                    break;
                }
                // a faixa perdida vai SEMPRE no mesmo lote do evento que a explica (o aviso ou o expurgo)
                if (events.size() >= MAX_EVENTS || bytes + raw.length() + 1 > MAX_BYTES) {
                    stop = Stop.LIMIT;
                    break;
                }
                if (seq > expected) {
                    Range hole = new Range(expected, seq - 1);
                    if (!(declared.contains(hole) || definitiveLoss(e, hole))) {
                        stop = Stop.HOLE_UNEXPLAINED;
                        holeAt = expected;
                        break;
                    }
                    missing.add(hole);
                }
                events.add(new Line(seq, raw, end));
                bytes += raw.length() + 1;
                expected = seq + 1;
            }
        }
        // o fechamento só é conhecido quando a leitura chegou ao fim do que está gravado
        boolean closed = stop == Stop.END && ("COLLECTION_COMPLETED".equals(lastCode) || "HISTORY_PURGED".equals(lastCode));
        return new Batch(List.copyOf(events), List.copyOf(missing), stop, holeAt, baseOffset, baseSeq, closed, lastSeq, rescanned, true);
    }

    /** Primeira sequência legível desta execução maior que {@code after} (−1 se não houver), sem regra de lacuna. */
    static long firstSequenceAfter(Path runDir, String executionId, long after) {
        Path f = runDir.resolve(FILE);
        try (var lines = Files.lines(f, StandardCharsets.UTF_8)) {
            return lines.filter(l -> l.length() <= MAX_LINE).map(ActivityCodec::decode).flatMap(java.util.Optional::stream)
                    .filter(e -> e.agentExecutionId().equals(executionId) && e.sequence() > after).mapToLong(ActivityEvent::sequence).min().orElse(-1);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static Range startingAt(List<Range> declared, long from) {
        for (Range r : declared) if (r.from() == from) return r;
        return null;
    }

    /** Perda registrada pelo próprio Agent logo depois da lacuna (aviso da coleta ou expurgo local que cobre a faixa). */
    static boolean definitiveLoss(ActivityEvent after, Range hole) {
        if (after.source() == ActivityEvent.Source.COLLECTOR && "EVENT_GAP".equals(after.code()) && ErrorCode.OBS_STORE_FAILED.code().equals(after.errorCode())) {
            return true;
        }
        if ("HISTORY_PURGED".equals(after.code())) {
            Long until = after.counts().get("purged_until_sequence");
            return until != null && until == hole.to();
        }
        return false;
    }

    /** O evento da linha que termina exatamente em {@code offset} ({@code null} se não houver uma linha válida ali). */
    static ActivityEvent lineEndingAt(Path f, long offset, String executionId) {
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            int n = (int) Math.min(MAX_LINE + 1L, offset);
            byte[] b = new byte[n];
            raf.seek(offset - n);
            raf.readFully(b);
            if (b[n - 1] != '\n') return null;
            int from = n - 2;
            while (from >= 0 && b[from] != '\n') from--;
            if (from < 0 && offset - n > 0) return null; // a linha é maior que o limite: não é deste histórico
            String raw = new String(b, from + 1, n - 2 - from, StandardCharsets.UTF_8);
            return ActivityCodec.decode(raw).filter(e -> e.agentExecutionId().equals(executionId)).orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
