package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.nekkora.agent.platform.PackageFingerprint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Vetores da regra {@code nekkora-agent/package-fingerprint/1} (contrato Platform–Agent sec. 6.4) calculados pela função
 * QUE O AGENT JÁ USA ({@code ExecutionPackage.fingerprint}), sem reimplementação. O canal leva os 64 hex sem o prefixo.
 */
class PackageFingerprintVectorTest {
    @TempDir
    Path dir;

    static final byte[] MAIN = "print('ola')\r\n".getBytes(StandardCharsets.UTF_8);
    static final byte[] DADOS = {(byte) 0xff, (byte) 0xfe, 0x00, 0x01};
    static final String MAIN_SHA = "sha256:dbccf933401a15cfe345e90891e20b5a05a495f552e4577b80c052276604e24f";
    static final String DADOS_SHA = "sha256:d2ad9277baaee14856d20ec2b21f87a0cb8a7f86c6ef090fd5a082b1e85135ac";
    static final byte[] MANIFEST = ("{\"schema\": \"nekkora-studio/codegen-manifest\", \"version\": \"1.4.0\", \"files\": {\"main.py\": \"" + MAIN_SHA
            + "\", \"dados.bin\": \"" + DADOS_SHA + "\"}}\r\n").getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("V1: vetor sintético do contrato (CRLF e binário) = cada6a64…")
    void syntheticVector() throws Exception {
        Files.write(dir.resolve("main.py"), MAIN);
        Files.write(dir.resolve("dados.bin"), DADOS);
        Files.write(dir.resolve("manifest.json"), MANIFEST);
        assertEquals(MAIN_SHA, ExecutionPackage.hash(MAIN));
        assertEquals(DADOS_SHA, ExecutionPackage.hash(DADOS));
        assertEquals("sha256:779965c156b7c0f0921285933008850b19d592ce990ba7dffb42b8dd22eae868", ExecutionPackage.hash(MANIFEST));
        Map<String, String> files = new LinkedHashMap<>();
        files.put("main.py", MAIN_SHA);
        files.put("dados.bin", DADOS_SHA);
        String fp = ExecutionPackage.fingerprint(ExecutionPackage.Kind.STUDIO_PYTHON, dir, files, ExecutionPackage.STUDIO_MANIFEST);
        assertEquals("sha256:cada6a642bc7001a314b513a9dd56a312b4ff7612149c0ee29d523e641dff828", fp);
        assertEquals("cada6a642bc7001a314b513a9dd56a312b4ff7612149c0ee29d523e641dff828", PackageFingerprint.wire(fp).orElseThrow());
        // não é o hash dos bytes de um artefato (sec. 6.4: as duas medidas não se comparam)
        String manifestBytes = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MANIFEST));
        assertNotEquals(manifestBytes, PackageFingerprint.wire(fp).orElseThrow());
    }

    @Test
    @DisplayName("V2: vetor real (pacote do Studio da7bb94 da COMPAT-001) = 5664f1a5…, quando o pacote está disponível nesta máquina")
    void realVector() throws Exception {
        String p = System.getenv("NEKKORA_REAL_PACKAGE");
        Assumptions.assumeTrue(p != null && Files.isRegularFile(Path.of(p, "manifest.json")), "NEKKORA_REAL_PACKAGE ausente (o pacote do Studio não é versionado)");
        ExecutionPackage pkg = ExecutionPackage.inspect(Path.of(p));
        assertEquals("sha256:5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d", pkg.fingerprint());
        assertEquals("5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d", PackageFingerprint.wire(pkg.fingerprint()).orElseThrow());
    }

    @Test
    @DisplayName("V3: só a forma sha256:<64 hex> vira impressão do canal")
    void wireForm() {
        assertEquals(java.util.Optional.empty(), PackageFingerprint.wire("cada6a642bc7001a314b513a9dd56a312b4ff7612149c0ee29d523e641dff828"));
        assertEquals(java.util.Optional.empty(), PackageFingerprint.wire("sha256:CADA6A642BC7001A314B513A9DD56A312B4FF7612149C0EE29D523E641DFF828"));
        assertEquals(java.util.Optional.empty(), PackageFingerprint.wire(null));
    }
}
