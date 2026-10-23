package com.nekkora.agent.ui;

import static com.nekkora.agent.ui.DesktopHarness.capture;
import static com.nekkora.agent.ui.DesktopHarness.texts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.exec.ObsStudioPackage;
import com.nekkora.agent.obs.ActivityEvent;
import com.nekkora.agent.os.TrayController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OBS-001 na janela real: aba "Atividade" acompanha uma execução em andamento (etapas aparecendo durante, EDT
 * responsiva), sentinelas nunca aparecem na tela, processo genérico mostra "Etapas não disponíveis", filtros, e o
 * histórico continua consultável depois de reiniciar o Agent. Capturas em {@code target/desktop-evidence}.
 */
@Tag("desktop")
class ActivityDesktopTest {
    @TempDir
    Path tmp;

    private DesktopHarness h;

    static final List<String> SENTINELS = List.of("123.456.789-09", "12345678909", "12.345.678/0001-95", "maria.sigilosa@cliente.example",
            "tok_SIGILOSO_OBS_9Z", "Senha#Sigilosa9Z", "ClienteSigiloso9Z", "VALOR-EXCEL-9Z-4471", "token=abc9Z", "cliente.example/api", "Traceback",
            "SENTINELA-VALOR-DA-AUTOMACAO-7Q2");

    @AfterEach
    void tearDown() throws Exception {
        if (h != null) h.app.context().execution().close();
        if (h != null) h.close();
    }

    private void choose(Runnable r) throws Exception {
        h.run(r);
        h.waitFor(() -> !h.app.executionBusy(), "escolha conferida");
    }

    private ExecutionDialog open() throws Exception {
        h.later(h.app::openExecution);
        h.waitFor(() -> h.app.executionDialog() != null && h.app.executionDialog().isShowing(), "janela Execução local");
        h.waitFor(() -> h.app.executionSetup().session() != null, "sessão verificada");
        ExecutionDialog d = h.edt(h.app::executionDialog);
        h.run(() -> d.tabs().setSelectedIndex(1));
        return d;
    }

    private ExecutionId run(ExecutionDialog d, Path pkg, Path py, Path out) throws Exception {
        choose(() -> h.app.selectPackage(pkg));
        choose(h.app::approveConfirmed);
        choose(() -> h.app.selectPython(py));
        choose(() -> h.app.selectOutput(out));
        h.settle();
        h.run(() -> d.startButton().doClick());
        h.waitFor(() -> h.app.context().execution().running(), "em andamento");
        return h.app.context().execution().current().orElseThrow().record().id();
    }

    /** Tudo o que a aba mostra: textos visíveis + todas as células da tabela + o detalhe de cada linha. */
    private List<String> everything(ExecutionDialog d) throws Exception {
        return h.edt(() -> {
            ActivityPanel p = d.activityPanel();
            List<String> out = new ArrayList<>(texts(d.getRootPane()));
            for (int r = 0; r < p.model().getRowCount(); r++) {
                for (int c = 0; c < p.model().getColumnCount(); c++) out.add(String.valueOf(p.model().getValueAt(r, c)));
            }
            for (int r = 0; r < p.table().getRowCount(); r++) {
                p.table().setRowSelectionInterval(r, r);
                out.addAll(texts(p));
            }
            p.table().clearSelection();
            return out;
        });
    }

    /** Captura o topo (resumo) e a linha do tempo da aba. */
    private void shots(ExecutionDialog d, String name) throws Exception {
        h.run(() -> d.activityPanel().revealSummary());
        h.settle();
        capture(d, name);
        h.run(() -> d.activityPanel().revealTimeline());
        h.settle();
        capture(d, name + "-linha-do-tempo");
        h.run(() -> d.activityPanel().revealSummary());
    }

    private static String leak(List<String> texts) {
        for (String t : texts) for (String s : SENTINELS) if (t.contains(s)) return s + " em '" + t + "'";
        return null;
    }

