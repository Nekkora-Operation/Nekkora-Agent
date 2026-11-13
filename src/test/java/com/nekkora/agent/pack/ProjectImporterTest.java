package com.nekkora.agent.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.pack.ImportProblem.Code;
import com.nekkora.agent.pack.ImportedProject.Kind;
import com.nekkora.agent.pack.InclusionRules.Rule;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** AGENT-PACOTES-001 T01: cópia privada, raiz, formato e declarações do ZIP importado. */
class ProjectImporterTest {

    @TempDir
    Path tmp;

    ProjectImporter importer() {
        return new ProjectImporter(tmp.resolve("dados").resolve("importacao"), ZipLimits.DEFAULT);
    }

    @Test
    void codeProjectWithWrappingFolderKeepsStructureAndSuggestsOnly() throws Exception {
        Path zip = ZipForge.ofFolder(ZipForge.fixture("modular"), "meu-projeto/", tmp.resolve("modular.zip"),
                ZipForge.e(".env", "SENHA=nao-e-real"), ZipForge.e("__pycache__/x.pyc", "cache"), ZipForge.e(".venv/Scripts/python.txt", "venv"),
                ZipForge.e("credenciais-servico.json", "{}"), ZipForge.e("sub/nekkora-external.json", "{}"));
        ImportedProject p = importer().open(zip, SafeZip.Cancel.NEVER);
        assertEquals(Kind.CODE, p.kind());
        assertEquals("meu-projeto", p.rootFolder(), "a pasta externa única é mostrada como raiz");
        assertTrue(p.file("calculos/totais.py").isPresent(), "estrutura preservada, sem achatar");
        assertTrue(p.pythonFiles().containsAll(List.of("principal.py", "calculos/totais.py", "calculos/__init__.py", "setup.py")));
        assertTrue(p.suggestedEntry().isEmpty(), "sem main.py nem __main__.py e com vários .py na raiz: nenhuma sugestão");
        assertEquals(Rule.SECRET, p.file(".env").orElseThrow().rule());
        assertEquals(Rule.ENVIRONMENT, p.file("__pycache__/x.pyc").orElseThrow().rule());
        assertEquals(Rule.ENVIRONMENT, p.file(".venv/Scripts/python.txt").orElseThrow().rule());
        assertEquals(Rule.DATA, p.file("dados/exemplo.csv").orElseThrow().rule());
        assertEquals(Rule.INCLUDE_WARNED, p.file("credenciais-servico.json").orElseThrow().rule());
        assertTrue(p.notes().stream().anyMatch(n -> n.contains("subpasta")), "descritor em subpasta não escolhe formato");
        assertTrue(p.blockers().isEmpty());
        assertEquals(Declarations.DependencyStatus.PARTIAL, p.declarations().dependencyStatus(), "requirements.txt + setup.py: a pessoa confere");
        assertTrue(Files.isRegularFile(zip), "o ZIP de origem não é alterado");
        assertTrue(p.workDir().startsWith(tmp.resolve("dados").resolve("importacao")));
        importer().close(p);
        assertFalse(Files.exists(p.workDir()), "fechar apaga só a pasta da operação");
        assertTrue(Files.isRegularFile(zip));
    }

    @Test
    void minimalProjectSuggestsMainButNeverChoosesIt() throws Exception {
        ImportedProject p = importer().open(ZipForge.ofFolder(ZipForge.fixture("minimo"), "", tmp.resolve("m.zip")), SafeZip.Cancel.NEVER);
        assertEquals("", p.rootFolder());
        assertEquals("main.py", p.suggestedEntry().orElseThrow());
        assertEquals(Declarations.DependencyStatus.NOT_DECLARED, p.declarations().dependencyStatus(), "falta de declaração não é 'nenhuma'");
        assertTrue(p.declarations().python().isEmpty(), "Python não declarado não é escolhido pelo Agent");
    }

