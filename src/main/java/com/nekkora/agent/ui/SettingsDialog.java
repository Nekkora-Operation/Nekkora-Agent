package com.nekkora.agent.ui;

import com.nekkora.agent.os.ShortcutPolicy;
import com.nekkora.agent.storage.AgentConfig;
import com.nekkora.agent.storage.ConfigStore;
import com.nekkora.tokens.NekkoraTheme;
import com.nekkora.tokens.NekkoraThemes;
import com.nekkora.tokens.Spacing;
import com.nekkora.ui.button.NekkoraButton;
import com.nekkora.ui.container.NekkoraScrollPane;
import com.nekkora.ui.container.NekkoraStack;
import com.nekkora.ui.core.Severity;
import com.nekkora.ui.feedback.NekkoraInlineAlert;
import com.nekkora.ui.file.NekkoraFileDialogs;
import com.nekkora.ui.input.NekkoraComboBox;
import com.nekkora.ui.input.NekkoraFormField;
import com.nekkora.ui.input.NekkoraTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Rectangle;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Configurações: tema e destinos dos atalhos. Gravar acontece fora da EDT; o diálogo só fecha depois
 * da confirmação real da gravação. Em falha, mostra o código e mantém o diálogo aberto — a
 * configuração anterior continua em uso.
 */
final class SettingsDialog extends FittedDialog {

    /** Pedido de gravação; o resultado volta pela chamada de {@link #saveFinished}. */
    record Request(AgentConfig config, boolean preserveIncompatible) {}

    static final String TITLE = "Configurações";

    private final NekkoraComboBox<NekkoraTheme> theme = new NekkoraComboBox<>(NekkoraThemes.all());
    private final NekkoraTextField platformUrl = new NekkoraTextField("https://platform.exemplo.com", 28);
    private final NekkoraTextField studioExe = new NekkoraTextField("C:\\…\\Nekkora Studio.exe", 28);
    private final NekkoraFormField platformField;
    private final NekkoraFormField studioField;
    private final NekkoraInlineAlert failure = new NekkoraInlineAlert(Severity.ERROR, "Não foi possível salvar", "");
    private final NekkoraButton browse = NekkoraButton.secondary("Procurar…");
    private final NekkoraButton save;
    private final boolean windows;
    private boolean saving;

    SettingsDialog(Component parent, AgentConfig current, ConfigStore.State state, boolean windows, Consumer<Request> onSave) {
        super(parent, TITLE);
        this.windows = windows;
        setDescription("Preferências locais deste computador. Nenhuma senha, token ou dado de execução é guardado.");
        setDialogSize(Size.MEDIUM);

        theme.setLabelProvider(NekkoraTheme::displayName);
        theme.setSelectedItem(NekkoraThemes.byId(current.themeId()).orElse(NekkoraThemes.defaultTheme()));
        platformUrl.setText(current.platformUrl() == null ? "" : current.platformUrl());
        studioExe.setText(current.studioExecutable() == null ? "" : current.studioExecutable());
        platformUrl.getAccessibleContext().setAccessibleName("Endereço da Platform");
        studioExe.getAccessibleContext().setAccessibleName("Executável do Studio");

        NekkoraFormField themeField = new NekkoraFormField("Tema", theme);
        platformField = new NekkoraFormField("Endereço da Platform", platformUrl).optional()
                .helper("Abre só quando você clicar. Sem usuário, senha ou parâmetros.");
        JPanel studioRow = new JPanel(new BorderLayout(8, 0));
        studioRow.setOpaque(false);
        studioRow.add(studioExe, BorderLayout.CENTER);
        studioRow.add(browse, BorderLayout.EAST);
        studioField = new NekkoraFormField("Executável do Studio", studioRow).optional()
                .helper("Caminho completo de um .exe. Scripts e argumentos não são aceitos.");
        browse.addActionListener(e -> NekkoraFileDialogs.openFile(this, "Executável do Studio", "Programas", "exe")
                .ifPresent(p -> studioExe.setText(p.toString())));

        NekkoraStack form = NekkoraStack.vertical(Spacing.SPACE_6).align(NekkoraStack.Align.STRETCH);
        boolean preserve = state.blocksPlainSave();
        if (preserve) {
            form.add(new NekkoraInlineAlert(Severity.WARNING, "Arquivo de configuração incompatível",
                    "O arquivo atual não pôde ser usado (" + state.code().code() + "). Ao salvar, ele será guardado como cópia "
                            + "(agent-config.json.incompativel-…) na mesma pasta e um arquivo novo será criado. Nada é apagado."));
        }
        failure.setVisible(false);
        // o erro de gravação fica no topo: nunca escondido abaixo da área visível
        form.add(failure, 0);
        form.addAll(themeField, platformField, studioField);
        NekkoraScrollPane sp = NekkoraScrollPane.verticalOnly(form);
        sp.setBorder(null);
        setContent(sp);

        addSecondary("Cancelar");
        save = addPrimary(preserve ? "Guardar cópia e salvar" : "Salvar", () -> {
            if (saving) return false;
            AgentConfig next = collect(current);
            if (next == null) return false;
            saving = true;
            setBusy(true);
            failure.setVisible(false);
            onSave.accept(new Request(next, preserve));
            return false; // fecha só quando a gravação for confirmada
        });
    }

