package com.nekkora.agent.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.PackageShelf;
import com.nekkora.agent.pack.ImportProblem.Code;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AGENT-PACOTES-001 T01: o pacote gerado é aceito pelo validador real, é determinístico e nunca fica pela metade. */
class PackagerTest {

    @TempDir
    Path tmp;

    @AfterEach
    void reset() {
        Packager.opener = Packager.Opener.FILES;
        Packager.beforeMove = () -> {};
    }

    ImportedProject open(String fixture, String prefix, ZipForge.E... extra) throws Exception {
        Path zip = ZipForge.ofFolder(ZipForge.fixture(fixture), prefix, tmp.resolve(fixture + "-" + System.nanoTime() + ".zip"), extra);
        return new ProjectImporter(tmp.resolve("dados").resolve("importacao"), ZipLimits.DEFAULT).open(zip, SafeZip.Cancel.NEVER);
    }

    static PackagePlan modular(String version) {
        return new PackagePlan("relatorio-modular", version, "principal.py", ">=3.10,<3.14", List.of(), true, Set.of(), Set.of(), false);
    }

    Path out() throws IOException {
        return Files.createDirectories(tmp.resolve("pacotes-" + System.nanoTime()));
    }

    @Test
    void generatedPackageIsAcceptedByTheRealValidators() throws Exception {
        ImportedProject p = open("modular", "modular/", ZipForge.e(".env", "SENHA=nao-e-real"), ZipForge.e("__pycache__/a.pyc", "x"));
        Packager.Built b = Packager.build(p, modular("1.0.0"), out(), false, SafeZip.Cancel.NEVER);
        assertEquals("relatorio-modular-1.0.0.zip", b.zip().getFileName().toString());
        // validador do Agent pelo caminho do ZIP (a mesma extração da CLI "pacote instalar")
        PackageShelf shelf = new PackageShelf(tmp.resolve("estante"), AtomicWriter.DEFAULT);
        ExecutionPackage pk = shelf.stage(b.zip());
        assertEquals(ExecutionPackage.Kind.EXTERNAL_PYTHON, pk.kind());
        assertEquals(b.fingerprint(), pk.fingerprint(), "a impressão calculada é a do validador");
        assertEquals("principal.py", pk.entry());
        assertEquals("1.0.0", pk.packageVersion());
        assertTrue(pk.files().containsKey("calculos/totais.py"), "módulos em subpasta preservados");
        assertTrue(pk.files().containsKey("config/parametros.json"));
        assertFalse(pk.files().containsKey(".env"), "credencial nunca entra");
        assertFalse(pk.files().keySet().stream().anyMatch(f -> f.startsWith("__pycache__")));
        assertFalse(pk.files().containsKey("dados/exemplo.csv"), "dados ficam fora por padrão");
        shelf.discard(pk);
    }

    @Test
    void dataCanBeIncludedByChoiceButCredentialsNever() throws Exception {
        ImportedProject p = open("modular", "", ZipForge.e(".env", "SENHA=nao-e-real"));
        PackagePlan withData = new PackagePlan("m", "1.0.0", "principal.py", ">=3.10,<3.14", List.of(), true, Set.of("dados/exemplo.csv"), Set.of(), false);
        assertTrue(Packager.preview(p, withData).files().containsKey("dados/exemplo.csv"));
        PackagePlan withEnv = new PackagePlan("m", "1.0.0", "principal.py", ">=3.10,<3.14", List.of(), true, Set.of(".env"), Set.of(), false);
        assertEquals(Code.FORBIDDEN_INCLUDED, assertThrows(ImportProblem.class, () -> Packager.preview(p, withEnv)).code());
        Packager.Preview pv = Packager.preview(p, modular("1.0.0"));
        assertTrue(pv.excluded().stream().anyMatch(x -> x.path().equals(".env") && x.rule() == InclusionRules.Rule.SECRET), "fora, com motivo mostrado");
        assertTrue(pv.excluded().stream().anyMatch(x -> x.path().equals("dados/exemplo.csv") && x.rule() == InclusionRules.Rule.DATA));
    }

