package com.nekkora.agent.ui;

import static com.nekkora.agent.ui.DesktopHarness.capture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.os.TrayController;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-PLATFORM-001: o card "Platform" abre o pareamento pelo botão e pelo atalho Alt+A, com o foco no campo do código.
 * (Alt+R foi descartado: nesta máquina de homologação o NVIDIA Overlay captura Alt+R globalmente, antes do aplicativo.)
 */
@Tag("desktop")
class PlatformDesktopTest {
    @TempDir
    Path tmp;

    private DesktopHarness h;

    @AfterEach
    void tearDown() throws Exception {
        if (h != null) h.close();
    }

    @Test
    void pairingDialogOpensFromTheButtonAndFromTheKeyboard() throws Exception {
        Path dir = tmp.resolve("p1");
        DesktopHarness.writeConfig(dir, "{\"format\": \"nekkora-agent/config\", \"version\": 1, \"theme\": \"sakura-dark\", "
                + "\"shortcuts\": {\"platformUrl\": \"https://platform.exemplo.test\", \"studioExecutable\": null}}");
        h = DesktopHarness.start(dir, Map.of("COMPUTERNAME", "LAB-AGENT-01"), new String[0], TrayController.SYSTEM);
        MainWindow w = h.window();
        h.waitFor(() -> w.pairButton().isShowing(), "botão Parear visível");
        capture(w, "PLT-card-nao-pareado");
        h.later(() -> w.pairButton().doClick());
        h.waitFor(() -> h.app.activeDialog() instanceof PairingDialog d && d.isShowing(), "pareamento aberto pelo botão");
        PairingDialog d = (PairingDialog) h.edt(h.app::activeDialog);
        h.waitFor(() -> d.codeInput().isFocusOwner(), "foco no campo do código");
        capture(d, "PLT-pareamento-aberto");
        h.later(d::cancel);
        h.waitFor(() -> h.app.activeDialog() == null, "pareamento fechado");
        // pelo teclado, como uma pessoa
        h.run(() -> { w.toFront(); w.requestFocus(); });
        h.waitFor(w::isFocused, "janela com foco");
        Robot r = new Robot();
        r.keyPress(KeyEvent.VK_ALT);
        r.keyPress(KeyEvent.VK_A);
        r.keyRelease(KeyEvent.VK_A);
        r.keyRelease(KeyEvent.VK_ALT);
        h.waitFor(() -> h.app.activeDialog() instanceof PairingDialog p && p.isShowing(), "pareamento aberto por Alt+A");
        PairingDialog k = (PairingDialog) h.edt(h.app::activeDialog);
        h.waitFor(() -> k.codeInput().isFocusOwner(), "foco no campo do código (teclado)");
        h.later(k::cancel);
    }

    /**
     * T06: com a máquina pareada, o card mostra Platform, organização e Worker e oferece as três intenções; "Desvincular esta
     * máquina…" mostra as consequências ANTES de remover; cancelar não muda nada; confirmar deixa "Não pareado". O vínculo é
     * gravado direto na pasta descartável SEM credencial: nenhuma chamada de rede acontece (a Platform "não é consultada").
     */
    @Test
    void unlinkFromTheCardShowsConsequencesAndCancelKeepsEverything() throws Exception {
        Path dir = tmp.resolve("p2");
        DesktopHarness.writeConfig(dir, "{\"format\": \"nekkora-agent/config\", \"version\": 1, \"theme\": \"sakura-dark\", "
                + "\"shortcuts\": {\"platformUrl\": \"https://platform.exemplo.test\", \"studioExecutable\": null}}");
        String orgId = "0a1b2c3d-0000-4000-8000-000000000001";
        String worker = "9f8e7d6c-0000-4000-8000-000000000002";
        Path link = dir.resolve("plataforma").resolve("vinculo.json");
        java.nio.file.Files.createDirectories(link.getParent());
        java.nio.file.Files.writeString(link, "{\"schema\":\"nekkora-agent/platform-link\",\"version\":\"1.0.0\",\"origin\":\"https://platform.exemplo.test\","
                + "\"organizationId\":\"" + orgId + "\",\"agentId\":\"5e4d3c2b-0000-4000-8000-000000000003\",\"installationId\":\"5e4d3c2b-0000-4000-8000-000000000004\","
                + "\"workerId\":\"" + worker + "\",\"credentialId\":\"5e4d3c2b-0000-4000-8000-000000000005\",\"credentialExpiresAt\":\"2030-01-01T00:00:00Z\","
                + "\"pairedAt\":\"2026-11-01T00:00:00Z\",\"refused\":false}\n");
        h = DesktopHarness.start(dir, Map.of("COMPUTERNAME", "LAB-AGENT-01"), new String[0], TrayController.SYSTEM);
        MainWindow w = h.window();
        h.waitFor(() -> w.removeLinkButton().isShowing() && w.rotateButton().isShowing() && !w.pairButton().isVisible(), "pareado: renovar e desvincular");
        assertEquals(MainWindow.UNLINK_LABEL, h.edt(() -> w.removeLinkButton().getText()));
        assertEquals(MainWindow.ROTATE_LABEL, h.edt(() -> w.rotateButton().getText()));
        java.util.List<String> card = h.edt(() -> DesktopHarness.texts(w.getContentPane()));
        assertTrue(card.contains("platform.exemplo.test") && card.contains("0a1b2c3d") && card.contains("9f8e7d6c"),
                "Platform, organização e Worker no card: " + card);
        capture(w, "PLT-card-pareado-tres-intencoes");
        // abrir e cancelar: nada muda
        h.later(() -> w.removeLinkButton().doClick());
        h.waitFor(() -> h.app.activeDialog() instanceof UnlinkDialog d && d.isShowing(), "confirmação do desvínculo");
        UnlinkDialog d = (UnlinkDialog) h.edt(h.app::activeDialog);
        String all = String.join("\n", h.edt(() -> DesktopHarness.texts(d.getContentPane())));
        assertTrue(all.contains(orgId) && all.contains(worker), "identificadores completos na confirmação");
        assertTrue(all.contains("revogar o Agent na Platform (Workers)"), "consequência na Platform explicada");
        assertTrue(all.contains("não foi consultada"), all);
        assertSame(d.keepButton(), h.edt(() -> d.getRootPane().getDefaultButton()), "Enter não desvincula");
        capture(d, "PLT-desvincular-confirmacao");
        h.later(() -> d.keepButton().doClick());
        h.waitFor(() -> h.app.activeDialog() == null, "confirmação fechada");
        h.settle();
        assertTrue(java.nio.file.Files.exists(link), "cancelar não remove nada");
        assertTrue(h.edt(() -> w.removeLinkButton().isShowing()));
        // confirmar: "Não pareado"
        h.waitFor(() -> w.removeLinkButton().isEnabled(), "botão livre");
        h.later(() -> w.removeLinkButton().doClick());
        h.waitFor(() -> h.app.activeDialog() instanceof UnlinkDialog x && x.isShowing(), "confirmação de novo");
        UnlinkDialog d2 = (UnlinkDialog) h.edt(h.app::activeDialog);
        h.later(() -> d2.unlinkButton().doClick());
        h.waitFor(() -> w.pairButton().isShowing() && !w.removeLinkButton().isVisible(), "Não pareado depois do desvínculo");
        assertFalse(java.nio.file.Files.exists(link));
        assertEquals(MainWindow.PAIR_LABEL, h.edt(() -> w.pairButton().getText()));
        capture(w, "PLT-card-desvinculado");
    }
}