    @Test
    void declarationsAreReadAsTextOnly() throws Exception {
        ImportedProject d = importer().open(ZipForge.ofFolder(ZipForge.fixture("declarado"), "", tmp.resolve("d.zip")), SafeZip.Cancel.NEVER);
        assertEquals(Declarations.DependencyStatus.DECLARED, d.declarations().dependencyStatus());
        assertEquals(List.of("openpyxl==3.1.5", "python-dateutil==2.9.0.post0"), d.declarations().exact());
        assertEquals(">=3.11,<3.14", d.declarations().python().orElseThrow(), "requires-python do pyproject (sem dependências nele: não pede conferência)");
        ImportedProject v = importer().open(ZipForge.ofFolder(ZipForge.fixture("minimo"), "", tmp.resolve("v.zip"), ZipForge.e(".python-version", "3.12.4\n"),
                ZipForge.e(".gitignore", "*.pyc\n")), SafeZip.Cancel.NEVER);
        assertEquals("==3.12.*", v.declarations().python().orElseThrow(), ".python-version 3.12.4 → ==3.12.*");
        assertEquals(Rule.TOOLING, v.file(".python-version").orElseThrow().rule(), "arquivo de ferramenta: fora, com motivo");
        assertEquals(Rule.TOOLING, v.file(".gitignore").orElseThrow().rule());
        ImportedProject x = importer().open(ZipForge.ofFolder(ZipForge.fixture("dependencias"), "", tmp.resolve("x.zip")), SafeZip.Cancel.NEVER);
        assertEquals(Declarations.DependencyStatus.PARTIAL, x.declarations().dependencyStatus());
        assertEquals(List.of("numpy==1.26.4"), x.declarations().exact());
        assertEquals(2, x.declarations().unsupported().size(), "faixa e -r ficam como não aceitas, nunca descartadas");
        assertTrue(x.declarations().otherFiles().contains("pyproject.toml"));
        assertEquals(">=3.10,<3.14", x.declarations().python().orElseThrow(), "requires-python no formato aceito");
    }

    @Test
    void existingExternalPackageIsRecognizedNotRewritten() throws Exception {
        ImportedProject code = importer().open(ZipForge.ofFolder(ZipForge.fixture("minimo"), "", tmp.resolve("m.zip")), SafeZip.Cancel.NEVER);
        PackagePlan plan = new PackagePlan("minimo", "1.0.0", "main.py", ">=3.10,<3.14", List.of(), true, Set.of(), Set.of(), false);
        Path out = Files.createDirectories(tmp.resolve("saida"));
        Packager.Built b = Packager.build(code, plan, out, false, SafeZip.Cancel.NEVER);
        ImportedProject again = importer().open(b.zip(), SafeZip.Cancel.NEVER);
        assertEquals(Kind.EXTERNAL_PACKAGE, again.kind());
        assertEquals(b.fingerprint(), again.existing().fingerprint());
        assertEquals("1.0.0", again.existing().packageVersion());
        assertEquals(Code.ALREADY_PACKAGE, assertThrows(ImportProblem.class, () -> Packager.preview(again, plan)).code(), "sem regravar em silêncio");
    }

    @Test
    void studioAndLabPackagesAreRoutedNotConverted() throws Exception {
        Path studio = com.nekkora.agent.exec.ExecFixturesAccess.studioPackage(tmp.resolve("studio-src"));
        ImportedProject s = importer().open(ZipForge.ofFolder(studio, "pacote-studio/", tmp.resolve("s.zip")), SafeZip.Cancel.NEVER);
        assertEquals(Kind.STUDIO_PACKAGE, s.kind());
        assertEquals(ExecutionPackage.Kind.STUDIO_PYTHON, s.existing().kind());
        assertEquals(Code.NOT_CONVERTIBLE, assertThrows(ImportProblem.class, () -> Packager.preview(s, plan())).code());
        Path lab = com.nekkora.agent.exec.ExecFixturesAccess.labPackage(tmp.resolve("lab-src"));
        assertEquals(Kind.LAB_PACKAGE, importer().open(ZipForge.ofFolder(lab, "", tmp.resolve("l.zip")), SafeZip.Cancel.NEVER).kind());
    }

