package com.nekkora.agent.ui;

import static com.nekkora.agent.ui.DesktopHarness.capture;

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
}
