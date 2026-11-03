package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-PYTHON-001 (Marco 2): pacote Python EXTERNO. O vetor sintético é o MESMO do PackageInspectorExternalTest da API e da
 * implementação Python independente do gerador ({@code tools/external-package/nekkora_empacotar.py}): três implementações,
 * um valor. A regra /1 do Studio não muda (o vetor dela continua no PackageFingerprintVectorTest).
 */
class ExternalPackageTest {
    static final String MAIN = "sha256:dbccf933401a15cfe345e90891e20b5a05a495f552e4577b80c052276604e24f";
    static final String BIN = "sha256:d2ad9277baaee14856d20ec2b21f87a0cb8a7f86c6ef090fd5a082b1e85135ac";
    static final String FINGERPRINT = "sha256:e71ca1e0aedd2842d09809d5634134f3f23fb4d2ca0c5d90c370bb15474595f9";

    @TempDir
    Path tmp;

    static String descriptor(String entry, String deps, String files) {
        return "{\"schema\": \"nekkora-agent/external-python-package\", \"version\": \"1.0.0\", \"name\": \"vetor\", \"packageVersion\": \"1.0.0\", "
                + "\"entry\": \"" + entry + "\", \"python\": \">=3.10,<3.14\", \"dependencies\": " + deps + ", \"files\": " + files + "}\r\n";
    }

    static final String FILES = "{\"main.py\": \"" + MAIN + "\", \"util/x.bin\": \"" + BIN + "\"}";

    Path vector(String name, String descriptor) throws Exception {
        Path d = Files.createDirectories(tmp.resolve(name));
        Files.writeString(d.resolve("nekkora-external.json"), descriptor, StandardCharsets.UTF_8);
        Files.writeString(d.resolve("main.py"), "print('ola')\r\n", StandardCharsets.UTF_8);
        Files.createDirectories(d.resolve("util"));
        Files.write(d.resolve("util").resolve("x.bin"), new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01});
        return d;
    }

    @Test
    void vetorSinteticoIgualAoDaApiEDoGerador() throws Exception {
        ExecutionPackage p = ExecutionPackage.inspect(vector("v", descriptor("main.py", "[]", FILES)));
        assertEquals(ExecutionPackage.Kind.EXTERNAL_PYTHON, p.kind());
        assertEquals(FINGERPRINT, p.fingerprint());
        assertEquals("nekkora-agent/external-python-fingerprint/1", ExecutionPackage.ruleOf(p.kind()));
        assertEquals("main.py", p.entry());
        assertEquals(">=3.10,<3.14", p.pythonRequirement());
        assertEquals("1.0.0", p.packageVersion());
        // a mesma lista sob a regra /1 dá outra impressão: um pacote não passa por outro tipo
        assertNotEquals(FINGERPRINT, ExecutionPackage.fingerprint(ExecutionPackage.Kind.LAB_GENERIC, p.dir(), p.files(), "nekkora-external.json"));
    }

    @Test
    void recusasDoPacoteExterno() throws Exception {
        Path changed = vector("alterado", descriptor("main.py", "[]", FILES));
        Files.writeString(changed.resolve("main.py"), "print('outro')\n");
        assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(changed)).code());
        assertEquals(ErrorCode.EXEC_PACKAGE_UNSAFE,
                assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(vector("fora", descriptor("../fora.py", "[]", FILES)))).code());
        assertEquals(ErrorCode.EXEC_PACKAGE_INVALID,
                assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(vector("dep", descriptor("main.py", "[\"requests>=2\"]", FILES)))).code());
        Path venv = vector("venv", descriptor("main.py", "[]", "{\"main.py\": \"" + MAIN + "\", \"venv/pyvenv.cfg\": \"" + BIN + "\"}"));
        Files.createDirectories(venv.resolve("venv"));
        Files.write(venv.resolve("venv").resolve("pyvenv.cfg"), new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01});
        assertEquals(ErrorCode.EXEC_PACKAGE_UNSAFE, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(venv)).code());
        Path both = vector("ambos", descriptor("main.py", "[]", FILES));
        Files.writeString(both.resolve("manifest.json"), "{}");
        assertEquals(ErrorCode.EXEC_PACKAGE_INVALID, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(both)).code());
    }

    @Test
    void faixaDePython() {
        assertTrue(ExecutionPackage.pythonSatisfies(">=3.10,<3.14", 3, 12));
        assertFalse(ExecutionPackage.pythonSatisfies(">=3.10,<3.14", 3, 14));
        assertFalse(ExecutionPackage.pythonSatisfies(">=3.10,<3.14", 3, 9));
        assertTrue(ExecutionPackage.pythonSatisfies("==3.12.*", 3, 12));
        assertFalse(ExecutionPackage.pythonSatisfies("==3.12.*", 3, 13));
        assertTrue(ExecutionPackage.pythonSatisfies("~=3.11", 3, 13));
        assertFalse(ExecutionPackage.pythonSatisfies("~=3.11", 4, 0));
    }

    @Test
    void prateleiraExtraiZipSemConfiancaEFixaPelaImpressao() throws Exception {
        Path src = vector("src", descriptor("main.py", "[]", FILES));
        Path zip = tmp.resolve("pacote.zip");
        try (OutputStream o = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(o)) {
            for (String rel : new String[] {"nekkora-external.json", "main.py", "util/x.bin"}) {
                z.putNextEntry(new ZipEntry("relatorio/" + rel));
                z.write(Files.readAllBytes(src.resolve(rel)));
                z.closeEntry();
            }
        }
        PackageShelf shelf = new PackageShelf(tmp.resolve("pacotes"), AtomicWriter.DEFAULT);
        ExecutionPackage staged = shelf.stage(zip);
        assertEquals(FINGERPRINT, staged.fingerprint());
        PackageShelf.Entry e = shelf.install(staged, Path.of("C:/python/python.exe"), tmp, Instant.now());
        assertEquals(tmp.resolve("pacotes").resolve(FINGERPRINT.substring(7)), e.dir());
        assertTrue(shelf.find(FINGERPRINT).isPresent());
        // ZIP com caminho que sobe de pasta: recusado, nada fica
        Path evil = tmp.resolve("mau.zip");
        try (OutputStream o = Files.newOutputStream(evil); ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry("../fora.txt"));
            z.write(1);
            z.closeEntry();
        }
        assertEquals(ErrorCode.EXEC_PACKAGE_UNSAFE, assertThrows(ExecRefused.class, () -> shelf.stage(evil)).code());
        assertFalse(Files.exists(tmp.resolve("fora.txt")));
    }
}