    @Test
    void invalidAndConflictingDescriptorsBlock() throws Exception {
        ImportedProject bad = importer().open(new ZipForge().file("main.py", "print(1)").file("nekkora-external.json", "{\"schema\":\"outra\"}").write(tmp.resolve("b.zip")),
                SafeZip.Cancel.NEVER);
        assertEquals(Kind.EXTERNAL_INVALID, bad.kind());
        assertEquals(ErrorCode.EXEC_PACKAGE_INVALID, bad.refusal().code());
        assertEquals(Code.EXTERNAL_INVALID, assertThrows(ImportProblem.class, () -> Packager.preview(bad, plan())).code());
        ImportedProject both = importer().open(new ZipForge().file("main.py", "print(1)").file("nekkora-external.json", "{}").file("manifest.json", "{}")
                .write(tmp.resolve("c.zip")), SafeZip.Cancel.NEVER);
        assertEquals(Kind.CONFLICT, both.kind());
        ImportedProject studioBroken = importer().open(new ZipForge().file("main.py", "print(1)")
                .file("manifest.json", "{\"schema\":\"nekkora-studio/codegen-manifest\",\"version\":\"1.0.0\"}").write(tmp.resolve("e.zip")), SafeZip.Cancel.NEVER);
        assertEquals(Kind.STUDIO_INVALID, studioBroken.kind());
        // manifest.json de OUTRA ferramenta: projeto de código, mas o nome é reservado na raiz do pacote
        ImportedProject other = importer().open(new ZipForge().file("main.py", "print(1)").file("manifest.json", "{\"name\":\"app web\"}")
                .write(tmp.resolve("f.zip")), SafeZip.Cancel.NEVER);
        assertEquals(Kind.CODE, other.kind());
        assertEquals(Code.RESERVED_ROOT_NAME, assertThrows(ImportProblem.class, () -> Packager.preview(other, plan())).code());
        PackagePlan withoutIt = new PackagePlan("p", "1.0.0", "main.py", ">=3.10,<3.14", List.of(), true, Set.of(), Set.of("manifest.json"), false);
        assertEquals(1, Packager.preview(other, withoutIt).files().size(), "deixar o arquivo fora resolve, por escolha da pessoa");
    }

