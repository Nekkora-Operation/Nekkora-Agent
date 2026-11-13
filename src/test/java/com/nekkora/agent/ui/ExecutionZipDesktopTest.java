package com.nekkora.agent.ui;

import static com.nekkora.agent.ui.DesktopHarness.capture;
import static com.nekkora.agent.ui.DesktopHarness.texts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.os.TrayController;
import com.nekkora.agent.pack.ImportedProject;
import com.nekkora.agent.pack.PackagePlan;
import com.nekkora.agent.pack.Packager;
import com.nekkora.agent.pack.ProjectImporter;
import com.nekkora.agent.pack.SafeZip;
import com.nekkora.agent.pack.ZipLimits;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-PACOTES-001 T03 na janela real: o pacote em ZIP gerado pelo assistente vai para a "Execução local" sem a pessoa extrair
 * nada; aprovação, Python, saída e início continuam explícitos; a tela explica onde o script grava. O seletor de arquivo do
 * sistema é o único passo substituído (o ZIP entra pelo mesmo método que ele chama). A aprovação, aqui, é chamada pelo método
 * da janela (no aceite integrado ela é da pessoa).
 */
@Tag("desktop")
class ExecutionZipDesktopTest {
    @TempDir
    Path tmp;

    private DesktopHarness h;

    @AfterEach
    void tearDown() throws Exception {
        if (h != null) h.app.context().execution().close();
        if (h != null) h.close();
    }

    private void choose(Runnable r) throws Exception {
        h.run(r);
        h.waitFor(() -> !h.app.executionBusy(), "escolha conferida");
    }

