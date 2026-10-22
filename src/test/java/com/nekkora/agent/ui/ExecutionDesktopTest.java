package com.nekkora.agent.ui;

import static com.nekkora.agent.ui.DesktopHarness.capture;
import static com.nekkora.agent.ui.DesktopHarness.texts;
import static com.nekkora.agent.ui.DesktopHarness.trayIconCount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.os.TrayController;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DESKTOP-001 na janela real (sessão interativa do Windows): "Execução local" só habilita Iniciar com todas as escolhas;
 * processo de laboratório real até o fim; execução longa acompanhada (estado, tempo, PID), janela fechada sem cancelar,
 * cancelamento; a janela principal mostra a linha de estado. Os seletores de arquivo do sistema são o único passo
 * substituído (as escolhas entram pelos mesmos métodos que eles chamam).
 */
@Tag("desktop")
class ExecutionDesktopTest {
    @TempDir
    Path tmp;

    private DesktopHarness h;

    @AfterEach
    void tearDown() throws Exception {
        // uma asserção que falhou no meio deixaria a execução ativa: a saída pediria confirmação (diálogo modal)
        if (h != null) h.app.context().execution().close();
        if (h != null) h.close();
        assertEquals(0, trayIconCount(), "sair remove o ícone");
    }

    static Path python() {
        String env = System.getenv("NEKKORA_TEST_PYTHON");
        if (env != null && !env.isBlank() && Files.isRegularFile(Path.of(env))) return Path.of(env).toAbsolutePath();
        Path p = Path.of("..", "Nekkora-RPA-Framework", ".venv", "Scripts", "python.exe").toAbsolutePath().normalize();
        return Files.isRegularFile(p) ? p : null;
    }

