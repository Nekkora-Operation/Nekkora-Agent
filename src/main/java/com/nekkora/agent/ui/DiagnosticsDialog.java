package com.nekkora.agent.ui;

import com.nekkora.agent.diagnostics.AgentLog;
import com.nekkora.agent.diagnostics.DiagnosticReport;
import com.nekkora.tokens.Spacing;
import com.nekkora.ui.button.NekkoraButton;
import com.nekkora.ui.container.NekkoraScrollPane;
import com.nekkora.ui.container.NekkoraStack;
import com.nekkora.ui.core.Severity;
import com.nekkora.ui.feedback.NekkoraInlineAlert;
import com.nekkora.ui.input.NekkoraCheckBox;
import com.nekkora.ui.technical.NekkoraCodeBlock;
import com.nekkora.ui.theme.Scale;
import com.nekkora.ui.typography.NekkoraParagraph;
import com.nekkora.ui.typography.TextTone;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.time.Clock;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Diagnóstico: mostra a prévia EXATA do arquivo (lista fechada de campos), com o nome da máquina
 * desmarcado por padrão. "Exportar…" grava o texto da prévia onde a pessoa escolher; nada é enviado.
 */
final class DiagnosticsDialog extends FittedDialog {

    static final String TITLE = "Diagnóstico";

    private final DiagnosticReport.Facts facts;
    private final Clock clock;
    private final NekkoraCheckBox includeName;
    private final NekkoraCodeBlock preview = new NekkoraCodeBlock("", "json");
    private final NekkoraInlineAlert failure = new NekkoraInlineAlert(Severity.ERROR, "Não foi possível exportar", "");
    private final NekkoraButton export;
    private boolean exporting;

    DiagnosticsDialog(Component parent, DiagnosticReport.Facts facts, String logsLocation, Clock clock, Consumer<String> onExport) {
        super(parent, TITLE);
        this.facts = facts;
        this.clock = clock;
        setDescription("O arquivo contém somente os campos abaixo. Nada é enviado: você escolhe onde salvar.");
        setDialogSize(Size.MEDIUM);

        StringBuilder list = new StringBuilder();
        for (String c : DiagnosticReport.CONTENTS) list.append("• ").append(c).append('\n');
        NekkoraParagraph contents = new NekkoraParagraph(list.toString().strip(), TextTone.SECONDARY);

        includeName = facts.machineName().isPresent()
                ? new NekkoraCheckBox("Incluir o nome da máquina (" + facts.machineName().get() + ")", false)
                : new NekkoraCheckBox("Incluir o nome da máquina (indisponível)", false);
        includeName.setEnabled(facts.machineName().isPresent());
        includeName.addActionListener(e -> refresh());

        preview.getTextArea().setRows(12);
        preview.getTextArea().getAccessibleContext().setAccessibleName("Prévia do arquivo de diagnóstico");
        preview.setPreferredSize(new Dimension(Scale.px(480), Scale.px(240)));

        NekkoraParagraph logs = new NekkoraParagraph("Logs técnicos locais: " + logsLocation + " (até " + AgentLog.MAX_FILES + " arquivos de "
                + AgentLog.MAX_FILE_BYTES / 1024 + " KiB). Os logs não são incluídos neste arquivo.", TextTone.MUTED);

        failure.setVisible(false);
        NekkoraStack content = NekkoraStack.vertical(Spacing.SPACE_5).align(NekkoraStack.Align.STRETCH);
        content.addAll(failure, contents, includeName, preview, logs);
        NekkoraScrollPane sp = NekkoraScrollPane.verticalOnly(content);
        sp.setBorder(null);
        setContent(sp);
        refresh();

        addSecondary("Fechar");
        export = addPrimary("Exportar…", () -> {
            if (exporting) return false;
            failure.setVisible(false);
            onExport.accept(previewText());
            return false;
        });
    }

    private void refresh() {
        preview.setCode(DiagnosticReport.toJson(facts, includeName.isSelected(), clock.instant()));
    }

    /** O texto exato que será gravado. */
    String previewText() {
        return preview.getCode();
    }

    void exportStarted() {
        exporting = true;
        export.setLoading(true);
        includeName.setEnabled(false);
    }

    void exportFinished(boolean ok, String failureMessage) {
        exporting = false;
        export.setLoading(false);
        includeName.setEnabled(facts.machineName().isPresent());
        if (ok) {
            confirm();
            return;
        }
        failure.setMessage(failureMessage);
        failure.setVisible(true);
        failure.revalidate();
        SwingUtilities.invokeLater(() -> failure.scrollRectToVisible(new Rectangle(0, 0, failure.getWidth(), failure.getHeight())));
    }

    // acesso para testes de desktop
    NekkoraCheckBox includeNameBox() {
        return includeName;
    }
}