    private Path generatedZip() throws Exception {
        Path folder = Path.of("src", "test", "resources", "agent-pacotes-001", "projetos", "modular").toAbsolutePath();
        Path code = tmp.resolve("codigo.zip");
        try (OutputStream o = Files.newOutputStream(code); ZipOutputStream z = new ZipOutputStream(o); Stream<Path> s = Files.walk(folder)) {
            for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                z.putNextEntry(new ZipEntry(folder.relativize(f).toString().replace('\\', '/')));
                z.write(Files.readAllBytes(f));
                z.closeEntry();
            }
        }
        ProjectImporter im = new ProjectImporter(tmp.resolve("imp"), ZipLimits.DEFAULT);
        ImportedProject p = im.open(code, SafeZip.Cancel.NEVER);
        Packager.Built b = Packager.build(p, new PackagePlan("relatorio-modular", "1.0.0", "principal.py", ">=3.10,<3.14", List.of(), true, Set.of(), Set.of(),
                false), Files.createDirectories(tmp.resolve("pacotes")), false, SafeZip.Cancel.NEVER);
        im.close(p);
        return b.zip();
    }

    @Test
    void zipFromTheAssistantIsPreparedAndRunWithoutExtracting() throws Exception {
        Path py = ExecutionDesktopTest.python();
        Assumptions.assumeTrue(py != null, "sem Python neste ambiente");
        Path zip = generatedZip();
        Path out = Files.createDirectories(tmp.resolve("saida"));
        h = DesktopHarness.start(tmp.resolve("dados"), AgentDesktopTest.ENV, new String[0], TrayController.SYSTEM);
        // como o "Preparar execução" do assistente: a janela abre com o ZIP já escolhido
        h.later(() -> h.app.openExecution(zip));
        h.waitFor(() -> h.app.executionDialog() != null && h.app.executionDialog().isShowing(), "janela Execução local");
        ExecutionDialog d = h.edt(h.app::executionDialog);
        h.waitFor(() -> !h.app.executionBusy() && h.app.executionSetup().pkg() != null, "pacote do ZIP conferido");
        h.waitFor(() -> h.app.executionSetup().session() != null, "sessão verificada");
        assertEquals(ExecutionDialog.ZIP_LABEL, h.edt(() -> d.packageZipButton().getText()));
        assertEquals(ExecutionDialog.FOLDER_LABEL, h.edt(() -> d.packageButton().getText()), "a pasta continua disponível, identificada como pasta");
        assertEquals(ExecutionPackage.Kind.EXTERNAL_PYTHON, h.edt(() -> h.app.executionSetup().pkg().kind()));
        assertTrue(h.edt(() -> h.app.executionSetup().pkg().dir()).startsWith(tmp.resolve("dados").resolve("pacotes-zip")), "preparado pelo Agent");
        assertFalse(h.edt(() -> d.startButton().isEnabled()), "não aprovado: nada começa");
        List<String> t = h.edt(() -> texts(d.getRootPane()));
        assertTrue(t.stream().anyMatch(x -> x.equals("Aprovação: não aprovado")), t.toString());
        assertTrue(t.contains(com.nekkora.agent.exec.ExecutionSetup.EXTERNAL_OUTPUT_LABEL), "a tela diz onde o script grava de fato: " + t);
        capture(d, "PAC-T03-execucao-zip-preparada");
        choose(h.app::approveConfirmed);
        choose(() -> h.app.selectPython(py));
        choose(() -> h.app.selectOutput(out));
        h.settle();
        assertTrue(h.edt(() -> d.startButton().isEnabled()), "aprovado, Python e saída escolhidos: Iniciar habilitado");
        assertFalse(h.edt(() -> texts(d.getRootPane())).stream().anyMatch(x -> x.contains(tmp.toString())), "sem caminhos completos");
        h.run(() -> d.startButton().doClick());
        h.waitFor(() -> h.app.context().execution().current().map(s -> !s.running() && s.record().state().terminal()).orElse(false), "execução terminou");
        h.settle();
        var r = h.app.context().execution().current().orElseThrow().record();
        assertEquals(ExecutionState.SUCCEEDED, r.state());
        assertTrue(Files.isRegularFile(out.resolve(r.id().value()).resolve("resultado.json")), "o script gravou em NEKKORA_SAIDA");
        capture(d, "PAC-T03-execucao-zip-concluida");
    }

    @Test
    void missingDependencyIsShownBeforeStarting() throws Exception {
        Path py = ExecutionDesktopTest.python();
        Assumptions.assumeTrue(py != null, "sem Python neste ambiente");
        Path folder = Path.of("src", "test", "resources", "agent-pacotes-001", "projetos", "minimo").toAbsolutePath();
        Path code = tmp.resolve("minimo.zip");
        try (OutputStream o = Files.newOutputStream(code); ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry("main.py"));
            z.write(Files.readAllBytes(folder.resolve("main.py")));
            z.closeEntry();
        }
        ProjectImporter im = new ProjectImporter(tmp.resolve("imp"), ZipLimits.DEFAULT);
        ImportedProject p = im.open(code, SafeZip.Cancel.NEVER);
        Path zip = Packager.build(p, new PackagePlan("minimo", "1.0.0", "main.py", ">=3.10,<3.14", List.of("pacote-inexistente-nekkora==9.9.9"), true, Set.of(),
                Set.of(), false), Files.createDirectories(tmp.resolve("pacotes")), false, SafeZip.Cancel.NEVER).zip();
        im.close(p);
        h = DesktopHarness.start(tmp.resolve("dados"), AgentDesktopTest.ENV, new String[0], TrayController.SYSTEM);
        h.later(() -> h.app.openExecution(zip));
        h.waitFor(() -> h.app.executionDialog() != null && h.app.executionDialog().isShowing(), "janela Execução local");
        ExecutionDialog d = h.edt(h.app::executionDialog);
        h.waitFor(() -> !h.app.executionBusy() && h.app.executionSetup().pkg() != null, "pacote conferido");
        choose(h.app::approveConfirmed);
        choose(() -> h.app.selectPython(py));
        Path out = Files.createDirectories(tmp.resolve("saida"));
        choose(() -> h.app.selectOutput(out));
        h.settle();
        assertEquals(com.nekkora.agent.core.ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE, h.edt(() -> h.app.executionSetup().pythonProblem()),
                "dependência ausente aparece na preparação");
        assertFalse(h.edt(() -> d.startButton().isEnabled()), "nada começa");
        capture(d, "PAC-T03-dependencia-ausente");
    }
}