    static Path labPackage(Path dir, String entry) throws Exception {
        Files.createDirectories(dir);
        Path scripts = Path.of("tools", "lab", "scripts");
        Map<String, String> files = new LinkedHashMap<>();
        for (String f : List.of(entry, "lab_comum.py")) {
            Files.copy(scripts.resolve(f), dir.resolve(f));
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readString(dir.resolve(f), StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
            files.put(f, "sha256:" + java.util.HexFormat.of().formatHex(d));
        }
        StringBuilder b = new StringBuilder("{\"schema\": \"nekkora-agent/lab-package\", \"version\": \"1.0.0\", \"name\": \"Laboratorio\", \"entry\": \"")
                .append(entry).append("\", \"files\": {");
        int n = 0;
        for (Map.Entry<String, String> e : files.entrySet()) b.append(n++ == 0 ? "" : ", ").append('"').append(e.getKey()).append("\": \"").append(e.getValue()).append('"');
        Files.writeString(dir.resolve(ExecutionPackage.LAB_DESCRIPTOR), b.append("}}"), StandardCharsets.UTF_8);
        return dir;
    }

    private void choose(Runnable r) throws Exception {
        h.run(r);
        h.waitFor(() -> !h.app.executionBusy(), "escolha conferida");
    }

    private ExecutionDialog dialog() throws Exception {
        return h.edt(h.app::executionDialog);
    }

    @Test
    @DisplayName("Iniciar só com tudo escolhido; sucesso real; execução longa acompanhada, janela fechada não cancela; cancelar")
    void janelaDeExecucao() throws Exception {
        Path py = python();
        Assumptions.assumeTrue(py != null, "sem Python neste ambiente");
        Path ok = labPackage(tmp.resolve("pacote-ok"), "lab_sucesso.py");
        Path slow = labPackage(tmp.resolve("pacote-lento"), "lab_lento.py");
        Path out = Files.createDirectories(tmp.resolve("saida"));
        h = DesktopHarness.start(tmp.resolve("dados"), AgentDesktopTest.ENV, new String[0], TrayController.SYSTEM);
        MainWindow w = h.window();
        assertTrue(h.edt(() -> texts(w.getRootPane())).contains("Nenhuma execução nesta sessão do Agent."));
        h.later(() -> w.executionButton().doClick());
        h.waitFor(() -> h.app.executionDialog() != null && h.app.executionDialog().isShowing(), "janela Execução local");
        ExecutionDialog d = dialog();
        h.waitFor(() -> h.app.executionSetup().session() != null, "sessão verificada");
        h.settle();
        assertFalse(h.edt(() -> d.startButton().isEnabled()), "nada escolhido: Iniciar desabilitado");
        assertTrue(h.edt(() -> h.app.executionSetup().session().usable()), "sessão interativa real, tela desbloqueada");
        capture(d, "D01-execucao-vazia");

        choose(() -> h.app.selectPackage(ok));
        assertFalse(h.edt(() -> d.startButton().isEnabled()), "pacote não aprovado");
        assertTrue(h.edt(() -> d.approveButton().isEnabled()));
        assertTrue(h.edt(() -> texts(d.getRootPane())).stream().anyMatch(t -> t.equals("Aprovação: não aprovado")));
        choose(h.app::approveConfirmed);
        choose(() -> h.app.selectPython(py));
        assertFalse(h.edt(() -> d.startButton().isEnabled()), "sem pasta de saída");
        choose(() -> h.app.selectOutput(out));
        h.settle();
        assertTrue(h.edt(() -> d.startButton().isEnabled()), "tudo escolhido: Iniciar habilitado");
        List<String> t = h.edt(() -> texts(d.getRootPane()));
        assertTrue(t.stream().anyMatch(x -> x.equals("Aprovação: aprovado nesta máquina")) && t.stream().anyMatch(x -> x.contains("Processo de laboratório")), t.toString());
        assertFalse(t.stream().anyMatch(x -> x.contains(tmp.toString())), "a tela não mostra caminhos completos");
        capture(d, "D02-execucao-pronta-para-iniciar");

        h.run(() -> d.startButton().doClick());
        h.waitFor(() -> h.app.context().execution().current().map(s -> !s.running() && s.record().state().terminal()).orElse(false), "execução terminou");
        h.settle();
        assertEquals(ExecutionState.SUCCEEDED, h.app.context().execution().current().orElseThrow().record().state());
        assertTrue(h.edt(() -> texts(d.getRootPane())).stream().anyMatch(x -> x.equals("Estado: Concluída com sucesso")));
        assertTrue(h.edt(w::executionText).startsWith("Concluída com sucesso"), h.edt(w::executionText));
        capture(d, "D03-execucao-concluida");

        // execução longa: acompanhada, janela fechada sem cancelar, reaberta, cancelada
        choose(() -> h.app.selectPackage(slow));
        choose(h.app::approveConfirmed);
        h.settle();
        h.run(() -> d.startButton().doClick());
        h.waitFor(() -> h.app.context().execution().running(), "em andamento");
        Thread.sleep(1500);
        h.settle();
        assertFalse(h.edt(() -> d.startButton().isEnabled()), "durante a execução não inicia outra");
        assertTrue(h.edt(() -> d.cancelButton().isEnabled()));
        assertTrue(h.edt(w::executionText).matches("Em andamento · \\d+ s · PID \\d+"), h.edt(w::executionText));
        capture(d, "D04-execucao-em-andamento");
        h.run(d::dispose);
        h.waitFor(() -> h.app.executionDialog() == null, "janela fechada");
        Thread.sleep(800);
        assertTrue(h.app.context().execution().running(), "fechar a janela não cancela");
        h.later(() -> w.executionButton().doClick());
        h.waitFor(() -> h.app.executionDialog() != null && h.app.executionDialog().isShowing(), "reaberta");
        assertTrue(h.edt(() -> texts(h.app.executionDialog().getRootPane())).stream().anyMatch(x -> x.equals("Estado: Em andamento")));
        assertTrue(h.app.context().execution().cancel()); // a confirmação do botão é o diálogo do sistema; a ação é a mesma
        h.waitFor(() -> !h.app.context().execution().running(), "cancelada");
        h.settle();
        assertEquals(ExecutionState.CANCELLED, h.app.context().execution().current().orElseThrow().record().state());
        assertTrue(h.edt(w::executionText).startsWith("Cancelada"), h.edt(w::executionText));
        capture(dialog(), "D05-execucao-cancelada");
        capture(w, "D06-janela-principal-apos-cancelar");
        ExecutionDialog last = dialog();
        h.run(last::dispose);
    }
}
