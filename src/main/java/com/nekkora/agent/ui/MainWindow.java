package com.nekkora.agent.ui;

import com.nekkora.agent.core.IntegrationStatus;
import com.nekkora.agent.core.MainViewModel;
import com.nekkora.agent.platform.PlatformStatus;
import com.nekkora.tokens.Spacing;
import com.nekkora.ui.NekkoraFrame;
import com.nekkora.ui.brand.NekkoraLogo;
import com.nekkora.ui.button.NekkoraButton;
import com.nekkora.ui.container.NekkoraCard;
import com.nekkora.ui.container.NekkoraScrollPane;
import com.nekkora.ui.container.NekkoraStack;
import com.nekkora.ui.core.Severity;
import com.nekkora.ui.core.Status;
import com.nekkora.ui.data.NekkoraKeyValueView;
import com.nekkora.ui.data.NekkoraStatusBadge;
import com.nekkora.ui.feedback.NekkoraInlineAlert;
import com.nekkora.ui.theme.Scale;
import com.nekkora.ui.typography.NekkoraCaption;
import com.nekkora.ui.typography.NekkoraHeading;
import com.nekkora.ui.typography.NekkoraParagraph;
import com.nekkora.ui.typography.TextTone;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.WindowConstants;

/**
 * Janela principal compacta. Mostra estados, atalhos e ações; não guarda estado próprio: tudo vem de
 * {@link MainViewModel} via {@link #render}. Fechar não encerra — quem decide é o {@link AgentApp}.
 */
final class MainWindow extends NekkoraFrame {

    /** Ações da janela. */
    interface Listener {
        void closeRequested();

        void opened();

        void openStudio();

        void openPlatform();

        void settings();

        void diagnostics();

        void execution();

        void exit();

        /** AGENT-PLATFORM-001: controles mínimos do canal com a Platform. */
        default void pair() {}

        default void rotate() {}

        default void removeLink() {}

        default void blocked() {}
    }

    static final int WIDTH = 540;
    static final String TITLE = "Nekkora Agent";

    private final NekkoraStack body = NekkoraStack.vertical(Spacing.SPACE_6).align(NekkoraStack.Align.STRETCH);
    private final NekkoraStack notices = NekkoraStack.vertical(Spacing.SPACE_4).align(NekkoraStack.Align.STRETCH);
    private final NekkoraCaption versionCaption = new NekkoraCaption("", TextTone.SECONDARY);
    private final NekkoraCard statusCard = new NekkoraCard("Esta máquina", null);
    private final NekkoraCard shortcutsCard = new NekkoraCard("Atalhos", null);
    private final NekkoraCard platformCard = new NekkoraCard("Platform", null);
    private final NekkoraButton pair = NekkoraButton.secondary("Parear…");
    private final NekkoraButton rotate = NekkoraButton.ghost("Nova credencial…");
    private final NekkoraButton removeLink = NekkoraButton.ghost("Remover vínculo local…");
    private final NekkoraButton blockedButton = NekkoraButton.secondary("Ver envios parados…");
    private PlatformStatus platformStatus;
    private final NekkoraParagraph executionLine = new NekkoraParagraph("Nenhuma nesta sessão", TextTone.SECONDARY);
    private final NekkoraButton execution = NekkoraButton.secondary("Execução local…");
    private final NekkoraParagraph closeHint = new NekkoraParagraph("", TextTone.MUTED);
    private final NekkoraButton studio = NekkoraButton.secondary("Abrir Studio");
    private final NekkoraButton platform = NekkoraButton.secondary("Abrir Platform");
    private final NekkoraButton settings = NekkoraButton.secondary("Configurações");
    private final NekkoraButton diagnostics = NekkoraButton.secondary("Diagnóstico");
    private final NekkoraButton exit = NekkoraButton.ghost("Sair");
    private final NekkoraScrollPane scroll;
    private MainViewModel model;

