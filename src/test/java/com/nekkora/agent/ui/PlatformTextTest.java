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
        // T13: o pedido é enviado ao confirmar; antes da resposta, nada diz que o Worker ficou livre
        assertEquals("A Platform aceita esta máquina agora. Ao confirmar, o pedido de desvínculo é enviado em seguida, e a resposta da Platform aparece aqui.",
                PlatformText.server(preview(ServerView.ACCEPTED, LinkState.PAIRED)));
        assertTrue(PlatformText.server(preview(ServerView.REFUSED, LinkState.REFUSED)).startsWith("A Platform recusou a credencial desta máquina (revogada ou vencida)."));
        String offline = PlatformText.server(preview(ServerView.UNREACHABLE, LinkState.PAIRED));
        assertTrue(offline.contains("não pôde ser consultada") && offline.contains("platform.exemplo.test") && offline.contains("0a1b2c3d-0000-4000-8000-000000000001"),
                "orienta a concluir na Platform de origem com host e organização: " + offline);
        assertTrue(PlatformText.server(preview(ServerView.NOT_CONSULTED, LinkState.DESTINATION_CHANGED)).contains("o endereço configurado não é o do pareamento"));
    }

    @Test
    void consequencesAndPendingChoicesAreExplicit() {
        // T13: o desvínculo é pedido à Platform; sem conexão fica guardado; a administração continua podendo revogar
        assertTrue(PlatformText.UNLINK_CONSEQUENCES.contains("pede à Platform o encerramento deste vínculo"));
        assertTrue(PlatformText.UNLINK_CONSEQUENCES.contains("nada novo começa por este vínculo"));
        assertTrue(PlatformText.UNLINK_CONSEQUENCES.contains("Vinculada e Offline") && PlatformText.UNLINK_CONSEQUENCES.contains("revogar o vínculo em Workers"));
        assertTrue(PlatformText.UNLINK_CONSEQUENCES.contains("a credencial é apagada desta máquina quando a Platform responder"));
        assertTrue(PlatformText.UNLINK_CONSEQUENCES.contains("aprovações locais de pacotes"));
        assertTrue(PlatformText.UNLINK_CONSEQUENCES.contains("histórico das execuções e as automações desta máquina são mantidos"));
        assertTrue(PlatformText.UNLINK_CONSEQUENCES.contains("Cancelar não muda nada."));
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

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("LICENCIAMENTO: recusa do registro por licença - a tela diz que executou neste computador e que não foi registrada, com o motivo")
    void notRegisteredByLicense() {
        for (ErrorCode c : new ErrorCode[] {ErrorCode.PLT_LICENSE_REQUIRED, ErrorCode.PLT_LICENSE_EXPIRED, ErrorCode.PLT_RUN_NOT_COVERED}) {
            com.nekkora.agent.platform.DeliveryView v = new com.nekkora.agent.platform.DeliveryView(com.nekkora.agent.platform.DeliveryView.State.BLOCKED,
                    0, 0, 0, c, null, false);
            String text = PlatformText.delivery(v);
            assertTrue(text.startsWith("Executou neste computador · não registrada na Platform: "), text);
            assertTrue(!text.contains("Envio parado"), text);
            assertTrue(c.description().contains("aconteceu neste computador") && c.description().contains("não está monitorada na Platform"), c.description());
            // não é falha da execução
            org.junit.jupiter.api.Assertions.assertNotEquals(PlatformText.deliveryStatus(new com.nekkora.agent.platform.DeliveryView(
                    com.nekkora.agent.platform.DeliveryView.State.BLOCKED, 0, 0, 0, ErrorCode.PLT_AUTHORIZATION_REFUSED, null, false)), PlatformText.deliveryStatus(v));
        }
        assertTrue(PlatformText.delivery(new com.nekkora.agent.platform.DeliveryView(com.nekkora.agent.platform.DeliveryView.State.BLOCKED, 0, 0, 0,
                ErrorCode.PLT_LICENSE_EXPIRED, null, false)).endsWith("a licença da automação estava vencida quando esta execução começou"));
        // cada motivo do servidor continua com o seu texto e o seu código; nenhum descreve a licença "hoje"
        assertTrue(PlatformText.licenseReason(ErrorCode.PLT_LICENSE_REQUIRED).endsWith("quando esta execução começou"));
        assertTrue(PlatformText.licenseReason(ErrorCode.PLT_RUN_NOT_COVERED).startsWith("no início dela"));
        org.junit.jupiter.api.Assertions.assertEquals(3, java.util.stream.Stream.of(ErrorCode.PLT_LICENSE_REQUIRED, ErrorCode.PLT_LICENSE_EXPIRED,
                ErrorCode.PLT_RUN_NOT_COVERED).map(PlatformText::licenseReason).distinct().count());
        assertTrue(PlatformText.NOT_REGISTERED_KEPT.contains("não à situação da licença hoje"));
        assertTrue(PlatformText.NOT_REGISTERED_KEPT.contains("nada foi apagado nem descartado"));
        // não promete que renovar a licença registra o que rodou sem ela
        assertTrue(PlatformText.NOT_REGISTERED_KEPT.contains("renovar a licença depois vale para as próximas execuções, não para esta"));
        // a recusa de autorização de antes continua como era
        assertTrue(PlatformText.delivery(new com.nekkora.agent.platform.DeliveryView(com.nekkora.agent.platform.DeliveryView.State.BLOCKED, 0, 0, 0,
                ErrorCode.PLT_AUTHORIZATION_REFUSED, null, false)).startsWith("Envio parado: "));
    }
}