    @Test
    void entryInSubfolderWithSiblingImports() throws Exception {
        ImportedProject p = open("modular", "");
        PackagePlan plan = new PackagePlan("ferramenta", "0.1.0", "calculos/totais.py", "==3.12.*", List.of(), true, Set.of(), Set.of(), false);
        Packager.Built b = Packager.build(p, plan, out(), false, SafeZip.Cancel.NEVER);
        assertEquals("calculos/totais.py", b.entry());
    }

    @Test
    void recompressedOrRedatedZipIsSameContentDifferentVersionChangesFingerprintOnly() throws Exception {
        ImportedProject a = open("modular", "");
        // mesmo conteúdo, ZIP montado de outro jeito (pasta externa e outra ordem/compressão): mesma identidade e mesma impressão
        Path other = new ZipForge().add(new ZipForge.E("x/config/parametros.json", Files.readAllBytes(ZipForge.fixture("modular").resolve("config/parametros.json"))).method(0))
                .add(new ZipForge.E("x/principal.py", Files.readAllBytes(ZipForge.fixture("modular").resolve("principal.py"))).method(0))
                .add(new ZipForge.E("x/calculos/totais.py", Files.readAllBytes(ZipForge.fixture("modular").resolve("calculos/totais.py"))))
                .add(new ZipForge.E("x/calculos/__init__.py", Files.readAllBytes(ZipForge.fixture("modular").resolve("calculos/__init__.py"))))
                .add(new ZipForge.E("x/requirements.txt", Files.readAllBytes(ZipForge.fixture("modular").resolve("requirements.txt"))))
                .add(new ZipForge.E("x/setup.py", Files.readAllBytes(ZipForge.fixture("modular").resolve("setup.py"))))
                .add(new ZipForge.E("x/ferramentas/conftest.py", Files.readAllBytes(ZipForge.fixture("modular").resolve("ferramentas/conftest.py"))))
                .add(new ZipForge.E("x/dados/exemplo.csv", Files.readAllBytes(ZipForge.fixture("modular").resolve("dados/exemplo.csv"))))
                .write(tmp.resolve("recompactado.zip"));
        ImportedProject b = new ProjectImporter(tmp.resolve("dados").resolve("importacao"), ZipLimits.DEFAULT).open(other, SafeZip.Cancel.NEVER);
        assertNotEquals(a.sourceSha256(), b.sourceSha256(), "os bytes dos ZIPs diferem");
        Packager.Preview pa = Packager.preview(a, modular("1.0.0"));
        Packager.Preview pb = Packager.preview(b, modular("1.0.0"));
        assertEquals(pa.contentIdentity(), pb.contentIdentity(), "recompactar não é conteúdo novo");
        assertEquals(pa.fingerprint(), pb.fingerprint());
        Packager.Preview v2 = Packager.preview(a, modular("1.0.1"));
        assertEquals(pa.contentIdentity(), v2.contentIdentity(), "a identidade de conteúdo não depende da versão declarada");
        assertNotEquals(pa.fingerprint(), v2.fingerprint(), "a impressão inclui o descritor (e a versão): por isso não serve para deduplicar");
        PackagePlan changedEntry = new PackagePlan("relatorio-modular", "1.0.0", "setup.py", ">=3.10,<3.14", List.of(), true, Set.of(), Set.of(), false);
        assertNotEquals(pa.contentIdentity(), Packager.preview(a, changedEntry).contentIdentity(), "outra entrada é outro conteúdo executável");
    }

    @Test
    void buildIsDeterministic() throws Exception {
        ImportedProject p = open("modular", "");
        Packager.Built x = Packager.build(p, modular("1.0.0"), out(), false, SafeZip.Cancel.NEVER);
        Packager.Built y = Packager.build(p, modular("1.0.0"), out(), false, SafeZip.Cancel.NEVER);
        assertArrayEquals(Files.readAllBytes(x.zip()), Files.readAllBytes(y.zip()));
        assertEquals(x.sha256(), y.sha256());
    }

