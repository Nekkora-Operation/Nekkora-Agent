package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.pack.ImportProblem;
import com.nekkora.agent.pack.ImportedProject;
import com.nekkora.agent.pack.PackagePlan;
import com.nekkora.agent.pack.Packager;
import com.nekkora.agent.pack.ProjectImporter;
import com.nekkora.agent.pack.SafeZip;
import com.nekkora.agent.pack.ZipLimits;
import com.nekkora.agent.pack.ZipStager;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-PACOTES-001 T03: o pacote gerado pelo assistente é preparado a partir do ZIP (sem a pessoa extrair) e executado pelo
 * supervisor de sempre, com Python REAL: aprovação pela impressão, início explícito, desfecho observado, pasta de saída e pasta
 * de trabalho conferidas com um script controlado. As fixtures são as da T01 (sem dados reais).
 */
class ZipPackageExecutionTest {
    @TempDir
    Path tmp;

    Path python;
    Path agentDir;
    Path out;
    ApprovalStore approvals;
    RunStore runs;
    ExecutionController controller;
    ZipStager stager;

    @BeforeEach
    void setUp() throws Exception {
        python = ExecFixtures.python();
        agentDir = tmp.resolve("dados").resolve("execucao");
        out = Files.createDirectories(tmp.resolve("saida"));
        approvals = new ApprovalStore(agentDir.resolve("aprovacoes.json"), AtomicWriter.DEFAULT);
        runs = new RunStore(agentDir.resolve("execucoes"), AtomicWriter.DEFAULT);
        controller = new ExecutionController(runs, approvals, new ExecFixtures.FakeSession(), Clock.systemUTC(), agentDir, Duration.ofMillis(100),
                Duration.ofMillis(200));
        stager = new ZipStager(ZipStager.defaultRoot(tmp.resolve("dados")));
    }

    @AfterEach
    void tearDown() {
        controller.close();
    }

    static Path fixture(String name) {
        return Path.of("src", "test", "resources", "agent-pacotes-001", "projetos", name).toAbsolutePath().normalize();
    }

