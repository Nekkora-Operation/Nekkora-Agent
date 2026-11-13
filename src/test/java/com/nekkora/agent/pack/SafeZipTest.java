package com.nekkora.agent.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.pack.ImportProblem.Code;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AGENT-PACOTES-001 T01: leitura segura do ZIP (bytes e estrutura, não a extensão). */
class SafeZipTest {

    @TempDir
    Path tmp;

    private Code refused(byte[] zip) throws IOException {
        return refused(zip, ZipLimits.DEFAULT);
    }

    private Code refused(byte[] zip, ZipLimits limits) throws IOException {
        Path f = tmp.resolve("entrada-" + System.nanoTime() + ".ZIP");
        Files.write(f, zip);
        Path into = Files.createDirectories(tmp.resolve("x-" + System.nanoTime()));
        ImportProblem p = assertThrows(ImportProblem.class, () -> SafeZip.extract(f, into, limits, SafeZip.Cancel.NEVER));
        return p.code();
    }

    private SafeZip.Content ok(byte[] zip) throws Exception {
        Path f = tmp.resolve("ok-" + System.nanoTime() + ".zip");
        Files.write(f, zip);
        Path into = Files.createDirectories(tmp.resolve("y-" + System.nanoTime()));
        return SafeZip.extract(f, into, ZipLimits.DEFAULT, SafeZip.Cancel.NEVER);
    }