    @Test
    void descriptorKeepsContractFieldsAndNoOrganization() throws Exception {
        ImportedProject p = open("declarado", "");
        PackagePlan plan = new PackagePlan("declarado", "1.0.0", "app.py", p.declarations().python().orElseThrow(), p.declarations().exact(), false, Set.of(),
                Set.of(), false);
        Packager.Preview pv = Packager.preview(p, plan);
        String d = new String(pv.descriptor(), StandardCharsets.UTF_8);
        assertTrue(d.startsWith("{\n  \"schema\": \"nekkora-agent/external-python-package\",\n  \"version\": \"1.0.0\",\n  \"name\": \"declarado\","), d);
        assertTrue(d.contains("\"dependencies\": [\n    \"openpyxl==3.1.5\",\n    \"python-dateutil==2.9.0.post0\"\n  ],"));
        for (String forbidden : List.of("organization", "organizacao", "workspace", "automation", "projeto", "ambiente")) {
            assertFalse(d.toLowerCase().contains("\"" + forbidden), "o manifesto não leva " + forbidden);
        }
    }

    @Test
    void fieldValidation() throws Exception {
        ImportedProject p = open("minimo", "");
        assertEquals(Code.NAME_INVALID, problem(p, new PackagePlan("meu projeto", "1.0.0", "main.py", ">=3.10", List.of(), true, Set.of(), Set.of(), false)));
        assertEquals(Code.NAME_INVALID, problem(p, new PackagePlan("relatório", "1.0.0", "main.py", ">=3.10", List.of(), true, Set.of(), Set.of(), false)));
        assertEquals(Code.VERSION_INVALID, problem(p, new PackagePlan("p", "1.0", "main.py", ">=3.10", List.of(), true, Set.of(), Set.of(), false)));
        assertEquals(Code.VERSION_INVALID, problem(p, new PackagePlan("p", "v1.0.0", "main.py", ">=3.10", List.of(), true, Set.of(), Set.of(), false)));
        assertEquals(Code.PYTHON_INVALID, problem(p, new PackagePlan("p", "1.0.0", "main.py", "3.12", List.of(), true, Set.of(), Set.of(), false)));
        assertEquals(Code.DEPENDENCY_INVALID, problem(p, new PackagePlan("p", "1.0.0", "main.py", ">=3.10", List.of("requests>=2"), true, Set.of(), Set.of(), false)));
        assertEquals(Code.DEPENDENCY_INVALID, problem(p, new PackagePlan("p", "1.0.0", "main.py", ">=3.10", List.of("Req==1.0", "req==1.0"), true, Set.of(), Set.of(), false)));
        assertEquals(Code.ENTRY_INVALID, problem(p, new PackagePlan("p", "1.0.0", "nao-existe.py", ">=3.10", List.of(), true, Set.of(), Set.of(), false)));
        assertEquals(Code.ENTRY_INVALID, problem(p, new PackagePlan("p", "1.0.0", null, ">=3.10", List.of(), true, Set.of(), Set.of(), false)));
        assertEquals(Code.DEPENDENCIES_UNCONFIRMED, problem(p, new PackagePlan("p", "1.0.0", "main.py", ">=3.10", List.of(), false, Set.of(), Set.of(), false)),
                "sem declaração, a lista vazia precisa ser confirmada pela pessoa");
    }

    private static Code problem(ImportedProject p, PackagePlan plan) {
        return assertThrows(ImportProblem.class, () -> Packager.preview(p, plan)).code();
    }

