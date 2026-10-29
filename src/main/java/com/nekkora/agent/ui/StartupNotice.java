package com.nekkora.agent.ui;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.tokens.NekkoraThemes;
import com.nekkora.ui.Nekkora;
import com.nekkora.ui.core.Tone;
import com.nekkora.ui.icon.IconName;
import javax.swing.JComponent;

/** Avisos exibidos quando o Agent não abre a janela principal (segunda instância ou falha inicial). */
public final class StartupNotice {
    private StartupNotice() {}

    /** Segunda instância: explica onde está o Agent já aberto. Chamar na EDT; bloqueia até "Entendi". */
    public static void alreadyRunning() {
        show("O Nekkora Agent já está aberto",
                "Outra janela do Agent já está em execução para este usuário do Windows. Procure o ícone do "
                        + "Nekkora Agent na bandeja do sistema, perto do relógio (pode estar em “Mostrar ícones ocultos”). "
                        + "Esta segunda abertura será encerrada sem alterar nada.",
                IconName.INFO, Tone.INFO);
    }

    /** Falha que impede iniciar com segurança. Chamar na EDT; bloqueia até "Entendi". */
    public static void fatal(ErrorCode code) {
        String detail = code == ErrorCode.DATA_DIR_UNAVAILABLE
                ? "Verifique se a pasta %LOCALAPPDATA%\\Nekkora\\Agent existe e se a sua conta pode gravar nela."
                : "Feche outras cópias do Agent e tente novamente.";
        show("Não foi possível iniciar o Nekkora Agent", code.description() + " (" + code.code() + "). " + detail,
                IconName.X_CIRCLE, Tone.ERROR);
    }

    private static void show(String title, String text, IconName icon, Tone tone) {
        Nekkora.install(NekkoraThemes.defaultTheme());
        FittedDialog d = new FittedDialog((JComponent) null, title);
        d.setDescription(text);
        d.setMarker(icon, tone);
        d.setDialogSize(FittedDialog.Size.SMALL);
        d.addPrimary("Entendi");
        d.setAlwaysOnTop(true);
        d.showDialog();
    }
}