    @Test
    @DisplayName("OBS-G1/G2/G3 na tela: etapas durante a execução, sentinelas ausentes, genérico sem etapas, histórico após reiniciar")
    void atividadeNaJanelaReal() throws Exception {
        Path py = ExecutionDesktopTest.python();
        Assumptions.assumeTrue(py != null, "sem Python neste ambiente");
        Path slow = ObsStudioPackage.create(tmp.resolve("pacote-studio-lento"), "{\"mode\": \"slow\", \"items\": 4, \"delay_ms\": 350}");
        Path hostile = ObsStudioPackage.create(tmp.resolve("pacote-sentinela"), "{\"mode\": \"sentinel\", \"items\": 2, \"delay_ms\": 20}");
        Path generic = ExecutionDesktopTest.labPackage(tmp.resolve("pacote-generico"), "lab_volumoso.py");
        Path out = Files.createDirectories(tmp.resolve("saida"));
        Path data = tmp.resolve("dados");
        h = DesktopHarness.start(data, AgentDesktopTest.ENV, new String[0], TrayController.SYSTEM);
        ExecutionDialog d = open();

        // 1) execução em andamento: etapas aparecem DURANTE, a EDT continua respondendo
        ExecutionId first = run(d, slow, py, out);
        h.waitFor(() -> d.activityPanel().events().stream().anyMatch(e -> "search.press_enter".equals(e.stepId())), "etapas durante a execução");
        assertTrue(h.app.context().execution().running(), "ainda em andamento quando as etapas aparecem");
        long worst = 0;
        for (int i = 0; i < 20 && h.app.context().execution().running(); i++) {
            long t = System.nanoTime();
            h.run(() -> { });
            worst = Math.max(worst, (System.nanoTime() - t) / 1_000_000);
            Thread.sleep(50);
        }
        assertTrue(worst < 250, "EDT responsiva durante a coleta: pior caso " + worst + " ms");
        shots(d, "O01-atividade-durante-execucao");
        List<String> during = h.edt(() -> texts(d.getRootPane()));
        assertTrue(during.stream().anyMatch(t -> t.equals("Estado: Em andamento")), during.toString());
        assertTrue(during.stream().anyMatch(t -> t.startsWith("Em tempo quase real")), during.toString());
        h.waitFor(() -> !h.app.context().execution().running(), "fim");
        h.waitFor(() -> d.activityPanel().health() != null && d.activityPanel().health().completed(), "coleta encerrada");
        h.settle();
        List<ActivityEvent> ev = h.edt(() -> d.activityPanel().events());
        Set<String> phases = new TreeSet<>();
        ev.stream().filter(e -> e.phase() != null).forEach(e -> phases.add(e.phase()));
        assertTrue(phases.containsAll(Set.of("INIT", "PROCESS", "END")), phases.toString());
        assertTrue(ev.stream().anyMatch(e -> "profile.read_birth_date".equals(e.stepId())));
        assertEquals(ExecutionState.SUCCEEDED, h.app.context().execution().current().orElseThrow().record().state());
        assertTrue(h.edt(() -> texts(d.activityPanel())).stream().anyMatch(t -> t.equals("Estado: Concluída com sucesso")));
        shots(d, "O02-atividade-concluida");
        int firstCount = ev.size();

        // filtro: só erros (nenhum nesta execução) e busca por etapa
        h.run(() -> d.activityPanel().levelBox().setSelectedIndex(2));
        assertEquals(0, (int) h.edt(() -> d.activityPanel().model().getRowCount()));
        h.run(() -> d.activityPanel().levelBox().setSelectedIndex(0));

        // 2) produtor hostil: nada sensível na tela (tabela, detalhe, alertas)
        ExecutionId second = run(d, hostile, py, out);
        h.waitFor(() -> !h.app.context().execution().running(), "fim (sentinela)");
        h.waitFor(() -> second.equals(d.activityPanel().selectedRun()) && d.activityPanel().health() != null && d.activityPanel().health().completed(),
                "coleta encerrada (sentinela)");
        h.settle();
        List<String> all = everything(d);
        assertNull(leak(all), "sentinela na tela");
        assertTrue(all.stream().anyMatch(t -> t.equals("Eventos recusados")), "a recusa é mostrada, não escondida");
        shots(d, "O03-atividade-produtor-hostil");

        // 3) processo genérico (sem Framework, ~32 MB de saída): só ciclo de vida, "Etapas não disponíveis"
        ExecutionId third = run(d, generic, py, out);
        h.waitFor(() -> !h.app.context().execution().running(), "fim (genérico)");
        h.waitFor(() -> third.equals(d.activityPanel().selectedRun()) && d.activityPanel().health() != null && d.activityPanel().health().completed(),
                "coleta encerrada (genérico)");
        h.settle();
        List<String> g = h.edt(() -> texts(d.activityPanel()));
        assertTrue(g.contains("Etapas não disponíveis para este tipo de processo"), g.toString());
        assertTrue(h.edt(() -> d.activityPanel().events()).stream().noneMatch(e -> e.source() == ActivityEvent.Source.FRAMEWORK));
        assertNull(leak(everything(d)));
        shots(d, "O04-atividade-processo-generico");
        h.run(d::dispose);
        h.close();
        h = null;

        // 4) reinício do Agent: histórico das três execuções, mesma contagem, sem duplicar
        h = DesktopHarness.start(data, AgentDesktopTest.ENV, new String[0], TrayController.SYSTEM);
        ExecutionDialog d2 = open();
        h.waitFor(() -> d2.activityPanel().runsBox().getItemCount() == 3, "histórico com 3 execuções");
        h.run(() -> {
            for (int i = 0; i < d2.activityPanel().runsBox().getItemCount(); i++) {
                if (d2.activityPanel().runsBox().getItemAt(i).id().equals(first)) d2.activityPanel().runsBox().setSelectedIndex(i);
            }
        });
        h.waitFor(() -> first.equals(d2.activityPanel().selectedRun()) && d2.activityPanel().events().size() == firstCount, "histórico recarregado");
        h.settle();
        assertEquals(firstCount, h.edt(() -> d2.activityPanel().events()).size(), "reabrir não duplica nem perde");
        assertFalse(h.edt(() -> d2.activityPanel().health()).live());
        h.run(() -> d2.activityPanel().revealTimeline());
        h.settle();
        capture(d2, "O05-historico-apos-reiniciar");
        h.run(d2::dispose);
    }
}