    @Test
    void sourceChangedDuringCopyIsRefusedAndCleaned() throws Exception {
        Path zip = ZipForge.ofFolder(ZipForge.fixture("minimo"), "", tmp.resolve("m.zip"));
        ProjectImporter im = importer();
        im.afterCopy = () -> {
            try {
                Files.setLastModifiedTime(zip, FileTime.from(Instant.now().plusSeconds(60)));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
        assertEquals(Code.SOURCE_CHANGED, assertThrows(ImportProblem.class, () -> im.open(zip, SafeZip.Cancel.NEVER)).code());
        try (var s = Files.list(tmp.resolve("dados").resolve("importacao"))) {
            assertEquals(0, s.count(), "nenhuma sobra da operação");
        }
    }

    @Test
    void sourceChangedAfterReadBlocksBuild() throws Exception {
        Path zip = ZipForge.ofFolder(ZipForge.fixture("minimo"), "", tmp.resolve("m.zip"));
        ImportedProject p = importer().open(zip, SafeZip.Cancel.NEVER);
        Files.writeString(zip, "outro conteúdo", StandardOpenOption.APPEND);
        Path out = Files.createDirectories(tmp.resolve("saida"));
        assertEquals(Code.SOURCE_CHANGED, assertThrows(ImportProblem.class, () -> Packager.build(p, plan(), out, false, SafeZip.Cancel.NEVER)).code());
        try (var s = Files.list(out)) {
            assertEquals(0, s.count(), "versões não se misturam: nada gerado");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void lockedSourceIsReportedAsInUse() throws Exception {
        Path zip = ZipForge.ofFolder(ZipForge.fixture("minimo"), "", tmp.resolve("m.zip"));
        try (FileChannel ch = FileChannel.open(zip, StandardOpenOption.WRITE); var lock = ch.lock()) {
            assertEquals(Code.SOURCE_LOCKED, assertThrows(ImportProblem.class, () -> importer().open(zip, SafeZip.Cancel.NEVER)).code());
        }
    }

    @Test
    void cancelRemovesOperationFolder() throws Exception {
        Path zip = ZipForge.ofFolder(ZipForge.fixture("modular"), "", tmp.resolve("m.zip"));
        assertEquals(Code.CANCELLED, assertThrows(ImportProblem.class, () -> importer().open(zip, () -> true)).code());
        try (var s = Files.list(tmp.resolve("dados").resolve("importacao"))) {
            assertEquals(0, s.count());
        }
        assertTrue(Files.isRegularFile(zip));
    }

    @Test
    void staleOperationsAreCleanedOnlyInsideTheImportRoot() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("dados").resolve("importacao"));
        Path old = Files.createDirectories(root.resolve("op-antiga"));
        Files.writeString(old.resolve("x"), "x");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(ProjectImporter.STALE).minusSeconds(60)));
        Path recent = Files.createDirectories(root.resolve("op-recente"));
        Path other = Files.createDirectories(root.resolve("historico"));
        importer().open(ZipForge.ofFolder(ZipForge.fixture("minimo"), "", tmp.resolve("m.zip")), SafeZip.Cancel.NEVER);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent), "operação recente pode estar em uso por outra janela");
        assertTrue(Files.exists(other), "só pastas op-* são sobras");
    }

    @Test
    void namesOutsideContractAreReadButCannotBePackaged() throws Exception {
        ImportedProject p = importer().open(new ZipForge().file("main.py", "import util\n").file("relatórios/saída 1.py", "x = 1\n")
                .write(tmp.resolve("u.zip")), SafeZip.Cancel.NEVER);
        assertFalse(p.file("relatórios/saída 1.py").orElseThrow().contractSafe());
        ImportProblem e = assertThrows(ImportProblem.class, () -> Packager.preview(p, plan()));
        assertEquals(Code.NAME_OUTSIDE_CONTRACT, e.code());
        assertTrue(e.detail().orElseThrow().contains("saída 1.py"));
        PackagePlan without = new PackagePlan("p", "1.0.0", "main.py", ">=3.10,<3.14", List.of(), true, Set.of(), Set.of("relatórios/saída 1.py"), false);
        assertEquals(Set.of("main.py"), Packager.preview(p, without).files().keySet(), "a pessoa pode deixar o arquivo fora");
        ImportedProject q = importer().open(new ZipForge().file("pasta x/início.py", "print(1)").write(tmp.resolve("v.zip")), SafeZip.Cancel.NEVER);
        PackagePlan entry = new PackagePlan("p", "1.0.0", "pasta x/início.py", ">=3.10,<3.14", List.of(), true, Set.of(), Set.of(), false);
        assertEquals(Code.NAME_OUTSIDE_CONTRACT, assertThrows(ImportProblem.class, () -> Packager.preview(q, entry)).code());
    }

    @Test
    void noPythonFilesBlocks() throws Exception {
        ImportedProject p = importer().open(new ZipForge().file("leia-me.txt", "nada").write(tmp.resolve("t.zip")), SafeZip.Cancel.NEVER);
        assertEquals(Code.NO_PYTHON_FILES, p.blockers().get(0).code());
        assertNull(p.existing());
    }

    static PackagePlan plan() {
        return new PackagePlan("projeto", "1.0.0", "main.py", ">=3.10,<3.14", List.of(), true, Set.of(), Set.of(), false);
    }

    static String text(Path p) throws java.io.IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
