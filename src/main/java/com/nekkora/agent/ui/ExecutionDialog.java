package com.nekkora.agent.ui;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionController;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.ExecutionSetup;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.tokens.Spacing;
import com.nekkora.ui.button.NekkoraButton;
import com.nekkora.ui.container.NekkoraCard;
import com.nekkora.ui.container.NekkoraScrollPane;
import com.nekkora.ui.container.NekkoraStack;
import com.nekkora.ui.core.Severity;
import com.nekkora.ui.core.Status;
import com.nekkora.ui.data.NekkoraKeyValueView;
import com.nekkora.ui.data.NekkoraStatusBadge;
import com.nekkora.ui.feedback.NekkoraInlineAlert;
import com.nekkora.ui.icon.IconName;
import com.nekkora.ui.navigation.NekkoraTabs;
import com.nekkora.ui.typography.NekkoraParagraph;
import com.nekkora.ui.typography.TextTone;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Optional;

/**
 * "Execução local": a pessoa escolhe e aprova um pacote local, o Python do projeto, a entrada e a pasta de saída, e só
 * então inicia; acompanha estado, tempo e PID e pode cancelar. Não guarda estado nem executa nada: as ações vão para o
 * {@link AgentApp}, que chama o {@link ExecutionController} fora da EDT. Fechar o diálogo não cancela a execução.
 */
final class ExecutionDialog extends FittedDialog {

    interface Actions {
        void choosePackage();

        void approve();

        void choosePython();

        void chooseInput();

        void chooseOutput();

        void start();

        void cancelRun();
    }

    static final String TITLE = "Execução local";

    private final NekkoraStack body = NekkoraStack.vertical(Spacing.SPACE_6).align(NekkoraStack.Align.STRETCH);
    private final NekkoraButton pkg = NekkoraButton.secondary("Escolher pacote…");
    private final NekkoraButton approve = NekkoraButton.secondary("Aprovar este pacote…");
    private final NekkoraButton python = NekkoraButton.secondary("Escolher Python…");
    private final NekkoraButton input = NekkoraButton.secondary("Escolher entrada…");
    private final NekkoraButton output = NekkoraButton.secondary("Pasta de saída…");
    private final NekkoraButton start;
    private final NekkoraButton cancelRun;
    private final NekkoraCard setupCard = new NekkoraCard("Preparação", null);
    private final NekkoraCard runCard = new NekkoraCard("Execução", null);

    private final ActivityPanel activity;
    private final NekkoraTabs tabs = new NekkoraTabs();

