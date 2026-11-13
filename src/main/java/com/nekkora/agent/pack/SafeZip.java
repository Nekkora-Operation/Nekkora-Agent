package com.nekkora.agent.pack;

import com.nekkora.agent.pack.ImportProblem.Code;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * AGENT-PACOTES-001: leitura de um ZIP NÃO confiável pelo diretório central, conferindo cada item contra o cabeçalho local, e
 * extração para uma pasta privada do Agent. Nada do ZIP é executado; nenhum ZIP contido nele é aberto (vira um arquivo
 * comum).
 * <p>
 * Ordem: (1) todo o diretório central é lido e conferido — nomes, tipos, colisões, limites declarados, sobreposição — ANTES
 * de qualquer gravação; (2) cada arquivo é descompactado com teto pelos bytes PRODUZIDOS (cabeçalho enganoso não passa),
 * conferindo tamanho e CRC. Uma recusa no meio apaga só o que esta leitura gravou (a pasta de destino é da operação).
 */
public final class SafeZip {
    private SafeZip() {}

    /** Cancelamento cooperativo (a pessoa cancelou ou o assistente foi invalidado). */
    @FunctionalInterface
    public interface Cancel {
        boolean requested();

        Cancel NEVER = () -> false;
    }

    /** Um arquivo extraído: nome no ZIP (sem barra final), arquivo gravado e tamanho. */
    public record Item(String name, Path file, long size) {}

    /** Conteúdo extraído: arquivos (na ordem do diretório central) e pastas declaradas. */
    public record Content(List<Item> files, Set<String> dirs) {}

    private record Entry(String name, boolean dir, int method, long crc, long csize, long size, long localOffset, byte[] rawName) {}

    private static final int LOCAL = 0x04034b50;
    private static final int CENTRAL = 0x02014b50;
    private static final int EOCD = 0x06054b50;
    private static final int ZIP64_LOCATOR = 0x07064b50;

    /** Lê e confere o ZIP {@code zip} e extrai os arquivos em {@code into} (pasta existente e vazia, da operação). */
    public static Content extract(Path zip, Path into, ZipLimits limits, Cancel cancel) throws ImportProblem {
        try (FileChannel ch = FileChannel.open(zip, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size == 0) throw new ImportProblem(Code.EMPTY_FILE);
            if (size > limits.zipBytes()) throw new ImportProblem(Code.ZIP_TOO_LARGE);
            ByteBuffer head = read(ch, 0, (int) Math.min(4, size));
            int sig = head.remaining() == 4 ? head.getInt() : 0;
            if (sig != LOCAL && sig != EOCD) throw new ImportProblem(Code.NOT_ZIP);
            List<Entry> entries = central(ch, size, limits);
            Set<String> dirs = new HashSet<>();
            List<Entry> files = new ArrayList<>();
            for (Entry e : entries) {
                if (e.dir()) dirs.add(e.name());
                else files.add(e);
            }
            if (files.isEmpty()) throw new ImportProblem(Code.NO_FILES);
            List<Item> out = new ArrayList<>();
            long[] total = {0};
            for (Entry e : files) {
                if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
                out.add(write(ch, e, into, limits, total, cancel));
            }
            return new Content(List.copyOf(out), Set.copyOf(dirs));
        } catch (java.nio.file.NoSuchFileException e) {
            throw new ImportProblem(Code.SOURCE_NOT_FOUND);
        } catch (java.nio.file.AccessDeniedException e) {
            throw new ImportProblem(Code.SOURCE_LOCKED);
        } catch (IOException e) {
            throw new ImportProblem(Code.TRUNCATED);
        }
    }

    // ------------------------------------------------------------------ diretório central

