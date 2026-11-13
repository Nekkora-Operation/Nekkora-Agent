package com.nekkora.agent.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-PACOTES-001 T01: as MESMAS fixtures passam pelo empacotador Python existente ({@code tools/external-package}) e pelo
 * empacotador do assistente; o descritor sai byte a byte igual (com o mesmo {@code generator}) e a impressão é a mesma. Não
 * há regra divergente. Precisa de um Python 3.10+ em {@code NEKKORA_TEST_PYTHON} (sem ele, o teste é pulado e o motivo
 * aparece no relatório). O empacotador Python só lê arquivos: nenhum código das fixtures é executado.
 */
class PackagerEquivalenceTest {

    @TempDir
    Path tmp;

    @ParameterizedTest
    @CsvSource({"modular,principal.py", "minimo,main.py", "declarado,app.py"})
    void javaAndPythonPackagersAgree(String fixture, String entry) throws Exception {
        String py = System.getenv("NEKKORA_TEST_PYTHON");
        Assumptions.assumeTrue(py != null && Files.isRegularFile(Path.of(py)), "NEKKORA_TEST_PYTHON ausente: equivalência com o empacotador Python não conferida");
        Path folder = ZipForge.fixture(fixture);
        Path out = Files.createDirectories(tmp.resolve("py"));
        List<String> cmd = new ArrayList<>(List.of(py, "-I", "-B", Path.of("tools", "external-package", "nekkora_empacotar.py").toAbsolutePath().toString(),
                folder.toString(), "--nome", fixture, "--versao", "2.3.4", "--entrada", entry, "--python", ">=3.10,<3.14", "--saida", out.toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), log);
        String pyFingerprint = log.lines().filter(l -> l.startsWith("impressao")).findFirst().orElseThrow().replaceAll(".*: ", "").strip();

        Path zip = ZipForge.ofFolder(folder, "", tmp.resolve(fixture + ".zip"));
        ImportedProject ip = new ProjectImporter(tmp.resolve("importacao"), ZipLimits.DEFAULT).open(zip, SafeZip.Cancel.NEVER);
        PackagePlan plan = new PackagePlan(fixture, "2.3.4", entry, ">=3.10,<3.14", ip.declarations().exact(), true, Set.of(), Set.of(), false);
        Packager.Preview java = Packager.preview(ip, plan, "nekkora-empacotar/1.0.0");
        assertArrayEquals(Files.readAllBytes(out.resolve(fixture + "-2.3.4.nekkora-external.json")), java.descriptor(), "descritor byte a byte igual");
        assertEquals("sha256:" + pyFingerprint, java.fingerprint(), "mesma impressão");
    }
}
