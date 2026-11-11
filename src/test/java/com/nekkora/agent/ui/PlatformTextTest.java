package com.nekkora.agent.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.platform.DeliveryView;
import com.nekkora.agent.platform.PlatformService;
import com.nekkora.agent.platform.PlatformService.ServerView;
import com.nekkora.agent.platform.PlatformStatus.LinkState;
import com.nekkora.ui.core.Status;
import org.junit.jupiter.api.Test;

/** T06: textos do desvínculo e da intenção do código (sem tela). */
class PlatformTextTest {
    private static PlatformService.UnlinkPreview preview(ServerView s, LinkState state) {
        return new PlatformService.UnlinkPreview(true, state, "platform.exemplo.test", "0a1b2c3d-0000-4000-8000-000000000001", "5e4d3c2b-0000-4000-8000-000000000003",
                "9f8e7d6c-0000-4000-8000-000000000002", 0, 0, false, s);
    }

    @Test
    void serverAnswerNeverSaysTheWorkerIsFree() {
        for (ServerView s : ServerView.values()) {
            String t = PlatformText.server(preview(s, LinkState.PAIRED)).toLowerCase(java.util.Locale.ROOT);
            assertFalse(t.contains("livre") || t.contains("liberado"), s + ": " + t);
        }
        assertEquals("A Platform ainda aceita esta máquina: o vínculo continua lá até uma pessoa autorizada revogar o Agent em Workers.",
                PlatformText.server(preview(ServerView.ACCEPTED, LinkState.PAIRED)));
        assertTrue(PlatformText.server(preview(ServerView.REFUSED, LinkState.REFUSED)).startsWith("A Platform recusou a credencial desta máquina (revogada ou vencida)."));
        String offline = PlatformText.server(preview(ServerView.UNREACHABLE, LinkState.PAIRED));
        assertTrue(offline.contains("não pôde ser consultada") && offline.contains("platform.exemplo.test") && offline.contains("0a1b2c3d-0000-4000-8000-000000000001"),
                "orienta a concluir na Platform de origem com host e organização: " + offline);
        assertTrue(PlatformText.server(preview(ServerView.NOT_CONSULTED, LinkState.DESTINATION_CHANGED)).contains("o endereço configurado não é o do pareamento"));
    }

    @Test
    void consequencesAndPendingChoicesAreExplicit() {
        assertTrue(PlatformText.UNLINK_CONSEQUENCES.contains("para de enviar e de receber"));
        assertTrue(PlatformText.UNLINK_CONSEQUENCES.contains("credencial guardada nesta máquina é apagada"));
        assertTrue(PlatformText.UNLINK_CONSEQUENCES.contains("histórico das execuções e as automações desta máquina são mantidos"));
        assertTrue(PlatformText.UNLINK_CONSEQUENCES.contains("revogar o Agent na Platform (Workers)"));
        String p = PlatformText.unlinkPending(2, 15);
        assertTrue(p.startsWith("2 execuções ainda não foram entregues à Platform (15 eventos)."));
        assertTrue(p.contains("“Aguardar envios”") && p.contains("“Desvincular mesmo assim”") && p.contains("não entregues"));
        assertTrue(PlatformText.unlinkPending(1, 0).startsWith("1 execução ainda não foi entregue à Platform."));
    }

    @Test
    void undeliveredAfterUnlinkIsLabelled() {
        DeliveryView v = new DeliveryView(DeliveryView.State.UNLINKED_UNDELIVERED, 3, 3, 0, null, null, false);
        assertTrue(PlatformText.delivery(v).startsWith("Não entregue (vínculo removido)"));
        assertEquals(Status.WARNING, PlatformText.deliveryStatus(v));
    }

    @Test
    void wrongKindCodeExplainsTheRightAction() {
        assertTrue(PlatformText.problem(ErrorCode.PLT_PAIRING_CODE_FOR_ROTATION).startsWith("Este é um código de pareamento. Para renovar a credencial desta máquina, "
                + "gere um código de renovação na Platform. Para ligar esta máquina a outro Worker ou organização, desvincule-a primeiro e pareie de novo."));
        assertTrue(PlatformText.problem(ErrorCode.PLT_ROTATION_CODE_FOR_PAIRING).startsWith("Este é um código de renovação de credencial, que só serve para uma "
                + "máquina já pareada. Para parear, gere um código de pareamento na Platform (Workers → Vincular Agent)."));
    }
}
