package com.nekkora.agent.os;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.core.TrayState;
import java.awt.AWTException;
import java.awt.Image;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * A07 (parte simulada): bandeja inexistente, falha ao instalar e desativação por opção. A bandeja
 * real do Windows é exercitada no teste de desktop e na homologação do pacote.
 */
class TrayControllerTest {
    private static final TrayController.Actions NOOP = new TrayController.Actions() {
        @Override
        public void open() { }

        @Override
        public void settings() { }

        @Override
        public void diagnostics() { }

        @Override
        public void exit() { }
    };

    private final List<String> calls = new ArrayList<>();

    private TrayController.Port port(boolean supported, boolean fail) {
        return new TrayController.Port() {
            @Override
            public boolean supported() {
                calls.add("supported");
                return supported;
            }

            @Override
            public TrayController.Handle add(IntFunction<Image> icon, String tooltip, TrayController.Actions a) throws AWTException {
                calls.add("add");
                if (fail) throw new AWTException("falha simulada");
                return new TrayController.Handle() {
                    @Override
                    public void notify(String title, String text) {
                        calls.add("notify");
                    }

                    @Override
                    public void remove() {
                        calls.add("remove");
                    }
                };
            }
        };
    }

    @Test
    void unsupportedTrayIsReported() {
        TrayController t = TrayController.install(port(false, false), false, s -> null, NOOP);
        assertEquals(TrayState.UNSUPPORTED, t.state());
        assertEquals(ErrorCode.TRAY_UNSUPPORTED, t.state().code());
        assertFalse(t.state().canHideToTray(), "sem bandeja a janela não pode sumir");
        assertFalse(t.notifyOnce("t", "m"));
        t.remove();
        assertEquals(List.of("supported"), calls);
    }

    @Test
    void installFailureIsReportedWithExceptionClassOnly() {
        TrayController t = TrayController.install(port(true, true), false, s -> null, NOOP);
        assertEquals(TrayState.FAILED, t.state());
        assertEquals("AWTException", t.failureType());
        assertFalse(t.state().canHideToTray());
    }

    @Test
    void disabledByOptionNeverTouchesTheTray() {
        TrayController t = TrayController.install(port(true, false), true, s -> null, NOOP);
        assertEquals(TrayState.DISABLED, t.state());
        assertTrue(calls.isEmpty());
    }

    @Test
    void activeTrayNotifiesOnlyOncePerSession() {
        TrayController t = TrayController.install(port(true, false), false, s -> null, NOOP);
        assertEquals(TrayState.ACTIVE, t.state());
        assertNull(t.failureType());
        assertTrue(t.notifyOnce("t", "m"));
        assertFalse(t.notifyOnce("t", "m"));
        assertFalse(t.notifyOnce("t", "m"));
        t.remove();
        assertEquals(List.of("supported", "add", "notify", "remove"), calls);
    }
}
