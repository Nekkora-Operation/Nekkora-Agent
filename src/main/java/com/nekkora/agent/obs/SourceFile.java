package com.nekkora.agent.obs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Leitura INCREMENTAL e defensiva de um arquivo JSONL de eventos que o Agent não escreveu. O caminho é FIXO: uma pasta
 * criada pelo Agent ({@code base}) + nomes constantes — nunca um caminho vindo de evento. A cada leitura:
 * <ul>
 *   <li>a pasta-base, cada pasta intermediária e o arquivo precisam existir como item comum, sem ligação simbólica nem
 *       ponto de nova análise (junção) — {@code isSymbolicLink()}/{@code isOther()} sem seguir ligações;</li>
 *   <li>o dono do arquivo precisa ser o dono da pasta-base (a conta Windows da execução), quando o SO informa o dono;</li>
 *   <li>o arquivo não pode ter encolhido, trocado de identidade nem mudado a primeira linha já lida (troca depois de
 *       abrir/rotação/truncamento → {@link Problem#REPLACED}: a coleta daquela origem para, nada é relido nem inventado);</li>
 *   <li>só linhas COMPLETAS são entregues; linha maior que o limite ou que não é UTF-8 vira {@link Line#invalid}; uma
 *       linha incompleta no fim só é declarada parcial na leitura final.</li>
 * </ul>
 * Cada chamada lê no máximo {@code maxBytes} (contrapressão): uma origem enorme não monopoliza o coletor.
 */
final class SourceFile {

    enum Problem { MISSING, UNSAFE, UNREADABLE, REPLACED }

    enum Invalid { TOO_LONG, ENCODING, PARTIAL }

    /** Uma linha lida (texto) ou recusada ({@code invalid}); {@code end} = posição logo depois dela. */
    record Line(String text, Invalid invalid, long end) {}

    /** Resultado de uma leitura: linhas, um problema (nada lido) e se ainda há bytes além do limite desta vez. */
    record Batch(List<Line> lines, Problem problem, boolean more) {
        static Batch of(Problem p) {
            return new Batch(List.of(), p, false);
        }
    }

    private static final byte NL = 0x0A;

    private final Path base;
    private final List<String> relative;
    private final int maxLine;
    private long position;
    private Object identity;
    private int headLength;
    private byte[] headDigest;
    private boolean skipping;
    private long skipStart;

    SourceFile(Path base, List<String> relative, int maxLine, long position) {
        this.base = Objects.requireNonNull(base);
        this.relative = List.copyOf(relative);
        this.maxLine = maxLine;
        this.position = position;
    }

    long position() {
        return position;
    }

    Batch read(int maxBytes, boolean finalRead) {
        Path file = base;
        UserPrincipal owner;
        try {
            if (!plain(base, true)) return Batch.of(Files.exists(base, LinkOption.NOFOLLOW_LINKS) ? Problem.UNSAFE : Problem.MISSING);
            owner = owner(base);
            for (int i = 0; i < relative.size(); i++) {
                file = file.resolve(relative.get(i));
                boolean last = i == relative.size() - 1;
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Batch.of(Problem.MISSING);
                if (!plain(file, !last)) return Batch.of(Problem.UNSAFE);
            }
            if (owner != null && !owner.equals(owner(file))) return Batch.of(Problem.UNSAFE);
        } catch (AccessDeniedException e) {
            return Batch.of(Problem.UNREADABLE);
        } catch (IOException | RuntimeException e) {
            return Batch.of(Problem.UNREADABLE);
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || a.isOther() || !a.isRegularFile()) return Batch.of(Problem.UNSAFE);
            Object id = a.fileKey() != null ? a.fileKey() : a.creationTime();
            long size = ch.size();
            if (identity != null && !identity.equals(id) || size < position || !sameHead(ch)) return Batch.of(Problem.REPLACED);
            identity = id;
            if (headDigest == null && position > 0) rememberFromStart(ch);
            if (size == position) return finalRead && skipping ? finishSkip() : new Batch(List.of(), null, false);
            long readStart = position;
            int n = (int) Math.min(maxBytes, size - readStart);
            ByteBuffer buf = ByteBuffer.allocate(n);
            while (buf.hasRemaining() && ch.read(buf, readStart + buf.position()) > 0) {
                // até encher ou o fim
            }
            byte[] data = Arrays.copyOf(buf.array(), buf.position());
            List<Line> out = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < data.length; i++) {
                if (data[i] != NL) continue;
                long end = readStart + i + 1;
                if (skipping) {
                    skipping = false;
                    out.add(new Line(null, Invalid.TOO_LONG, end));
                } else {
                    out.add(line(data, start, i, end));
                }
                if (headDigest == null && readStart + start == 0) remember(data, i + 1);
                start = i + 1;
            }
            int rest = data.length - start;
            if (!skipping && rest > maxLine) {
                skipping = true; // linha longa demais: descartada até a próxima quebra (contada uma vez)
                skipStart = readStart + start;
            }
            // dentro de uma linha longa demais os bytes são consumidos; senão, só até a última quebra
            position = skipping ? readStart + data.length : readStart + start;
            boolean more = readStart + data.length < size;
            if (finalRead && !more) {
                if (skipping) {
                    out.addAll(finishSkip().lines());
                } else if (rest > 0) {
                    position = readStart + data.length;
                    out.add(new Line(null, Invalid.PARTIAL, position)); // linha sem quebra no fim: escrita interrompida
                }
            }
            return new Batch(out, null, more);
        } catch (NoSuchFileException e) {
            return Batch.of(Problem.MISSING);
        } catch (AccessDeniedException e) {
            return Batch.of(Problem.UNREADABLE);
        } catch (IOException | RuntimeException e) {
            return Batch.of(Problem.UNREADABLE);
        }
    }

    private Batch finishSkip() {
        skipping = false;
        return new Batch(List.of(new Line(null, Invalid.TOO_LONG, Math.max(position, skipStart))), null, false);
    }

    private Line line(byte[] data, int from, int newline, long end) {
        int to = newline > from && data[newline - 1] == '\r' ? newline - 1 : newline;
        if (to - from > maxLine) return new Line(null, Invalid.TOO_LONG, end);
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, from, to - from)).toString();
            return new Line(s, null, end);
        } catch (CharacterCodingException e) {
            return new Line(null, Invalid.ENCODING, end);
        }
    }

    /** A primeira linha já lida continua igual (o arquivo não foi trocado por outro com o mesmo tamanho ou maior). */
    private boolean sameHead(FileChannel ch) throws IOException {
        if (headDigest == null) return true;
        ByteBuffer b = ByteBuffer.allocate(headLength);
        while (b.hasRemaining() && ch.read(b, b.position()) > 0) {
            // primeira linha inteira
        }
        return !b.hasRemaining() && MessageDigest.isEqual(headDigest, sha(b.array()));
    }

    /** Retomada (posição já avançada, p. ex. depois de reinício): a primeira linha atual passa a ser a referência. */
    private void rememberFromStart(FileChannel ch) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(maxLine + 2);
        while (b.hasRemaining() && ch.read(b, b.position()) > 0) {
            // começo do arquivo
        }
        for (int i = 0; i < b.position(); i++) {
            if (b.get(i) == NL) {
                remember(b.array(), i + 1);
                return;
            }
        }
    }

    private void remember(byte[] data, int len) {
        headLength = len;
        headDigest = sha(Arrays.copyOf(data, len));
    }

    private static byte[] sha(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Item comum (pasta ou arquivo), sem ligação simbólica nem junção/ponto de nova análise. */
    private static boolean plain(Path p, boolean directory) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return false;
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (a.isSymbolicLink() || a.isOther()) return false;
        return directory ? a.isDirectory() : a.isRegularFile();
    }

    private static UserPrincipal owner(Path p) {
        try {
            return Files.getOwner(p, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return null; // SO sem dono informado: a verificação de dono não se aplica
        }
    }
}