    private static List<Entry> central(FileChannel ch, long size, ZipLimits limits) throws IOException, ImportProblem {
        int tail = (int) Math.min(size, 22 + 65535);
        ByteBuffer t = read(ch, size - tail, tail);
        int at = -1;
        for (int i = tail - 22; i >= 0; i--) {
            if (t.getInt(i) == EOCD) {
                at = i;
                break;
            }
        }
        if (at < 0) throw new ImportProblem(Code.TRUNCATED);
        long eocd = size - tail + at;
        if (at >= 20 && t.getInt(at - 20) == ZIP64_LOCATOR) throw new ImportProblem(Code.UNSUPPORTED_FORMAT);
        int disk = Short.toUnsignedInt(t.getShort(at + 4));
        int cdDisk = Short.toUnsignedInt(t.getShort(at + 6));
        int onDisk = Short.toUnsignedInt(t.getShort(at + 8));
        int count = Short.toUnsignedInt(t.getShort(at + 10));
        long cdSize = Integer.toUnsignedLong(t.getInt(at + 12));
        long cdOffset = Integer.toUnsignedLong(t.getInt(at + 16));
        if (disk != 0 || cdDisk != 0 || onDisk != count || count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw new ImportProblem(Code.UNSUPPORTED_FORMAT);
        }
        if (count == 0) throw new ImportProblem(Code.NO_FILES);
        if (count > limits.entries()) throw new ImportProblem(Code.TOO_MANY_ENTRIES);
        if (cdOffset + cdSize > eocd) throw new ImportProblem(Code.TRUNCATED);
        ByteBuffer b = read(ch, cdOffset, (int) cdSize);
        List<Entry> out = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>(); // chave de colisão → é pasta
        long declared = 0;
        int p = 0;
        for (int i = 0; i < count; i++) {
            if (p + 46 > b.limit() || b.getInt(p) != CENTRAL) throw new ImportProblem(Code.TRUNCATED);
            int madeBy = Short.toUnsignedInt(b.getShort(p + 4));
            int flags = Short.toUnsignedInt(b.getShort(p + 8));
            int method = Short.toUnsignedInt(b.getShort(p + 10));
            long crc = Integer.toUnsignedLong(b.getInt(p + 16));
            long csize = Integer.toUnsignedLong(b.getInt(p + 20));
            long usize = Integer.toUnsignedLong(b.getInt(p + 24));
            int nameLen = Short.toUnsignedInt(b.getShort(p + 28));
            int extraLen = Short.toUnsignedInt(b.getShort(p + 30));
            int commentLen = Short.toUnsignedInt(b.getShort(p + 32));
            long external = Integer.toUnsignedLong(b.getInt(p + 38));
            long local = Integer.toUnsignedLong(b.getInt(p + 42));
            if (p + 46 + nameLen + extraLen + commentLen > b.limit()) throw new ImportProblem(Code.TRUNCATED);
            byte[] raw = new byte[nameLen];
            b.get(p + 46, raw);
            p += 46 + nameLen + extraLen + commentLen;
            String name = name(raw, (flags & 0x800) != 0);
            if ((flags & 0x1) != 0 || (flags & 0x40) != 0 || method == 99) throw new ImportProblem(Code.ENCRYPTED, ZipNames.display(name));
            if (method != 0 && method != 8) throw new ImportProblem(Code.UNSUPPORTED_METHOD, ZipNames.display(name));
            if (csize == 0xFFFFFFFFL || usize == 0xFFFFFFFFL || local == 0xFFFFFFFFL) throw new ImportProblem(Code.UNSUPPORTED_FORMAT);
            int type = (int) (external >>> 16) & 0xF000;
            boolean unixHost = (madeBy >>> 8) == 3;
            if (type != 0 && type != 0x8000 && type != 0x4000 || unixHost && type == 0xA000 || (external & 0x400) != 0) {
                throw new ImportProblem(Code.SPECIAL_ENTRY, ZipNames.display(name));
            }
            boolean dir = name.endsWith("/");
            String plain = dir ? name.substring(0, name.length() - 1) : name;
            if (ZipNames.unsafe(plain)) throw new ImportProblem(Code.UNSAFE_PATH, ZipNames.display(name));
            if (ZipNames.windowsInvalid(plain)) throw new ImportProblem(Code.WINDOWS_NAME, ZipNames.display(name));
            if (ZipNames.depth(plain) > limits.depth()) throw new ImportProblem(Code.TOO_DEEP, ZipNames.display(name));
            Boolean before = seen.put(ZipNames.collisionKey(plain), dir);
            if (before != null) throw new ImportProblem(before == dir ? Code.DUPLICATE : Code.FILE_DIR_CONFLICT, ZipNames.display(plain));
            if (dir) {
                if (usize != 0) throw new ImportProblem(Code.CORRUPT_ENTRY, ZipNames.display(name));
            } else {
                if (usize > limits.fileBytes()) throw new ImportProblem(Code.FILE_TOO_LARGE, ZipNames.display(name));
                declared += usize;
                if (declared > limits.totalBytes()) throw new ImportProblem(Code.CONTENT_TOO_LARGE);
                if (usize > limits.ratioFloor() && (csize == 0 || usize / Math.max(1, csize) > limits.ratio())) {
                    throw new ImportProblem(Code.EXPANSION, ZipNames.display(name));
                }
            }
            out.add(new Entry(plain, dir, method, crc, csize, usize, local, raw));
        }
        // pastas implícitas (a/b/c.py cria a e a/b): um ARQUIVO com esse caminho é conflito
        for (Entry e : out) {
            String[] segs = e.name().split("/");
            StringBuilder parent = new StringBuilder();
            for (int i = 0; i < segs.length - 1; i++) {
                parent.append(i == 0 ? "" : "/").append(segs[i]);
                if (Boolean.FALSE.equals(seen.get(ZipNames.collisionKey(parent.toString())))) {
                    throw new ImportProblem(Code.FILE_DIR_CONFLICT, ZipNames.display(parent.toString()));
                }
            }
        }
        locals(ch, out, cdOffset);
        return out;
    }