    /** ZIP da pasta, com substituições de conteúdo (caminho → texto). */
    Path zipOf(Path folder, String prefix, String name, String... replace) throws Exception {
        Path z = tmp.resolve(name);
        try (OutputStream o = Files.newOutputStream(z); ZipOutputStream zo = new ZipOutputStream(o); Stream<Path> s = Files.walk(folder)) {
            for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                String rel = folder.relativize(f).toString().replace('\\', '/');
                byte[] data = Files.readAllBytes(f);
                for (int i = 0; i < replace.length; i += 2) if (replace[i].equals(rel)) data = replace[i + 1].getBytes(StandardCharsets.UTF_8);
                zo.putNextEntry(new ZipEntry(prefix + rel));
                zo.write(data);
                zo.closeEntry();
            }
        }
        return z;
    }

    /** O caminho do assistente: importar o ZIP de código, gerar o pacote. */
    Path generated(Path codeZip, String python, List<String> deps) throws Exception {
        ProjectImporter importer = new ProjectImporter(ProjectImporter.defaultRoot(tmp.resolve("dados")), ZipLimits.DEFAULT);
        ImportedProject p = importer.open(codeZip, SafeZip.Cancel.NEVER);
        Path pkgs = Files.createDirectories(tmp.resolve("pacotes"));
        Packager.Built b = Packager.build(p, new PackagePlan("relatorio-modular", "1.0.0", "principal.py", python, deps, true, Set.of(), Set.of(), false),
                pkgs, true, SafeZip.Cancel.NEVER);
        importer.close(p);
        return b.zip();
    }

    ExecutionRecord waitEnd() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (controller.running() && System.nanoTime() < deadline) Thread.sleep(50);
        assertFalse(controller.running(), "terminou no prazo do teste");
        return controller.current().orElseThrow().record();
    }

    @Test
    void generatedZipRunsWithoutManualExtractionAndOutputIsWhereTheAgentSays() throws Exception {
        Assumptions.assumeTrue(python != null, "sem Python de teste (NEKKORA_TEST_PYTHON): execução real não conferida");
        Path pkgZip = generated(zipOf(fixture("modular"), "modular/", "codigo.zip"), ">=3.10,<3.14", List.of());
        byte[] before = Files.readAllBytes(pkgZip);
        ExecutionPackage pk = stager.stage(pkgZip, SafeZip.Cancel.NEVER);
        assertEquals(ExecutionPackage.Kind.EXTERNAL_PYTHON, pk.kind());
        assertTrue(pk.dir().startsWith(tmp.resolve("dados").resolve("pacotes-zip")), "preparado numa pasta do Agent");
        assertEquals(0, runs.list().records().size(), "importar, gerar e preparar não criam execução");
        // sem aprovação: nada começa
        ExecutionController.Request req = new ExecutionController.Request(pk.dir(), pk.fingerprint(), python, null, out, null);
        assertEquals(ErrorCode.EXEC_NOT_APPROVED, assertThrows(ExecRefused.class, () -> controller.start(req)).code());
        // a pessoa aprova (ato dela; aqui pelo mesmo método da janela) e inicia
        approvals.approve(pk, Clock.systemUTC().instant());
        ExecutionController.Snapshot s = controller.start(req);
        ExecutionRecord r = waitEnd();
        assertEquals(ExecutionState.SUCCEEDED, r.state());
        assertEquals(ErrorCode.EXT_EXIT_ZERO_NO_SIGNAL, r.code(), "código 0 sem sinal: terminou sem erro sinalizado (não é sucesso de negócio)");
        assertEquals(0, r.exitCode());
        Path runOut = out.resolve(s.record().id().value());
        String result = Files.readString(runOut.resolve("resultado.json"));
        assertTrue(result.contains("\"total\": 12"), "o script leu config/ e importou calculos/ (estrutura preservada): " + result);
        Path copy = runs.dir(r.id()).resolve("pacote");
        assertTrue(result.contains(copy.toAbsolutePath().toString().replace("\\", "\\\\")), "a pasta de trabalho é a cópia conferida do pacote: " + result);
        assertTrue(Files.isRegularFile(copy.resolve("saida-relativa.txt")), "caminho relativo grava na cópia");
        assertFalse(Files.exists(runOut.resolve("saida-relativa.txt")), "e NÃO na pasta de saída");
        assertFalse(Files.exists(pk.dir().resolve("saida-relativa.txt")), "a pasta preparada não é alterada");
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(pkgZip)), "o ZIP do pacote não é alterado");
    }

    @Test
    void controlledErrorIsObservedAsFailure() throws Exception {
        Assumptions.assumeTrue(python != null, "sem Python de teste");
        Path pkgZip = generated(zipOf(fixture("modular"), "", "erro.zip", "config/parametros.json", "{\"valores\": [1], \"codigo_de_saida\": 3}\n"),
                ">=3.10,<3.14", List.of());
        ExecutionPackage pk = stager.stage(pkgZip, SafeZip.Cancel.NEVER);
        approvals.approve(pk, Clock.systemUTC().instant());
        controller.start(new ExecutionController.Request(pk.dir(), pk.fingerprint(), python, null, out, null));
        ExecutionRecord r = waitEnd();
        assertEquals(ExecutionState.FAILED, r.state());
        assertEquals(ErrorCode.EXT_EXIT_NONZERO, r.code());
        assertEquals(3, r.exitCode());
    }

    @Test
    void incompatiblePythonOrMissingDependencyStartsNothing() throws Exception {
        Assumptions.assumeTrue(python != null, "sem Python de teste");
        ExecutionPackage old = stager.stage(generated(zipOf(fixture("modular"), "", "py.zip"), "==3.2.*", List.of()), SafeZip.Cancel.NEVER);
        approvals.approve(old, Clock.systemUTC().instant());
        ExecRefused e = assertThrows(ExecRefused.class, () -> controller.start(new ExecutionController.Request(old.dir(), old.fingerprint(), python, null, out, null)));
        assertEquals(ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE, e.code(), "pacote externo: versão de Python fora do declarado (mesmo código do lançador)");
        ExecutionPackage dep = stager.stage(generated(zipOf(fixture("modular"), "", "dep.zip", "principal.py", "print('outro conteudo')\n"), ">=3.10,<3.14",
                List.of("pacote-inexistente-nekkora==9.9.9")), SafeZip.Cancel.NEVER);
        approvals.approve(dep, Clock.systemUTC().instant());
        ExecRefused d = assertThrows(ExecRefused.class, () -> controller.start(new ExecutionController.Request(dep.dir(), dep.fingerprint(), python, null, out, null)));
        assertEquals(ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE, d.code());
        // a sonda das dependências antes do início (a janela usa a mesma): diagnóstico na preparação
        assertEquals(ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE, assertThrows(ExecRefused.class, () -> PythonRuntime.requireDependencies(python, dep)).code());
        try (var s = Files.list(out)) {
            assertEquals(0, s.count(), "nenhuma pasta de saída: nada começou");
        }
    }

    @Test
    void stagingIsContentAddressedAndNeverTheShelf() throws Exception {
        Path pkgZip = generated(zipOf(fixture("modular"), "", "c.zip"), ">=3.10,<3.14", List.of());
        ExecutionPackage a = stager.stage(pkgZip, SafeZip.Cancel.NEVER);
        ExecutionPackage b = stager.stage(pkgZip, SafeZip.Cancel.NEVER);
        assertEquals(a.dir(), b.dir(), "mesmo conteúdo, mesma pasta");
        assertEquals(a.dir().getFileName().toString(), a.fingerprint().substring("sha256:".length()));
        PackageShelf shelf = new PackageShelf(PackageShelf.defaultRoot(tmp.resolve("dados")), AtomicWriter.DEFAULT);
        assertTrue(shelf.find(a.fingerprint()).isEmpty(), "preparar para a janela não põe o pacote ao alcance de pedidos da Platform");
        assertFalse(approvals.approved(a), "preparar não aprova");
        // ZIP adulterado (arquivo trocado depois da geração): o validador recusa
        Path tampered = tmp.resolve("adulterado.zip");
        try (var in = new java.util.zip.ZipFile(pkgZip.toFile()); OutputStream o = Files.newOutputStream(tampered); ZipOutputStream zo = new ZipOutputStream(o)) {
            for (ZipEntry e : java.util.Collections.list(in.entries())) {
                zo.putNextEntry(new ZipEntry(e.getName()));
                zo.write(e.getName().equals("principal.py") ? "print('trocado')\n".getBytes(StandardCharsets.UTF_8) : in.getInputStream(e).readAllBytes());
                zo.closeEntry();
            }
        }
        assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED, assertThrows(ExecRefused.class, () -> stager.stage(tampered, SafeZip.Cancel.NEVER)).code());
        // ZIP falso: recusado pelo leitor, antes do validador
        Path fake = Files.writeString(tmp.resolve("falso.zip"), "não é zip");
        assertEquals(ImportProblem.Code.NOT_ZIP, assertThrows(ImportProblem.class, () -> stager.stage(fake, SafeZip.Cancel.NEVER)).code());
        try (var s = Files.list(ZipStager.defaultRoot(tmp.resolve("dados")))) {
            assertEquals(1, s.count(), "só a pasta do pacote válido; nenhuma sobra de preparo");
        }
    }

    @Test
    void studioPackageZipIsStagedAsIs() throws Exception {
        Path studio = ExecFixtures.studioPackage(tmp.resolve("studio"), "single", "\"output\": \"--output\"", "");
        Path z = zipOf(studio, "projeto-studio/", "studio.zip");
        ExecutionPackage pk = stager.stage(z, SafeZip.Cancel.NEVER);
        assertEquals(ExecutionPackage.Kind.STUDIO_PYTHON, pk.kind(), "sem conversão para pacote externo");
    }
}
