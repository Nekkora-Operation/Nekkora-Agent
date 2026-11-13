package com.nekkora.agent.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Montador de ZIP byte a byte para os testes de recusa (AGENT-PACOTES-001): cada campo do cabeçalho pode ser adulterado —
 * nome (inclusive diferente no cabeçalho local), método, flags, atributos externos, tamanho e CRC declarados, ZIP64. Só
 * para testes: ZIPs maliciosos nascem aqui, nunca ficam versionados como binários.
 */
final class ZipForge {

    static final class E {
        String name;
        byte[] data;
        int method = 8;
        int flags = 0x800; // UTF-8
        int madeBy = 20; // DOS/FAT
        long external = 0;
        Long declaredSize;
        Long crc;
        String localName;
        byte[] rawName;

        E(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }

        E method(int m) {
            method = m;
            return this;
        }

        E flags(int f) {
            flags = f;
            return this;
        }

        E unixMode(int mode) {
            madeBy = 3 << 8 | 20;
            external = (long) mode << 16;
            return this;
        }

        E external(long x) {
            external = x;
            return this;
        }

        E declaredSize(long s) {
            declaredSize = s;
            return this;
        }

        E crc(long c) {
            crc = c;
            return this;
        }

        E localName(String n) {
            localName = n;
            return this;
        }

        E rawName(byte[] n) {
            rawName = n;
            return this;
        }
    }

    private final List<E> entries = new ArrayList<>();
    private boolean zip64Locator;

    ZipForge add(E e) {
        entries.add(e);
        return this;
    }

    ZipForge file(String name, String text) {
        return add(new E(name, text.getBytes(StandardCharsets.UTF_8)));
    }

    ZipForge dir(String name) {
        return add(new E(name.endsWith("/") ? name : name + "/", new byte[0]).method(0));
    }

    ZipForge zip64() {
        zip64Locator = true;
        return this;
    }

    static E e(String name, String text) {
        return new E(name, text.getBytes(StandardCharsets.UTF_8));
    }

    byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream cd = new ByteArrayOutputStream();
        for (E e : entries) {
            byte[] name = e.rawName != null ? e.rawName : e.name.getBytes(StandardCharsets.UTF_8);
            byte[] lname = e.localName != null ? e.localName.getBytes(StandardCharsets.UTF_8) : name;
            byte[] body = e.method == 8 ? deflate(e.data) : e.data;
            CRC32 c = new CRC32();
            c.update(e.data);
            long crc = e.crc != null ? e.crc : c.getValue();
            long usize = e.declaredSize != null ? e.declaredSize : e.data.length;
            int offset = out.size();
            ByteBuffer l = le(30);
            l.putInt(0x04034b50).putShort((short) 20).putShort((short) e.flags).putShort((short) e.method).putShort((short) 0).putShort((short) 0x21)
                    .putInt((int) crc).putInt(body.length).putInt((int) usize).putShort((short) lname.length).putShort((short) 0);
            out.writeBytes(l.array());
            out.writeBytes(lname);
            out.writeBytes(body);
            ByteBuffer h = le(46);
            h.putInt(0x02014b50).putShort((short) e.madeBy).putShort((short) 20).putShort((short) e.flags).putShort((short) e.method).putShort((short) 0)
                    .putShort((short) 0x21).putInt((int) crc).putInt(body.length).putInt((int) usize).putShort((short) name.length).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt((int) e.external).putInt(offset);
            cd.writeBytes(h.array());
            cd.writeBytes(name);
        }
        int cdOffset = out.size();
        out.writeBytes(cd.toByteArray());
        if (zip64Locator) {
            ByteBuffer z = le(20);
            z.putInt(0x07064b50).putInt(0).putLong(0).putInt(1);
            out.writeBytes(z.array());
        }
        ByteBuffer eocd = le(22);
        eocd.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) entries.size()).putShort((short) entries.size()).putInt(cd.size())
                .putInt(cdOffset).putShort((short) 0);
        out.writeBytes(eocd.array());
        return out.toByteArray();
    }

    Path write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes());
        return file;
    }

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(9, true);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        while (!d.finished()) o.write(buf, 0, d.deflate(buf));
        d.end();
        return o.toByteArray();
    }

    private static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** ZIP "normal" de uma pasta de fixture, com prefixo opcional (pasta externa única) e itens extras. */
    static Path ofFolder(Path folder, String prefix, Path out, E... extra) throws IOException {
        ZipForge f = new ZipForge();
        try (var s = Files.walk(folder)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                String rel = folder.relativize(p).toString().replace('\\', '/');
                f.add(new E(prefix + rel, Files.readAllBytes(p)));
            }
        }
        for (E e : extra) {
            e.name = prefix + e.name;
            f.add(e);
        }
        return f.write(out);
    }

    static Path fixture(String name) {
        return Path.of("src", "test", "resources", "agent-pacotes-001", "projetos", name).toAbsolutePath().normalize();
    }
}