    /** Cabeçalhos locais: nome igual ao do diretório central e dados sem sobreposição, antes do diretório central. */
    private static void locals(FileChannel ch, List<Entry> entries, long cdOffset) throws IOException, ImportProblem {
        List<long[]> ranges = new ArrayList<>();
        for (Entry e : entries) {
            if (e.dir()) continue;
            long start = dataStart(ch, e, cdOffset);
            ranges.add(new long[] {e.localOffset(), start + e.csize()});
            if (start + e.csize() > cdOffset) throw new ImportProblem(Code.TRUNCATED);
        }
        ranges.sort(Comparator.comparingLong(r -> r[0]));
        for (int i = 1; i < ranges.size(); i++) {
            if (ranges.get(i)[0] < ranges.get(i - 1)[1]) throw new ImportProblem(Code.OVERLAPPING);
        }
    }

    private static long dataStart(FileChannel ch, Entry e, long cdOffset) throws IOException, ImportProblem {
        if (e.localOffset() + 30 > cdOffset) throw new ImportProblem(Code.TRUNCATED);
        ByteBuffer h = read(ch, e.localOffset(), 30);
        if (h.getInt(0) != LOCAL) throw new ImportProblem(Code.OVERLAPPING);
        int nameLen = Short.toUnsignedInt(h.getShort(26));
        int extraLen = Short.toUnsignedInt(h.getShort(28));
        if (e.localOffset() + 30 + nameLen > cdOffset) throw new ImportProblem(Code.TRUNCATED);
        ByteBuffer n = read(ch, e.localOffset() + 30, nameLen);
        byte[] raw = new byte[nameLen];
        n.get(raw);
        // nome do cabeçalho local diferente do diretório central: dois leitores veriam arquivos diferentes
        if (!java.util.Arrays.equals(raw, e.rawName())) throw new ImportProblem(Code.OVERLAPPING, ZipNames.display(e.name()));
        return e.localOffset() + 30 + nameLen + extraLen;
    }