    @Test
    void existingTargetNeedsConsentAndSourceIsNeverOverwritten() throws Exception {
        ImportedProject p = open("minimo", "");
        PackagePlan plan = new PackagePlan("minimo", "1.0.0", "main.py", ">=3.10,<3.14", List.of(), true, Set.of(), Set.of(), false);
        Path out = out();
        Path existing = Files.writeString(out.resolve("minimo-1.0.0.zip"), "versão anterior da pessoa");
        assertEquals(Code.TARGET_EXISTS, assertThrows(ImportProblem.class, () -> Packager.build(p, plan, out, false, SafeZip.Cancel.NEVER)).code());
        assertEquals("versão anterior da pessoa", Files.readString(existing), "sem consentimento nada é substituído");
        Packager.Built b = Packager.build(p, plan, out, true, SafeZip.Cancel.NEVER);
        assertEquals(existing.toAbsolutePath(), b.zip());
        assertEquals(1, leftovers(out).size(), "só o pacote, nenhum temporário");
        // gravar sobre o ZIP de origem: recusado mesmo com consentimento
        Path srcDir = Files.createDirectories(tmp.resolve("origem"));
        Path src = ZipForge.ofFolder(ZipForge.fixture("minimo"), "", srcDir.resolve("minimo-1.0.0.zip"));
        ImportedProject q = new ProjectImporter(tmp.resolve("dados").resolve("importacao"), ZipLimits.DEFAULT).open(src, SafeZip.Cancel.NEVER);
        byte[] before = Files.readAllBytes(src);
        assertEquals(Code.SAME_AS_SOURCE, assertThrows(ImportProblem.class, () -> Packager.build(q, plan, srcDir, true, SafeZip.Cancel.NEVER)).code());
        assertArrayEquals(before, Files.readAllBytes(src));
    }

    @Test
    void diskFullLeavesNothingMarkedReady() throws Exception {
        ImportedProject p = open("modular", "");
        Path out = out();
        Packager.opener = tmpFile -> new FilterOutputStream(Files.newOutputStream(tmpFile)) {
            long n;

            @Override
            public void write(int b) throws IOException {
                if (++n > 200) throw new IOException("There is not enough space on the disk");
                super.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                for (int i = 0; i < len; i++) write(b[off + i]);
            }
        };
        assertEquals(Code.NO_SPACE, assertThrows(ImportProblem.class, () -> Packager.build(p, modular("1.0.0"), out, false, SafeZip.Cancel.NEVER)).code());
        assertEquals(List.of(), leftovers(out), "nem pacote, nem temporário");
    }

    @Test
    void fileInUseOnWriteIsReportedAndNothingIsLeft() throws Exception {
        ImportedProject p = open("minimo", "");
        Path out = out();
        Packager.opener = f -> {
            throw new java.nio.file.AccessDeniedException(f.toString());
        };
        PackagePlan plan = new PackagePlan("minimo", "1.0.0", "main.py", ">=3.10,<3.14", List.of(), true, Set.of(), Set.of(), false);
        assertEquals(Code.WRITE_FAILED, assertThrows(ImportProblem.class, () -> Packager.build(p, plan, out, false, SafeZip.Cancel.NEVER)).code());
        assertEquals(List.of(), leftovers(out));
    }

    @Test
    void cancelBeforeRenameLeavesNothing() throws Exception {
        ImportedProject p = open("modular", "");
        Path out = out();
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        // o cancelamento chega depois da conferência do arquivo gravado: a troca de nome não acontece
        Packager.beforeMove = () -> cancel.set(true);
        Packager.Preview pv = Packager.preview(p, modular("1.0.0"));
        assertEquals(Code.CANCELLED, assertThrows(ImportProblem.class, () -> Packager.write(p, pv, out, false, cancel::get)).code());
        assertEquals(List.of(), leftovers(out));
    }

    @Test
    void nothingImportedIsExecuted() throws Exception {
        long children = ProcessHandle.current().children().count();
        ImportedProject p = open("modular", "");
        Path out = out();
        Packager.Built b = Packager.build(p, modular("1.0.0"), out, false, SafeZip.Cancel.NEVER);
        assertEquals(children, ProcessHandle.current().children().count(), "nenhum processo foi criado");
        try (var s = Files.walk(tmp)) {
            assertTrue(s.noneMatch(f -> f.getFileName().toString().equals("SENTINELA-EXECUTOU.txt")), "setup.py e conftest.py não rodaram");
        }
        assertTrue(Files.isRegularFile(b.zip()));
    }

    static List<Path> leftovers(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.toList();
        }
    }

    static OutputStream unused() {
        return OutputStream.nullOutputStream();
    }
}
