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
import com.nekkora.agent.obs.ActivityView;
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
        assertEquals(ActivityView.Integrity.COMPLETE, h.edt(() -> d.activityPanel().completeness()).integrity());
        assertTrue(h.edt(() -> d.activityPanel().footerLabel().getText()).endsWith("linha do tempo completa"));
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
        // parcial nunca parece completa: aviso próprio, título do quadro e rodapé
        assertEquals(ActivityView.Integrity.INCOMPLETE, h.edt(() -> d.activityPanel().completeness()).integrity());
        assertTrue(all.contains("Linha do tempo incompleta"), "aviso de linha do tempo incompleta");
        assertTrue(all.contains("Linha do tempo (incompleta)"), "título do quadro");
        assertTrue(h.edt(() -> d.activityPanel().footerLabel().getText()).endsWith("linha do tempo INCOMPLETA"));
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
        assertEquals(ActivityView.Integrity.COMPLETE, h.edt(() -> d.activityPanel().completeness()).integrity(), "sem etapas por natureza não é lacuna");
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

    @Test
    @DisplayName("Lista de execuções: carregando, nenhuma execução e falha de leitura são estados distintos e ditos")
    void historicoVazioEFalhaDeLeitura() throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path data = tmp.resolve("dados-vazio");
        Path runs = Files.createDirectories(data.resolve("execucoes"));
        h = DesktopHarness.start(data, AgentDesktopTest.ENV, new String[0], TrayController.SYSTEM);
        ExecutionDialog d = open();
        h.waitFor(() -> d.activityPanel().historyState() == ActivityPanel.HistoryState.EMPTY, "histórico vazio carregado");
        h.settle();
        assertEquals(0, (int) h.edt(() -> d.activityPanel().runsBox().getItemCount()));
        assertFalse(h.edt(() -> d.activityPanel().runsBox().isVisible()), "nada a escolher: a lista dá lugar ao aviso");
        assertTrue(h.edt(() -> d.activityPanel().runsEmptyLabel().isShowing()));
        assertEquals("Nenhuma execução registrada", h.edt(() -> d.activityPanel().runsEmptyLabel().getText()));
        assertEquals("Nenhuma execução registrada", h.edt(() -> d.activityPanel().runsBox().getAccessibleContext().getAccessibleDescription()));
        List<String> empty = h.edt(() -> texts(d.activityPanel()));
        assertTrue(empty.stream().anyMatch(t -> t.startsWith("Nenhuma execução registrada nesta máquina.")), empty.toString());
        assertTrue(empty.stream().noneMatch(t -> t.contains("Não foi possível carregar")), "vazio não é falha");
        capture(d, "O06-historico-vazio");
        Assumptions.assumeTrue(windows, "falha de leitura com ACL real só no Windows (root lê tudo no Linux)");
        deny(runs, true);
        try {
            h.run(() -> d.activityPanel().reloadButton().doClick());
            h.waitFor(() -> d.activityPanel().historyState() == ActivityPanel.HistoryState.FAILED, "falha de leitura do histórico");
            h.settle();
            List<String> failed = h.edt(() -> texts(d.activityPanel()));
            assertTrue(failed.contains("Não foi possível carregar o histórico"), failed.toString());
            assertTrue(failed.stream().anyMatch(t -> t.contains("AGT-OBS-014")), failed.toString());
            assertTrue(failed.stream().noneMatch(t -> t.startsWith("Nenhuma execução registrada nesta máquina.")), "falha não é 'nenhuma execução'");
            assertEquals("Histórico indisponível", h.edt(() -> d.activityPanel().runsEmptyLabel().getText()));
            capture(d, "O07-historico-falha-de-leitura");
        } finally {
            deny(runs, false);
        }
        h.run(() -> d.activityPanel().reloadButton().doClick());
        h.waitFor(() -> d.activityPanel().historyState() == ActivityPanel.HistoryState.EMPTY, "recupera ao atualizar");
        h.run(d::dispose);
    }

    @Test
    @DisplayName("Leitura sem resposta não congela a aba: aviso, nova tentativa e recuperação")
    void leituraPerdidaNaoCongelaAAba() throws Exception {
        com.nekkora.agent.exec.ExecutionId id = com.nekkora.agent.exec.ExecutionId.random();
        java.time.Instant t0 = java.time.Instant.parse("2026-10-23T10:00:00Z");
        com.nekkora.agent.exec.ExecutionRecord rec = new com.nekkora.agent.exec.ExecutionRecord(id, com.nekkora.agent.exec.ExecutionPackage.Kind.LAB_GENERIC,
                "pacote", "sha256:" + "a".repeat(64), ExecutionState.READY, null, t0, null, null, null, null, java.util.Map.of(), 0, 0, false, 0)
                .moveTo(ExecutionState.STARTING, null, t0).moveTo(ExecutionState.RUNNING, null, t0).moveTo(ExecutionState.SUCCEEDED, null, t0.plusSeconds(5));
        ActivityEvent done = ActivityEvent.of(id.value(), t0.plusSeconds(6), ActivityEvent.Source.COLLECTOR, "COLLECTION_COMPLETED", ActivityEvent.Level.INFO,
                ActivityEvent.Category.TELEMETRY).build().withSequence(1);
        ActivityView.Health ok = new ActivityView.Health(false, true, false, ActivityView.FrameworkState.NOT_APPLICABLE, 0, 0, 0, 0, 0, false, false, 0, false,
                false, 0, 0, null, false);
        java.util.concurrent.atomic.AtomicInteger asks = new java.util.concurrent.atomic.AtomicInteger();
        ActivityPanel.Data data = new ActivityPanel.Data() {
            @Override
            public void history(java.util.function.Consumer<com.nekkora.agent.exec.RunStore.Listing> onEdt) {
                onEdt.accept(new com.nekkora.agent.exec.RunStore.Listing(List.of(rec), 0, false));
            }

            @Override
            public void view(com.nekkora.agent.exec.ExecutionId x, long after, java.util.function.Consumer<ActivityPanel.Loaded> onEdt) {
                if (asks.incrementAndGet() == 1) return; // a primeira resposta se perde
                onEdt.accept(ActivityPanel.Loaded.of(java.util.Optional.of(new ActivityView(rec, List.of(done), ok))));
            }

            @Override
            public void export(com.nekkora.agent.exec.ExecutionId x, List<ActivityEvent> events, ActivityView.Completeness c) {
            }
        };
        java.util.concurrent.atomic.AtomicReference<ActivityPanel> panel = new java.util.concurrent.atomic.AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            ActivityPanel p = new ActivityPanel(data);
            p.start();
            panel.set(p);
        });
        long t = System.currentTimeMillis();
        while (System.currentTimeMillis() - t < ActivityPanel.LOST_REPLY_MS + 10_000) {
            ActivityView.Health h = DesktopHarness.onEdt(() -> panel.get().health());
            if (h != null && h.completed()) break;
            Thread.sleep(200);
        }
        assertTrue(DesktopHarness.onEdt(() -> panel.get().health()) != null && DesktopHarness.onEdt(() -> panel.get().health()).completed(), "recuperou");
        assertTrue(asks.get() >= 2, "pediu de novo depois da resposta perdida");
        assertEquals(ActivityView.Integrity.COMPLETE, DesktopHarness.onEdt(() -> panel.get().completeness()).integrity());
        javax.swing.SwingUtilities.invokeAndWait(() -> panel.get().stop());
    }

    @Test
    @DisplayName("Gravação do histórico pendente fica à vista e só volta a completa quando gravada (OBS-PERSISTENCE-HARDENING)")
    void gravacaoPendenteFicaAVista() throws Exception {
        com.nekkora.agent.exec.ExecutionId id = com.nekkora.agent.exec.ExecutionId.random();
        java.time.Instant t0 = java.time.Instant.parse("2026-10-23T10:00:00Z");
        com.nekkora.agent.exec.ExecutionRecord rec = new com.nekkora.agent.exec.ExecutionRecord(id, com.nekkora.agent.exec.ExecutionPackage.Kind.LAB_GENERIC,
                "pacote", "sha256:" + "a".repeat(64), ExecutionState.READY, null, t0, null, null, null, null, java.util.Map.of(), 0, 0, false, 0)
                .moveTo(ExecutionState.STARTING, null, t0).moveTo(ExecutionState.RUNNING, null, t0).moveTo(ExecutionState.SUCCEEDED, null, t0.plusSeconds(5));
        ActivityEvent done = ActivityEvent.of(id.value(), t0.plusSeconds(6), ActivityEvent.Source.COLLECTOR, "COLLECTION_COMPLETED", ActivityEvent.Level.INFO,
                ActivityEvent.Category.TELEMETRY).build().withSequence(1);
        // fechamento aceito, ainda não gravado: a coleta continua aberta até a prova no disco
        ActivityView.Health pendingHealth = new ActivityView.Health(true, true, false, ActivityView.FrameworkState.NOT_APPLICABLE, 0, 0, 0, 0, 0, false, false,
                0, false, false, 1, 0, null, false);
        ActivityView.Health written = new ActivityView.Health(false, true, false, ActivityView.FrameworkState.NOT_APPLICABLE, 0, 0, 0, 0, 0, false, false, 0,
                false, false, 0, 0, null, false);
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        ActivityPanel.Data data = new ActivityPanel.Data() {
            @Override
            public void history(java.util.function.Consumer<com.nekkora.agent.exec.RunStore.Listing> onEdt) {
                onEdt.accept(new com.nekkora.agent.exec.RunStore.Listing(List.of(rec), 0, false));
            }

            @Override
            public void view(com.nekkora.agent.exec.ExecutionId x, long after, java.util.function.Consumer<ActivityPanel.Loaded> onEdt) {
                onEdt.accept(ActivityPanel.Loaded.of(java.util.Optional.of(new ActivityView(rec, after >= 1 ? List.of() : List.of(done),
                        released.get() ? written : pendingHealth))));
            }

            @Override
            public void export(com.nekkora.agent.exec.ExecutionId x, List<ActivityEvent> events, ActivityView.Completeness c) {
            }
        };
        java.util.concurrent.atomic.AtomicReference<javax.swing.JFrame> frame = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<ActivityPanel> panel = new java.util.concurrent.atomic.AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            com.nekkora.ui.Nekkora.install(AgentApp.themeFor("")); // o tema do Agent (a captura é a tela real)
            ActivityPanel p = new ActivityPanel(data);
            javax.swing.JFrame f = new javax.swing.JFrame("Atividade");
            f.setContentPane(p);
            f.setSize(760, 760);
            f.setVisible(true);
            p.start();
            panel.set(p);
            frame.set(f);
        });
        try {
            long t = System.currentTimeMillis();
            while (System.currentTimeMillis() - t < 15_000 && !DesktopHarness.onEdt(() -> texts(panel.get())).contains("Gravação do histórico pendente")) {
                Thread.sleep(100);
            }
            List<String> during = DesktopHarness.onEdt(() -> texts(panel.get()));
            assertTrue(during.contains("Gravação do histórico pendente"), during.toString());
            assertTrue(during.stream().anyMatch(s -> s.contains("ainda não gravado") && s.contains("AGT-OBS-009")), during.toString());
            assertTrue(during.contains("Encerrada (1 eventos; 1 aguardando gravação no disco)"), "não diz 'guardados' antes de gravar: " + during);
            ActivityView.Completeness c = DesktopHarness.onEdt(() -> panel.get().completeness());
            assertEquals(ActivityView.Integrity.INCOMPLETE, c.integrity());
            assertTrue(c.gaps().contains(ActivityView.Gap.STORE_PENDING));
            capture(frame.get(), "O13-gravacao-pendente");
            released.set(true);
            t = System.currentTimeMillis();
            while (System.currentTimeMillis() - t < 15_000 && DesktopHarness.onEdt(() -> panel.get().completeness()).integrity() != ActivityView.Integrity.COMPLETE) {
                Thread.sleep(100);
            }
            assertEquals(ActivityView.Integrity.COMPLETE, DesktopHarness.onEdt(() -> panel.get().completeness()).integrity(), "completa depois de gravada");
            assertFalse(DesktopHarness.onEdt(() -> texts(panel.get())).contains("Gravação do histórico pendente"));
        } finally {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                panel.get().stop();
                frame.get().dispose();
            });
        }
    }

    /** ACL real: nega (ou devolve) a leitura da pasta ao usuário atual. */
    private static void deny(Path dir, boolean on) throws Exception {
        String icacls = System.getenv("SystemRoot") + "\\System32\\icacls.exe";
        String user = System.getenv("USERNAME");
        List<String> cmd = on ? List.of(icacls, dir.toString(), "/deny", user + ":(R)") : List.of(icacls, dir.toString(), "/remove:d", user);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, p.exitValue());
    }
}