    private static String name(byte[] raw, boolean utf8) throws ImportProblem {
        boolean ascii = true;
        for (byte x : raw) if (x < 0) ascii = false;
        if (!ascii && !utf8) throw new ImportProblem(Code.UNDECLARED_ENCODING);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            throw new ImportProblem(Code.UNDECLARED_ENCODING);
        }
    }

    // ------------------------------------------------------------------ extração

    private static Item write(FileChannel ch, Entry e, Path into, ZipLimits limits, long[] total, Cancel cancel) throws IOException, ImportProblem {
        long start = dataStart(ch, e, Long.MAX_VALUE);
        Path target = into.resolve(e.name()).normalize();
        if (!target.startsWith(into)) throw new ImportProblem(Code.UNSAFE_PATH, ZipNames.display(e.name()));
        CRC32 crc = new CRC32();
        long produced = 0;
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException ex) {
            throw Io.work(ex, into);
        }
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (e.method() == 0) {
                if (e.csize() != e.size()) throw new ImportProblem(Code.CORRUPT_ENTRY, ZipNames.display(e.name()));
                long pos = start;
                long left = e.csize();
                while (left > 0) {
                    if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
                    ByteBuffer chunk = read(ch, pos, (int) Math.min(left, 64 * 1024));
                    byte[] buf = new byte[chunk.remaining()];
                    chunk.get(buf);
                    produced += buf.length;
                    total[0] += buf.length;
                    if (total[0] > limits.totalBytes()) throw new ImportProblem(Code.CONTENT_TOO_LARGE);
                    crc.update(buf);
                    writeOut(out, buf, buf.length, into);
                    pos += buf.length;
                    left -= buf.length;
                }
            } else {
                Inflater inf = new Inflater(true);
                try {
                    long pos = start;
                    long left = e.csize();
                    byte[] buf = new byte[64 * 1024];
                    while (!inf.finished()) {
                        if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
                        if (inf.needsInput()) {
                            if (left <= 0) throw new ImportProblem(Code.CORRUPT_ENTRY, ZipNames.display(e.name()));
                            ByteBuffer chunk = read(ch, pos, (int) Math.min(left, 64 * 1024));
                            byte[] in = new byte[chunk.remaining()];
                            chunk.get(in);
                            inf.setInput(in);
                            pos += in.length;
                            left -= in.length;
                        }
                        int n = inf.inflate(buf);
                        if (n == 0 && inf.needsDictionary()) throw new ImportProblem(Code.CORRUPT_ENTRY, ZipNames.display(e.name()));
                        produced += n;
                        total[0] += n;
                        // teto pelos bytes PRODUZIDOS: o cabeçalho pode mentir
                        if (produced > e.size()) throw new ImportProblem(Code.MORE_THAN_DECLARED, ZipNames.display(e.name()));
                        if (produced > limits.fileBytes()) throw new ImportProblem(Code.FILE_TOO_LARGE, ZipNames.display(e.name()));
                        if (total[0] > limits.totalBytes()) throw new ImportProblem(Code.CONTENT_TOO_LARGE);
                        if (produced > limits.ratioFloor() && produced / Math.max(1, e.csize()) > limits.ratio()) {
                            throw new ImportProblem(Code.EXPANSION, ZipNames.display(e.name()));
                        }
                        crc.update(buf, 0, n);
                        writeOut(out, buf, n, into);
                    }
                } catch (DataFormatException ex) {
                    throw new ImportProblem(Code.CORRUPT_ENTRY, ZipNames.display(e.name()));
                } finally {
                    inf.end();
                }
            }
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            throw new ImportProblem(Code.DUPLICATE, ZipNames.display(e.name()));
        }
        if (produced != e.size() || crc.getValue() != e.crc()) throw new ImportProblem(Code.CORRUPT_ENTRY, ZipNames.display(e.name()));
        return new Item(e.name(), target, produced);
    }

    private static void writeOut(OutputStream out, byte[] buf, int n, Path into) throws ImportProblem {
        try {
            out.write(buf, 0, n);
        } catch (IOException ex) {
            throw Io.work(ex, into);
        }
    }

    private static ByteBuffer read(FileChannel ch, long pos, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            int n = ch.read(b, pos + b.position());
            if (n < 0) break;
        }
        b.flip();
        if (b.remaining() < len) throw new IOException("fim do arquivo");
        return b.order(ByteOrder.LITTLE_ENDIAN);
    }
}