    MainWindow(Listener listener) {
        super(TITLE);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        // largura mínima = largura de projeto: nenhum estado ou selo é cortado ao estreitar a janela
        setMinimumSize(new Dimension(Scale.px(WIDTH), Scale.px(300)));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                listener.closeRequested();
            }

            @Override
            public void windowOpened(WindowEvent e) {
                listener.opened();
            }
        });

        studio.addActionListener(e -> listener.openStudio());
        platform.addActionListener(e -> listener.openPlatform());
        settings.addActionListener(e -> listener.settings());
        diagnostics.addActionListener(e -> listener.diagnostics());
        execution.addActionListener(e -> listener.execution());
        execution.setMnemonic(KeyEvent.VK_E);
        execution.getAccessibleContext().setAccessibleDescription("Escolher, aprovar, iniciar e acompanhar uma automação local");
        exit.addActionListener(e -> listener.exit());
        pair.addActionListener(e -> listener.pair());
        rotate.addActionListener(e -> listener.rotate());
        removeLink.addActionListener(e -> listener.removeLink());
        blockedButton.addActionListener(e -> listener.blocked());
        pair.setMnemonic(KeyEvent.VK_R);
        rotate.setMnemonic(KeyEvent.VK_N);
        removeLink.setMnemonic(KeyEvent.VK_M);
        blockedButton.setMnemonic(KeyEvent.VK_V);
        pair.getAccessibleContext().setAccessibleDescription("Liga esta máquina à organização na Platform com um código de pareamento");
        removeLink.getAccessibleContext().setAccessibleDescription("Apaga a credencial guardada nesta máquina; na Platform nada muda");
        studio.setMnemonic(KeyEvent.VK_T);
        platform.setMnemonic(KeyEvent.VK_P);
        settings.setMnemonic(KeyEvent.VK_C);
        diagnostics.setMnemonic(KeyEvent.VK_D);
        exit.setMnemonic(KeyEvent.VK_S);
        exit.getAccessibleContext().setAccessibleDescription("Encerra o Nekkora Agent e remove o ícone da bandeja");

        NekkoraLogo logo = new NekkoraLogo(NekkoraLogo.Variant.APP_ICON, 40);
        NekkoraStack titles = NekkoraStack.vertical(Spacing.SPACE_1);
        titles.addAll(new NekkoraHeading(TITLE, NekkoraHeading.Level.H2), versionCaption);
        NekkoraStack header = NekkoraStack.horizontal(Spacing.SPACE_5).align(NekkoraStack.Align.CENTER);
        header.addAll(logo, titles);

        NekkoraStack actions = NekkoraStack.horizontal(Spacing.SPACE_4).align(NekkoraStack.Align.CENTER);
        actions.addAll(execution, settings, diagnostics);
        actions.addGlue();
        actions.add(exit);

        body.setPadding(Spacing.SPACE_7);
        // DESKTOP-001: a execução local é uma linha em "Esta máquina" e um botão nas ações (a janela continua compacta)
        // AGENT-PLATFORM-001: o canal com a Platform tem card próprio (vínculo, comunicação e envios; nunca desfecho de automação)
        platformCard.setVisible(false);
        body.addAll(header, notices, statusCard, platformCard, shortcutsCard, actions, closeHint);
        scroll = NekkoraScrollPane.verticalOnly(body);
        scroll.setBorder(null);
        getContentPane().add(scroll, BorderLayout.CENTER);
    }

    void render(MainViewModel vm) {
        this.model = vm;
        versionCaption.setText("Versão " + vm.version() + " · " + vm.build());

        notices.removeAll();
        for (MainViewModel.Notice n : vm.notices()) {
            Severity s = switch (n.level()) {
                case INFO -> Severity.INFO;
                case WARNING -> Severity.WARNING;
                case ERROR -> Severity.ERROR;
            };
            notices.add(new NekkoraInlineAlert(s, n.title(), n.message()));
        }
        notices.setVisible(!vm.notices().isEmpty());

        NekkoraKeyValueView rows = new NekkoraKeyValueView();
        rows.add("Máquina", vm.machine());
        rows.add("Versão", vm.version());
        rows.add("Execução local", executionLine);
        for (IntegrationStatus s : vm.integration()) {
            NekkoraStatusBadge badge = new NekkoraStatusBadge(s == IntegrationStatus.LICENSE ? Status.PENDING : Status.DISABLED, s.value());
            badge.getAccessibleContext().setAccessibleName(s.label() + ": " + s.value());
            rows.add(s.label(), badge);
        }
        NekkoraStack statusContent = NekkoraStack.vertical(Spacing.SPACE_5).align(NekkoraStack.Align.STRETCH);
        statusContent.addAll(rows, new NekkoraParagraph(IntegrationStatus.EXPLANATION, TextTone.SECONDARY));
        statusCard.setContent(statusContent);

        NekkoraStack sc = NekkoraStack.vertical(Spacing.SPACE_5).align(NekkoraStack.Align.STRETCH);
        sc.addAll(shortcutRow(studio, vm.studio()), shortcutRow(platform, vm.platform()));
        shortcutsCard.setContent(sc);

        closeHint.setText(vm.closeHint());
        body.revalidate();
        body.repaint();
    }

    /** Card "Platform": vínculo, comunicação, envios e bloqueios (na EDT). */
    void renderPlatform(PlatformStatus st) {
        this.platformStatus = st;
        NekkoraKeyValueView rows = new NekkoraKeyValueView();
        NekkoraStatusBadge link = new NekkoraStatusBadge(PlatformText.linkStatus(st.link()), PlatformText.link(st.link()));
        link.getAccessibleContext().setAccessibleName("Vínculo: " + PlatformText.link(st.link()));
        rows.add("Vínculo", link);
        if (st.host() != null) rows.add("Endereço", st.host());
        if (st.paired()) {
            NekkoraStatusBadge conn = new NekkoraStatusBadge(PlatformText.connectivityStatus(st), PlatformText.connectivity(st));
            conn.getAccessibleContext().setAccessibleName("Comunicação: " + PlatformText.connectivity(st));
            rows.add("Comunicação", conn);
            rows.add("Identificação", PlatformText.identity(st));
            rows.add("Envios", PlatformText.deliveries(st));
            rows.add("Último sincronismo", PlatformText.lastSync(st));
            if (st.incompleteRuns() > 0) rows.add("Histórico incompleto", st.incompleteRuns() + (st.incompleteRuns() == 1 ? " execução" : " execuções")
                    + " com perda local declarada");
        }
        NekkoraStack content = NekkoraStack.vertical(Spacing.SPACE_5).align(NekkoraStack.Align.STRETCH);
        content.add(rows);
        if (!st.blocked().isEmpty()) {
            content.add(new NekkoraInlineAlert(Severity.WARNING, "Envio parado", st.blocked().size() + (st.blocked().size() == 1 ? " execução precisa" : " execuções precisam")
                    + " de decisão para continuar o envio. A automação não é afetada."));
        } else if (st.problem() != null && st.link() != PlatformStatus.LinkState.PAIRED) {
            content.add(new NekkoraInlineAlert(st.link() == PlatformStatus.LinkState.NOT_PAIRED ? Severity.INFO : Severity.WARNING, "Platform",
                    PlatformText.problem(st.problem())));
        } else if (st.problem() != null) {
            content.add(new NekkoraParagraph(PlatformText.problem(st.problem()), TextTone.SECONDARY));
        }
        NekkoraStack buttons = NekkoraStack.horizontal(Spacing.SPACE_4).align(NekkoraStack.Align.CENTER);
        boolean any = false;
        if (st.link() == PlatformStatus.LinkState.NOT_PAIRED) {
            buttons.add(pair);
            any = true;
        }
        if (st.link() == PlatformStatus.LinkState.PAIRED || st.link() == PlatformStatus.LinkState.REFUSED) {
            buttons.add(rotate);
            any = true;
        }
        if (st.link() == PlatformStatus.LinkState.REFUSED || st.link() == PlatformStatus.LinkState.DESTINATION_CHANGED
                || st.link() == PlatformStatus.LinkState.CONTRACT_REFUSED) {
            buttons.add(removeLink);
            any = true;
        }
        if (!st.blocked().isEmpty()) {
            buttons.add(blockedButton);
            any = true;
        }
        if (any) content.add(buttons);
        platformCard.setContent(content);
        platformCard.setVisible(true);
        body.revalidate();
        body.repaint();
    }

    PlatformStatus platformStatus() {
        return platformStatus;
    }

    NekkoraButton pairButton() {
        return pair;
    }

    NekkoraButton rotateButton() {
        return rotate;
    }

    NekkoraButton removeLinkButton() {
        return removeLink;
    }

    private static JComponent shortcutRow(NekkoraButton button, MainViewModel.Shortcut s) {
        button.setEnabled(s.enabled());
        button.getAccessibleContext().setAccessibleDescription(s.detail());
        NekkoraParagraph detail = new NekkoraParagraph(s.detail(), s.enabled() ? TextTone.SECONDARY : TextTone.MUTED);
        JPanel row = new JPanel(new BorderLayout(Scale.px(Spacing.SPACE_5), 0));
        row.setOpaque(false);
        JPanel west = new JPanel(new BorderLayout());
        west.setOpaque(false);
        west.add(button, BorderLayout.NORTH);
        row.add(west, BorderLayout.WEST);
        row.add(detail, BorderLayout.CENTER);
        return row;
    }

    MainViewModel model() {
        return model;
    }

    /** Linha de estado da execução local (só estado, tempo e PID; nada da automação). */
    void renderExecution(String line) {
        if (line.equals(executionLine.getText())) return;
        executionLine.setText(line);
        executionLine.getAccessibleContext().setAccessibleName("Execução local: " + line);
        body.revalidate();
        body.repaint();
    }

    String executionText() {
        return executionLine.getText();
    }

    NekkoraButton executionButton() {
        return execution;
    }

    /** Dimensiona pelo conteúdo, limitado à área útil da tela, e centraliza. */
    void fitToScreen() {
        Rectangle usable = usableBounds();
        int w = Math.min(Scale.px(WIDTH), usable.width);
        // validate() só faz layout com a janela já exibível (peer nativo): pack() cria o peer sem mostrá-la
        if (!isDisplayable()) pack();
        setSize(w, Math.min(Scale.px(720), usable.height));
        // parágrafos calculam a altura pela largura já aplicada: dois passes de layout antes de medir
        for (int pass = 0; pass < 2; pass++) {
            body.invalidate();
            validate();
        }
        int chrome = getHeight() - scroll.getViewport().getHeight();
        int content = body.getPreferredSize().height;
        setSize(w, Math.min(content + chrome + Scale.px(2), usable.height));
        validate();
        setLocationRelativeTo(null);
    }

    private Rectangle usableBounds() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(b.x + in.left, b.y + in.top, b.width - in.left - in.right, b.height - in.top - in.bottom);
    }

    /** Foco inicial: primeiro atalho habilitado ou Configurações. */
    void focusFirstAction() {
        if (studio.isEnabled()) studio.requestFocusInWindow();
        else if (platform.isEnabled()) platform.requestFocusInWindow();
        else settings.requestFocusInWindow();
    }

    // acesso para testes de desktop
    NekkoraButton studioButton() {
        return studio;
    }

    NekkoraButton platformButton() {
        return platform;
    }

    NekkoraButton settingsButton() {
        return settings;
    }

    NekkoraButton diagnosticsButton() {
        return diagnostics;
    }

    NekkoraButton exitButton() {
        return exit;
    }

    NekkoraScrollPane scrollPane() {
        return scroll;
    }
}
