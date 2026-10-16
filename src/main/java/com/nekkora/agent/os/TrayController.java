package com.nekkora.agent.os;

import com.nekkora.agent.core.TrayState;
import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Um único ícone na bandeja do sistema, com menu Abrir / Configurações / Diagnóstico / Sair
 * (decisão D-06). Deve ser usado na EDT. Se a bandeja não existir ou a instalação falhar, o estado
 * informa o motivo e a aplicação mantém a janela como meio de acesso.
 */
public final class TrayController {

    /** Ações do menu (chamadas na EDT). */
    public interface Actions {
        void open();

        void settings();

        void diagnostics();

        void exit();
    }

    /** Ícone instalado. */
    public interface Handle {
        void notify(String title, String text);

        void remove();
    }

    /** Acesso à bandeja do sistema (substituível em testes de falha). */
    public interface Port {
        boolean supported();

        Handle add(IntFunction<Image> icon, String tooltip, Actions actions) throws AWTException;
    }

    public static final String TOOLTIP = "Nekkora Agent";
    public static final String MENU_OPEN = "Abrir o Nekkora Agent";
    public static final String MENU_SETTINGS = "Configurações…";
    public static final String MENU_DIAGNOSTICS = "Diagnóstico…";
    public static final String MENU_EXIT = "Sair";

    private final Handle handle;
    private final TrayState state;
    private final String failureType;
    private boolean noticeShown;

    private TrayController(Handle handle, TrayState state, String failureType) {
        this.handle = handle;
        this.state = state;
        this.failureType = failureType;
    }

    public static TrayController install(Port port, boolean disabledByOption, IntFunction<Image> icon, Actions actions) {
        Objects.requireNonNull(actions);
        if (disabledByOption) return new TrayController(null, TrayState.DISABLED, null);
        boolean supported;
        try {
            supported = port.supported();
        } catch (RuntimeException e) {
            return new TrayController(null, TrayState.FAILED, e.getClass().getSimpleName());
        }
        if (!supported) return new TrayController(null, TrayState.UNSUPPORTED, null);
        try {
            return new TrayController(port.add(icon, TOOLTIP, actions), TrayState.ACTIVE, null);
        } catch (AWTException | RuntimeException e) {
            return new TrayController(null, TrayState.FAILED, e.getClass().getSimpleName());
        }
    }

    public TrayState state() {
        return state;
    }

    /** Nome da classe da exceção que impediu a instalação, ou {@code null}. */
    public String failureType() {
        return failureType;
    }

    /** Mostra o aviso uma única vez por sessão. Devolve {@code true} se mostrou agora. */
    public boolean notifyOnce(String title, String text) {
        if (handle == null || noticeShown) return false;
        noticeShown = true;
        handle.notify(title, text);
        return true;
    }

    /** Remove o ícone (idempotente). */
    public void remove() {
        if (handle != null) handle.remove();
    }

    /** Bandeja real do Windows (AWT {@link SystemTray}). */
    public static final Port SYSTEM = new Port() {
        @Override
        public boolean supported() {
            return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
        }

        @Override
        public Handle add(IntFunction<Image> icon, String tooltip, Actions actions) throws AWTException {
            SystemTray tray = SystemTray.getSystemTray();
            int logical = Math.max(16, tray.getTrayIconSize().width);
            double scale = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration().getDefaultTransform().getScaleX();
            PopupMenu menu = new PopupMenu();
            menu.add(item(MENU_OPEN, actions::open));
            menu.add(item(MENU_SETTINGS, actions::settings));
            menu.add(item(MENU_DIAGNOSTICS, actions::diagnostics));
            menu.addSeparator();
            menu.add(item(MENU_EXIT, actions::exit));
            TrayIcon ti = new TrayIcon(icon.apply((int) Math.round(logical * Math.max(1.0, scale))), tooltip, menu);
            ti.setImageAutoSize(true);
            ti.addActionListener(e -> actions.open()); // duplo clique / Enter
            ti.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    // qualquer clique esquerdo: um clique logo após outro chega como duplo clique (clickCount 2)
                    // mesmo com a janela fechada no meio; abrir é idempotente
                    if (e.getButton() == MouseEvent.BUTTON1) actions.open();
                }
            });
            tray.add(ti);
            return new Handle() {
                private boolean removed;

                @Override
                public void notify(String title, String text) {
                    ti.displayMessage(title, text, TrayIcon.MessageType.NONE);
                }

                @Override
                public void remove() {
                    if (removed) return;
                    removed = true;
                    tray.remove(ti);
                }
            };
        }

        private MenuItem item(String label, Runnable action) {
            MenuItem mi = new MenuItem(label);
            mi.addActionListener(e -> action.run());
            return mi;
        }
    };
}