    @Test
    void contentNotExtensionDecides() throws Exception {
        assertEquals(Code.NOT_ZIP, refused("isto é texto com nome .zip".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Code.NOT_ZIP, refused(new byte[] {'{', '}'}));
        assertEquals(Code.EMPTY_FILE, refused(new byte[0]));
        byte[] good = new ZipForge().file("main.py", "print(1)\n".repeat(200)).bytes();
        assertEquals(Code.TRUNCATED, refused(Arrays.copyOf(good, good.length / 2)), "download interrompido");
        assertEquals(Code.TRUNCATED, refused(Arrays.copyOf(good, good.length - 10)));
        // a extensão em maiúsculas não importa: o conteúdo é que é conferido
        Path upper = tmp.resolve("PROJETO.ZIP");
        Files.write(upper, good);
        assertEquals(1, SafeZip.extract(upper, Files.createDirectories(tmp.resolve("u")), ZipLimits.DEFAULT, SafeZip.Cancel.NEVER).files().size());
    }

    @Test
    void encryptedAndUnsupportedMethodsAreRefused() throws Exception {
        assertEquals(Code.ENCRYPTED, refused(new ZipForge().add(ZipForge.e("main.py", "x").flags(0x801)).bytes()));
        assertEquals(Code.ENCRYPTED, refused(new ZipForge().add(ZipForge.e("main.py", "x").method(99)).bytes()), "AES");
        assertEquals(Code.UNSUPPORTED_METHOD, refused(new ZipForge().add(ZipForge.e("main.py", "x").method(12)).bytes()), "bzip2");
        assertEquals(Code.UNSUPPORTED_METHOD, refused(new ZipForge().add(ZipForge.e("main.py", "x").method(14)).bytes()), "LZMA");
        assertEquals(Code.UNSUPPORTED_FORMAT, refused(new ZipForge().file("main.py", "x").zip64().bytes()));
    }

    @Test
    void sizeLimitsUseProducedBytesNotHeaders() throws Exception {
        ZipLimits small = new ZipLimits(4096, 3, 2000, 1500, 16, 200, 1024 * 1024);
        assertEquals(Code.ZIP_TOO_LARGE, refused(new ZipForge().add(new ZipForge.E("a.bin", random(5000)).method(0)).bytes(), small));
        assertEquals(Code.TOO_MANY_ENTRIES, refused(new ZipForge().file("a.py", "1").file("b.py", "2").file("c.py", "3").file("d.py", "4").bytes(), small));
        assertEquals(Code.FILE_TOO_LARGE, refused(new ZipForge().file("a.py", "x".repeat(1600)).bytes(), small));
        assertEquals(Code.CONTENT_TOO_LARGE, refused(new ZipForge().file("a.py", "x".repeat(1000)).file("b.py", "y".repeat(1001)).bytes(), small));
        // cabeçalho enganoso: declara 10 bytes e descompacta 1 MiB
        assertEquals(Code.MORE_THAN_DECLARED, refused(new ZipForge().add(new ZipForge.E("grande.py", new byte[1024 * 1024]).declaredSize(10)).bytes()));
        // bomba: razão acima de 200 num arquivo acima de 1 MiB (declarada ou produzida)
        assertEquals(Code.EXPANSION, refused(new ZipForge().add(new ZipForge.E("zeros.bin", new byte[8 * 1024 * 1024])).bytes()));
        assertEquals(Code.EXPANSION, refused(new ZipForge().add(new ZipForge.E("zeros.bin", new byte[8 * 1024 * 1024]).declaredSize(8 * 1024 * 1024 + 1)).bytes()));
        // CRC e tamanho conferidos ao descompactar
        assertEquals(Code.CORRUPT_ENTRY, refused(new ZipForge().add(ZipForge.e("a.py", "print(1)").crc(12345)).bytes()));
        assertEquals(Code.CORRUPT_ENTRY, refused(new ZipForge().add(ZipForge.e("a.py", "print(1)").declaredSize(100)).bytes()));
        assertEquals(Code.NO_FILES, refused(new ZipForge().dir("vazia").bytes()));
    }

    @Test
    void pathsThatEscapeAreRefused() throws Exception {
        for (String n : new String[] {"../fora.py", "a/../../fora.py", "/abs.py", "C:/Windows/x.py", "C:x.py", "a\\b.py", "\\\\servidor\\c\\x.py", "//servidor/c/x.py",
                "a/./b.py", "a//b.py", "ok.py:fluxo", "a\u0001.py"}) {
            assertEquals(Code.UNSAFE_PATH, refused(new ZipForge().file(n, "x").bytes()), n);
        }
        // pasta com nome que escapa também é recusada
        assertEquals(Code.UNSAFE_PATH, refused(new ZipForge().dir("../fora").file("ok.py", "x").bytes()));
    }

    @Test
    void localHeaderMustMatchCentralDirectory() throws Exception {
        assertEquals(Code.OVERLAPPING, refused(new ZipForge().add(ZipForge.e("x.py", "y").localName("z.py")).bytes()));
    }

    @Test
    void linksAndSpecialEntriesAreRefused() throws Exception {
        assertEquals(Code.SPECIAL_ENTRY, refused(new ZipForge().add(ZipForge.e("link.py", "/etc/passwd").unixMode(0120777)).bytes()), "ligação simbólica");
        assertEquals(Code.SPECIAL_ENTRY, refused(new ZipForge().add(ZipForge.e("fifo", "").unixMode(0010644)).bytes()), "FIFO");
        assertEquals(Code.SPECIAL_ENTRY, refused(new ZipForge().add(ZipForge.e("dev", "").unixMode(0020644)).bytes()), "dispositivo");
        assertEquals(Code.SPECIAL_ENTRY, refused(new ZipForge().add(ZipForge.e("junction", "").external(0x400 | 0x10)).bytes()), "reparse point do Windows");
        // arquivo comum e pasta comum do Unix passam
        assertEquals(1, ok(new ZipForge().add(ZipForge.e("a.py", "x").unixMode(0100644)).dir("pasta").bytes()).files().size());
    }

    @Test
    void collisionsAndWindowsNamesAreRefused() throws Exception {
        assertEquals(Code.DUPLICATE, refused(new ZipForge().file("a.py", "1").file("a.py", "2").bytes()));
        assertEquals(Code.DUPLICATE, refused(new ZipForge().file("Main.py", "1").file("main.PY", "2").bytes()), "só a caixa muda");
        assertEquals(Code.DUPLICATE, refused(new ZipForge().file("relat\u00f3rio.py", "1").file("relato\u0301rio.py", "2").bytes()), "NFC × NFD");
        assertEquals(Code.FILE_DIR_CONFLICT, refused(new ZipForge().file("x", "1").file("x/y.py", "2").bytes()));
        assertEquals(Code.FILE_DIR_CONFLICT, refused(new ZipForge().dir("X").file("x", "1").bytes()));
        for (String n : new String[] {"CON.py", "aux", "nul.txt", "pasta/COM1.py", "lpt9", "a.py.", "a.py ", "nome?.py", "x*.py", "a<b.py", "a|b.py", "a\"b.py"}) {
            assertEquals(Code.WINDOWS_NAME, refused(new ZipForge().file(n, "x").bytes()), n);
        }
        assertEquals(Code.TOO_DEEP, refused(new ZipForge().file("a/".repeat(16) + "x.py", "x").bytes()));
        assertEquals(Code.UNDECLARED_ENCODING, refused(new ZipForge().add(ZipForge.e("x", "1").flags(0).rawName(new byte[] {'a', (byte) 0x82, '.', 'p', 'y'})).bytes()));
    }

    @Test
    void spacesUnicodeAndNestedZipsAreReadNotOpened() throws Exception {
        byte[] inner = new ZipForge().file("dentro.py", "print(1)").bytes();
        SafeZip.Content c = ok(new ZipForge().file("pasta com espaço/relatório ção.py", "print('ok')\n").add(new ZipForge.E("anexo.zip", inner)).bytes());
        assertEquals(2, c.files().size());
        SafeZip.Item z = c.files().stream().filter(i -> i.name().equals("anexo.zip")).findFirst().orElseThrow();
        assertArrayEquals(inner, Files.readAllBytes(z.file()), "o ZIP interno é um arquivo comum, nunca descompactado");
        assertFalse(Files.exists(z.file().resolveSibling("dentro.py")));
        assertTrue(Files.isRegularFile(c.files().get(0).file()));
    }

    @Test
    void cancelStopsAndKeepsOnlyOperationFiles() throws Exception {
        Path f = tmp.resolve("c.zip");
        Files.write(f, new ZipForge().file("a.py", "1").file("b.py", "2").bytes());
        Path into = Files.createDirectories(tmp.resolve("cancel"));
        assertEquals(Code.CANCELLED, assertThrows(ImportProblem.class, () -> SafeZip.extract(f, into, ZipLimits.DEFAULT, () -> true)).code());
        assertTrue(Files.isRegularFile(f), "o ZIP original fica");
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new java.util.Random(7).nextBytes(b);
        return b;
    }
}
