package com.nekkora.agent.ui;

import static com.nekkora.agent.ui.DesktopHarness.capture;
import static com.nekkora.agent.ui.DesktopHarness.find;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.os.TrayController;
import com.nekkora.ui.button.NekkoraButton;
import com.nekkora.ui.data.NekkoraStatusBadge;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A12: escalas 100%, 150% e 200% (uma JVM por escala, {@code -Dsun.java2d.uiScale}). Janela e diálogos
 * dentro da área útil, sem selo, botão ou rótulo menor que o tamanho preferido (texto cortado), com
 * rolagem quando o conteúdo não cabe. Capturas em {@code target/desktop-evidence/escala-N}.
 */
@Tag("desktop")
class ScaleEvidenceTest {
    @TempDir
    java.nio.file.Path tmp;

    @Test
    void windowAndDialogsFitAndNothingIsCutAtThisScale() throws Exception {
        int pct = (int) Math.round(DesktopHarness.scale() * 100);
        System.out.println("[A12] escala efetiva " + pct + "% (sun.java2d.uiScale=" + System.getProperty("sun.java2d.uiScale") + ")");
        try (DesktopHarness h = DesktopHarness.start(tmp.resolve("scale"), Map.of("COMPUTERNAME", "LAB-AGENT-01"), new String[0], TrayController.SYSTEM)) {
            MainWindow w = h.window();
            h.settle();
            check(h, w, "janela principal");
            capture(w, "A12-janela-" + pct);

            h.later(h.app::openSettings);
            h.waitFor(() -> h.app.activeDialog() instanceof SettingsDialog d && d.isShowing(), "Configurações");
            JDialog s = h.edt(h.app::activeDialog);
            h.settle();
            check(h, s, "Configurações");
            capture(s, "A12-configuracoes-" + pct);
            h.run(s::dispose);
            h.waitFor(() -> h.app.activeDialog() == null, "fechado");

            h.later(h.app::openDiagnostics);
            h.waitFor(() -> h.app.activeDialog() instanceof DiagnosticsDialog d && d.isShowing(), "Diagnóstico");
            JDialog d = h.edt(h.app::activeDialog);
            h.settle();
            check(h, d, "Diagnóstico");
            capture(d, "A12-diagnostico-" + pct);
            h.run(d::dispose);
            h.waitFor(() -> h.app.activeDialog() == null, "fechado");

            // DESKTOP-001: janela Execução local
            h.later(h.app::openExecution);
            h.waitFor(() -> h.app.activeDialog() instanceof ExecutionDialog x && x.isShowing(), "Execução local");
            JDialog x = h.edt(h.app::activeDialog);
            h.waitFor(() -> h.app.executionSetup().session() != null, "sessão verificada");
            h.settle();
            check(h, x, "Execução local");
            capture(x, "D12-execucao-local-" + pct);
            // OBS-001: aba Atividade na mesma escala
            h.run(() -> ((ExecutionDialog) x).tabs().setSelectedIndex(1));
            h.settle();
            check(h, x, "Atividade");
            capture(x, "O12-atividade-" + pct);
            h.run(x::dispose);
        }
    }

    private static void check(DesktopHarness h, Window win, String what) throws Exception {
        List<String> problems = h.edt(() -> {
            List<String> p = new ArrayList<>();
            Rectangle usable = AgentDesktopTest.usable(win);
            if (!usable.contains(win.getBounds())) p.add(what + " fora da área útil: " + win.getBounds() + " em " + usable);
            JScrollPane sp = find(SwingUtilities.getRootPane(win), JScrollPane.class).stream().findFirst().orElse(null);
            boolean scrolls = sp != null && sp.getViewport().getView() != null
                    && sp.getViewport().getView().getHeight() > sp.getViewport().getHeight();
            for (Component c : all(SwingUtilities.getRootPane(win))) {
                if (!c.isShowing()) continue;
                if (c instanceof NekkoraStatusBadge || c instanceof NekkoraButton || (c instanceof JLabel l && l.getText() != null && !l.getText().isBlank())) {
                    if (c.getWidth() + 1 < c.getPreferredSize().width) {
                        p.add(what + ": " + label(c) + " cortado (" + c.getWidth() + " < " + c.getPreferredSize().width + ")");
                    }
                }
            }
            System.out.println("[A12] " + what + " " + win.getWidth() + "x" + win.getHeight() + " rolagem=" + scrolls);
            return p;
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static String label(Component c) {
        if (c instanceof JLabel l) return "rótulo '" + l.getText() + "'";
        if (c instanceof NekkoraButton b) return "botão '" + b.getText() + "'";
        if (c instanceof JComponent j && j.getAccessibleContext().getAccessibleName() != null) return "selo '" + j.getAccessibleContext().getAccessibleName() + "'";
        return c.getClass().getSimpleName();
    }

    private static List<Component> all(Container root) {
        List<Component> out = new ArrayList<>();
        for (Component c : root.getComponents()) {
            out.add(c);
            if (c instanceof Container ct) out.addAll(all(ct));
        }
        return out;
    }
}
