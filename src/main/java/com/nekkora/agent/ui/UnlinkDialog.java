package com.nekkora.agent.ui;

import com.nekkora.agent.platform.PlatformService;
import com.nekkora.tokens.Spacing;
import com.nekkora.ui.button.NekkoraButton;
import com.nekkora.ui.container.NekkoraScrollPane;
import com.nekkora.ui.container.NekkoraStack;
import com.nekkora.ui.core.Severity;
import com.nekkora.ui.data.NekkoraKeyValueView;
import com.nekkora.ui.feedback.NekkoraInlineAlert;
import com.nekkora.ui.typography.NekkoraParagraph;
import com.nekkora.ui.typography.TextTone;
import java.awt.Component;
import java.awt.event.KeyEvent;

/**
 * T06: confirmação de "Desvincular esta máquina". As consequências aparecem ANTES de qualquer remoção, com a Platform, a
 * organização e o Worker do vínculo (identificadores completos: o contrato não traz nomes) e o que a Platform respondeu à
 * credencial. Com envios pendentes, as duas saídas são explícitas: "Aguardar envios" (nada muda) ou "Desvincular mesmo assim".
 * Cancelar (o botão padrão, Enter e Esc) nunca muda nada.
 */
final class UnlinkDialog extends FittedDialog {
    static final String TITLE = "Desvincular esta máquina?";

    private final NekkoraButton keep;
    private final NekkoraButton go;

    UnlinkDialog(Component parent, PlatformService.UnlinkPreview p) {
        super(parent, TITLE);
        setDescription("Remove o vínculo desta máquina com a Platform. Confira as consequências antes de confirmar.");
        setDialogSize(Size.MEDIUM);
        NekkoraStack form = NekkoraStack.vertical(Spacing.SPACE_6).align(NekkoraStack.Align.STRETCH);
        NekkoraKeyValueView who = new NekkoraKeyValueView();
        who.add("Platform", p.host() == null ? "—" : p.host());
        if (p.organizationId() != null) who.addCopyable("Organização", p.organizationId());
        if (p.workerId() != null) who.addCopyable("Worker", p.workerId());
        if (p.agentId() != null) who.addCopyable("Agent", p.agentId());
        form.add(who);
        form.add(new NekkoraInlineAlert(PlatformText.serverSeverity(p.server()), "Situação na Platform", PlatformText.server(p)));
        if (p.pendingRuns() > 0) {
            form.add(new NekkoraInlineAlert(Severity.WARNING, "Envios pendentes", PlatformText.unlinkPending(p.pendingRuns(), p.pendingEvents())));
        }
        if (p.rotationPending()) {
            form.add(new NekkoraInlineAlert(Severity.WARNING, "Renovação em andamento", PlatformText.UNLINK_ROTATION_PENDING));
        }
        form.add(new NekkoraParagraph(PlatformText.UNLINK_CONSEQUENCES, TextTone.SECONDARY));
        NekkoraScrollPane sp = NekkoraScrollPane.verticalOnly(form);
        sp.setBorder(null);
        setContent(sp);
        boolean pending = p.pendingRuns() > 0;
        keep = addSecondary(pending ? "Aguardar envios" : "Cancelar");
        keep.setMnemonic(pending ? KeyEvent.VK_A : KeyEvent.VK_C);
        go = addDanger(pending ? "Desvincular mesmo assim" : "Desvincular", () -> true);
        go.setMnemonic(KeyEvent.VK_D);
        getRootPane().setDefaultButton(keep);
    }

    // acesso para testes de desktop
    NekkoraButton keepButton() {
        return keep;
    }

    NekkoraButton unlinkButton() {
        return go;
    }
}
