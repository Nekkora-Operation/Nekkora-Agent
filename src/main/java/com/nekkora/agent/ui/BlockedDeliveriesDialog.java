package com.nekkora.agent.ui;

import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.platform.PlatformService;
import com.nekkora.agent.platform.PlatformStatus;
import com.nekkora.tokens.Spacing;
import com.nekkora.ui.button.NekkoraButton;
import com.nekkora.ui.container.NekkoraCard;
import com.nekkora.ui.container.NekkoraScrollPane;
import com.nekkora.ui.container.NekkoraStack;
import com.nekkora.ui.data.NekkoraKeyValueView;
import com.nekkora.ui.typography.NekkoraParagraph;
import com.nekkora.ui.typography.TextTone;
import java.awt.Component;
import java.util.List;
import java.util.function.Consumer;

/**
 * Envios parados à espera de decisão (evento recusado pela Platform, lacuna local sem perda registrada, recusa do
 * registro). A automação nunca é afetada. Para um evento ou uma lacuna, a pessoa pode desistir: o número passa a perda
 * definitiva e é declarado à Platform (o histórico lá fica incompleto, nunca apresentado como recebido).
 */
final class BlockedDeliveriesDialog extends FittedDialog {
    static final String TITLE = "Envios parados";

    static final String RETRY = "Tentar registrar de novo";

    BlockedDeliveriesDialog(Component parent, List<PlatformStatus.Blocked> blocked, Consumer<ExecutionId> abandon) {
        this(parent, blocked, abandon, id -> { });
    }

    BlockedDeliveriesDialog(Component parent, List<PlatformStatus.Blocked> blocked, Consumer<ExecutionId> abandon, Consumer<ExecutionId> retry) {
        super(parent, TITLE);
        setDescription("Cada envio abaixo parou e não é repetido em laço. Nada foi apagado do histórico local.");
        setDialogSize(Size.MEDIUM);
        NekkoraStack list = NekkoraStack.vertical(Spacing.SPACE_5).align(NekkoraStack.Align.STRETCH);
        if (blocked.isEmpty()) list.add(new NekkoraParagraph("Nenhum envio parado.", TextTone.SECONDARY));
        for (PlatformStatus.Blocked b : blocked) {
            NekkoraKeyValueView kv = new NekkoraKeyValueView();
            kv.addMono("Execução", b.executionId().value());
            kv.add("Motivo", b.code() == null ? "—" : PlatformText.problem(b.code()));
            if (b.sequence() != null) kv.add("Evento", String.valueOf(b.sequence()));
            boolean byLicense = PlatformService.notRegisteredByLicense(b.code());
            if (byLicense) {
                // a execução aconteceu; o que parou foi o registro na Platform. As duas coisas ficam ditas, separadas
                kv.add("Neste computador", "A automação executou. O desfecho dela está em Atividade.");
                kv.add("Na Platform", "Não registrada e não monitorada: " + PlatformText.licenseReason(b.code()) + ".");
            }
            NekkoraStack content = NekkoraStack.vertical(Spacing.SPACE_4).align(NekkoraStack.Align.STRETCH);
            content.add(kv);
            if (byLicense) {
                content.add(new NekkoraParagraph(PlatformText.NOT_REGISTERED_KEPT, TextTone.SECONDARY));
                NekkoraButton again = NekkoraButton.secondary(RETRY);
                again.getAccessibleContext().setAccessibleDescription("Pede à Platform, de novo, o registro desta execução; nada é apagado se ela recusar");
                again.addActionListener(e -> {
                    again.setEnabled(false);
                    retry.accept(b.executionId());
                });
                content.add(again);
            }
            if (b.sequence() != null) {
                NekkoraButton give = NekkoraButton.secondary("Desistir e declarar perda…");
                give.getAccessibleContext().setAccessibleDescription("Declara à Platform que este número não será entregue; a decisão é definitiva");
                give.addActionListener(e -> {
                    give.setEnabled(false);
                    abandon.accept(b.executionId());
                });
                content.add(give);
            }
            NekkoraCard card = new NekkoraCard(null, null);
            card.setContent(content);
            list.add(card);
        }
        NekkoraScrollPane sp = NekkoraScrollPane.verticalOnly(list);
        sp.setBorder(null);
        setContent(sp);
        addSecondary("Fechar");
    }
}