    private AgentConfig collect(AgentConfig current) {
        boolean ok = true;
        ShortcutPolicy.Check<?> url = ShortcutPolicy.platformUrl(platformUrl.getText());
        if (url instanceof ShortcutPolicy.Rejected<?> r && r.reason() != ShortcutPolicy.Reason.NOT_CONFIGURED) {
            platformField.setError("Endereço inválido: " + r.reason().text() + ".");
            ok = false;
        } else {
            platformField.setError(null);
        }
        ShortcutPolicy.Check<?> exe = ShortcutPolicy.studioExecutableSyntax(studioExe.getText(), windows);
        if (exe instanceof ShortcutPolicy.Rejected<?> r && r.reason() != ShortcutPolicy.Reason.NOT_CONFIGURED) {
            studioField.setError("Caminho inválido: " + r.reason().text() + ".");
            ok = false;
        } else {
            studioField.setError(null);
        }
        if (!ok) return null;
        NekkoraTheme t = theme.getSelectedValue();
        return new AgentConfig(t == null ? current.themeId() : t.id(), platformUrl.getText(), studioExe.getText());
    }

    /** Chamado na EDT quando a gravação terminou. {@code missingExecutable}: o arquivo do Studio não existe. */
    void saveFinished(boolean saved, boolean missingExecutable, String failureMessage) {
        saving = false;
        setBusy(false);
        if (saved) {
            confirm();
            return;
        }
        if (missingExecutable) {
            studioField.setError("Caminho inválido: " + ShortcutPolicy.Reason.PATH_MISSING.text() + ".");
            return;
        }
        failure.setMessage(failureMessage);
        failure.setVisible(true);
        failure.revalidate();
        SwingUtilities.invokeLater(() -> failure.scrollRectToVisible(new Rectangle(0, 0, failure.getWidth(), failure.getHeight())));
    }

    private void setBusy(boolean busy) {
        save.setLoading(busy);
        theme.setEnabled(!busy);
        platformUrl.setEnabled(!busy);
        studioExe.setEnabled(!busy);
        browse.setEnabled(!busy);
    }

    // acesso para testes de desktop
    NekkoraTextField platformUrlField() {
        return platformUrl;
    }

    NekkoraTextField studioExecutableField() {
        return studioExe;
    }

    NekkoraComboBox<NekkoraTheme> themeField() {
        return theme;
    }

    NekkoraInlineAlert failureAlert() {
        return failure;
    }

    NekkoraFormField platformFormField() {
        return platformField;
    }

    NekkoraFormField studioFormField() {
        return studioField;
    }
}