    ExecutionDialog(Component parent, Actions actions, ActivityPanel.Data activityData) {
        super(parent, TITLE);
        setDescription("Executa nesta máquina, na sua sessão, uma automação local aprovada por você. Nada começa sozinho e "
                + "a Platform não participa.");
        setDialogSize(Size.LARGE);
        pkg.addActionListener(e -> actions.choosePackage());
        approve.addActionListener(e -> actions.approve());
        python.addActionListener(e -> actions.choosePython());
        input.addActionListener(e -> actions.chooseInput());
        output.addActionListener(e -> actions.chooseOutput());
        pkg.setMnemonic(KeyEvent.VK_P);
        python.setMnemonic(KeyEvent.VK_Y);
        input.setMnemonic(KeyEvent.VK_E);
        output.setMnemonic(KeyEvent.VK_A);
        approve.getAccessibleContext().setAccessibleDescription("Mostra o que será executado e pede a sua confirmação");

        NekkoraStack rowA = NekkoraStack.horizontal(Spacing.SPACE_4).align(NekkoraStack.Align.CENTER);
        rowA.addAll(pkg, approve);
        NekkoraStack rowB = NekkoraStack.horizontal(Spacing.SPACE_4).align(NekkoraStack.Align.CENTER);
        rowB.addAll(python, input, output);
        NekkoraStack buttons = NekkoraStack.vertical(Spacing.SPACE_4).align(NekkoraStack.Align.STRETCH);
        buttons.addAll(rowA, rowB);
        body.setPadding(Spacing.SPACE_2);
        // o estado da execução fica no topo: é o que importa acompanhar sem rolar
        body.addAll(runCard, setupCard, buttons);
        NekkoraScrollPane sp = NekkoraScrollPane.verticalOnly(body);
        sp.setBorder(null);
        // OBS-001: a aba "Atividade" acompanha etapas e eventos (somente leitura); a de execução continua igual
        activity = new ActivityPanel(activityData);
        tabs.addTab("Execução", IconName.PLAY, sp);
        tabs.addTab("Atividade", IconName.PULSE, activity);
        tabs.setMnemonicAt(0, KeyEvent.VK_X);
        tabs.setMnemonicAt(1, KeyEvent.VK_T);
        tabs.getAccessibleContext().setAccessibleName("Execução local");
        setContent(tabs);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                activity.start();
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                activity.stop(); // a coleta continua no serviço; só a tela para de atualizar
            }
        });

        addSecondary("Fechar");
        cancelRun = addDanger("Cancelar execução", () -> {
            actions.cancelRun();
            return false;
        });
        start = addPrimary("Iniciar execução", () -> {
            actions.start();
            return false; // o diálogo continua aberto para acompanhar
        });
        start.setMnemonic(KeyEvent.VK_I);
        render(ExecutionSetup.empty(), Optional.empty(), false);
    }

    /** Redesenha com as escolhas atuais e a execução ativa ou a última. {@code busy}: uma ação está em andamento. */
    void render(ExecutionSetup s, Optional<ExecutionController.Snapshot> snap, boolean busy) {
        boolean running = snap.map(ExecutionController.Snapshot::running).orElse(false);
        activity.snapshot(snap);
        NekkoraKeyValueView kv = new NekkoraKeyValueView();
        kv.add("Sessão", ExecutionSetup.sessionLabel(s.session()));
        ExecutionPackage p = s.pkg();
        if (p == null) {
            kv.add("Pacote", s.packageProblem() == null ? "Nenhum escolhido" : s.packageProblem().description() + " (" + s.packageProblem().code() + ")");
        } else {
            kv.add("Pacote", p.name());
            kv.add("Tipo", ExecutionSetup.kindLabel(p));
            kv.add("Origem", ExecutionSetup.originLabel(p));
            kv.add("Como recebe dados", ExecutionSetup.modeLabel(p));
            kv.addMono("Impressão digital", p.shortFingerprint());
            if (!p.origins().isEmpty()) kv.add("Sites declarados", String.join(", ", p.origins()));
            NekkoraStatusBadge a = new NekkoraStatusBadge(s.approved() ? Status.SUCCESS : Status.PENDING, s.approved() ? "Aprovado nesta máquina" : "Não aprovado");
            a.getAccessibleContext().setAccessibleName("Aprovação: " + (s.approved() ? "aprovado nesta máquina" : "não aprovado"));
            kv.add("Aprovação", a);
        }
        if (s.python() == null) {
            kv.add("Python", s.pythonProblem() == null ? "Nenhum escolhido" : s.pythonProblem().description());
        } else {
            String fw = s.python().framework().map(v -> " · nekkora-rpa " + v).orElse(" · sem nekkora-rpa");
            kv.add("Python", ExecutionSetup.pythonFolder(s.python().python()) + " · Python " + s.python().pythonVersion() + fw + (s.pythonProblem() == null ? "" : " — incompatível"));
        }
        if (p != null && p.needsInput()) kv.add("Entrada", ExecutionSetup.shortName(s.input()));
        kv.add("Pasta de saída", ExecutionSetup.shortName(s.output()));
        NekkoraStack setup = NekkoraStack.vertical(Spacing.SPACE_5).align(NekkoraStack.Align.STRETCH);
        setup.add(kv);
        List<ErrorCode> missing = s.missing();
        if (!missing.isEmpty() && !running) {
            StringBuilder b = new StringBuilder();
            for (ErrorCode c : missing) b.append(b.isEmpty() ? "" : " ").append("• ").append(c.description());
            setup.add(new NekkoraInlineAlert(Severity.INFO, "Para iniciar falta", b.toString()));
        }
        setupCard.setContent(setup);

        NekkoraStack run = NekkoraStack.vertical(Spacing.SPACE_5).align(NekkoraStack.Align.STRETCH);
        if (snap.isEmpty()) {
            run.add(new NekkoraParagraph("Nenhuma execução nesta sessão do Agent.", TextTone.SECONDARY));
        } else {
            ExecutionRecord r = snap.get().record();
            NekkoraKeyValueView rv = new NekkoraKeyValueView();
            NekkoraStatusBadge b = new NekkoraStatusBadge(status(r.state()), r.state().label());
            b.getAccessibleContext().setAccessibleName("Estado: " + r.state().label());
            rv.add("Estado", b);
            rv.add("Tempo", ExecutionSetup.duration(snap.get().elapsedMillis()));
            if (r.owner() != null) rv.addMono("Processo (PID)", String.valueOf(r.owner().pid()));
            if (r.adopted()) rv.add("Supervisão", "Retomada depois de reinício do Agent (código de saída não observável)");
            if (r.suspensions() > 0) rv.add("Suspensões do Windows", String.valueOf(r.suspensions()));
            if (r.locks() > 0) rv.add("Bloqueios de tela", String.valueOf(r.locks()));
            if (r.exitCode() != null) rv.addMono("Código de saída", String.valueOf(r.exitCode()));
            if (r.code() != null) rv.add("Motivo", r.code().description() + " (" + r.code().code() + ")");
            run.add(rv);
            if (running) run.add(new NekkoraParagraph("Fechar esta janela não cancela a execução. A saída do processo não é guardada pelo Agent.",
                    TextTone.SECONDARY));
        }
        runCard.setContent(run);

        boolean idle = !running && !busy;
        pkg.setEnabled(idle);
        approve.setEnabled(idle && p != null && !s.approved());
        python.setEnabled(idle);
        input.setEnabled(idle && p != null && p.needsInput());
        output.setEnabled(idle);
        start.setEnabled(idle && s.canStart());
        cancelRun.setEnabled(running && !busy);
        start.getAccessibleContext().setAccessibleDescription(s.canStart() ? "Inicia a execução supervisionada" : "Indisponível: faltam escolhas");
        body.revalidate();
        body.repaint();
    }

    static Status status(ExecutionState s) {
        return switch (s) {
            case READY -> Status.PENDING;
            case STARTING, RUNNING -> Status.RUNNING;
            case SUCCEEDED -> Status.SUCCESS;
            case FAILED, TIMED_OUT -> Status.FAILED;
            case CANCELLED -> Status.DISABLED;
            case BLOCKED, OUTCOME_UNKNOWN -> Status.WARNING;
        };
    }

    // acesso para testes de desktop
    ActivityPanel activityPanel() {
        return activity;
    }

    NekkoraTabs tabs() {
        return tabs;
    }

    NekkoraButton packageButton() {
        return pkg;
    }

    NekkoraButton approveButton() {
        return approve;
    }

    NekkoraButton pythonButton() {
        return python;
    }

    NekkoraButton inputButton() {
        return input;
    }

    NekkoraButton outputButton() {
        return output;
    }

    NekkoraButton startButton() {
        return start;
    }

    NekkoraButton cancelButton() {
        return cancelRun;
    }
}
