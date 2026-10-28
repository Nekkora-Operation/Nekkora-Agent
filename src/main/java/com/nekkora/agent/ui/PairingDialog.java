package com.nekkora.agent.ui;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.tokens.Spacing;
import com.nekkora.ui.button.NekkoraButton;
import com.nekkora.ui.container.NekkoraScrollPane;
import com.nekkora.ui.container.NekkoraStack;
import com.nekkora.ui.core.Severity;
import com.nekkora.ui.data.NekkoraKeyValueView;
import com.nekkora.ui.feedback.NekkoraInlineAlert;
import com.nekkora.ui.input.NekkoraFormField;
import com.nekkora.ui.input.NekkoraPasswordField;
import com.nekkora.ui.typography.NekkoraParagraph;
import com.nekkora.ui.typography.TextTone;
import java.awt.Component;
import java.util.function.Consumer;

/**
 * Pareamento (ou troca de credencial) iniciado pela pessoa: cola o código gerado na Platform. O código só existe no
 * campo de senha e no pedido; nunca é exibido, registrado nem guardado em texto. O pedido é processado fora da EDT e o
 * diálogo só fecha com a resposta local (código no formato certo e material guardado com proteção do sistema); a
 * confirmação da Platform segue em segundo plano e aparece no card "Platform".
 */
final class PairingDialog extends FittedDialog {

    enum Mode { PAIR, ROTATE }

    static final String TITLE_PAIR = "Parear com a Platform";
    static final String TITLE_ROTATE = "Nova credencial da máquina";

    private final NekkoraPasswordField code = new NekkoraPasswordField("nkp1.…");
    private final NekkoraFormField codeField;
    private final NekkoraInlineAlert failure = new NekkoraInlineAlert(Severity.ERROR, "Não foi possível começar", "");
    private final NekkoraButton submit;
    private boolean busy;

    PairingDialog(Component parent, Mode mode, String host, boolean secretStore, Consumer<char[]> onSubmit) {
        super(parent, mode == Mode.PAIR ? TITLE_PAIR : TITLE_ROTATE);
        setDescription(mode == Mode.PAIR
                ? "Liga esta máquina à sua organização na Platform. Peça a quem administra a organização um código de pareamento."
                : "Troca a credencial desta máquina por uma nova. Peça a quem administra a organização um código de rotação.");
        setDialogSize(Size.MEDIUM);
        code.getAccessibleContext().setAccessibleName(mode == Mode.PAIR ? "Código de pareamento" : "Código de rotação");
        codeField = new NekkoraFormField(mode == Mode.PAIR ? "Código de pareamento" : "Código de rotação", code)
                .helper("Colado como recebido. Não é exibido nem guardado em texto.");
        NekkoraKeyValueView where = new NekkoraKeyValueView();
        where.add("Platform", host == null ? "Endereço https não configurado" : host);
        NekkoraStack form = NekkoraStack.vertical(Spacing.SPACE_6).align(NekkoraStack.Align.STRETCH);
        failure.setVisible(false);
        form.add(failure);
        if (host == null) {
            form.add(new NekkoraInlineAlert(Severity.WARNING, "Endereço da Platform", "Informe o endereço https da Platform em Configurações antes de parear."));
        }
        if (!secretStore) {
            form.add(new NekkoraInlineAlert(Severity.WARNING, "Proteção do sistema indisponível", ErrorCode.PLT_SECRET_STORE_UNAVAILABLE.description()));
        }
        form.addAll(where, codeField, new NekkoraParagraph(mode == Mode.PAIR
                ? "A credencial da máquina é gerada aqui e guardada com a proteção do Windows para o seu usuário. A execução local continua "
                        + "funcionando sem a Platform."
                : "A credencial atual só é substituída depois que a Platform confirmar a nova.", TextTone.SECONDARY));
        NekkoraScrollPane sp = NekkoraScrollPane.verticalOnly(form);
        sp.setBorder(null);
        setContent(sp);
        addSecondary("Cancelar");
        submit = addPrimary(mode == Mode.PAIR ? "Parear" : "Trocar credencial", () -> {
            if (busy) return false;
            char[] c = code.getPassword();
            code.setText("");
            if (c.length == 0) {
                codeField.setError("Cole o código recebido.");
                return false;
            }
            codeField.setError(null);
            busy = true;
            submit().setLoading(true);
            failure.setVisible(false);
            onSubmit.accept(c);
            return false; // fecha quando a resposta local voltar
        });
        submit.setEnabled(host != null && secretStore);
    }

    private NekkoraButton submit() {
        return submit;
    }

    /** Resposta local do pedido (na EDT). {@code problem} nulo: pedido aceito e diálogo fechado. */
    void finished(ErrorCode problem) {
        busy = false;
        submit.setLoading(false);
        if (problem == null) {
            confirm();
            return;
        }
        failure.setMessage(PlatformText.problem(problem));
        failure.setVisible(true);
        failure.revalidate();
    }

    // acesso para testes de desktop e homologação
    NekkoraPasswordField codeInput() {
        return code;
    }

    NekkoraButton submitButton() {
        return submit;
    }
}
