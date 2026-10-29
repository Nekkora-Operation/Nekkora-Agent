package com.nekkora.agent.app;

/**
 * Opções de inicialização. A única aceita nesta versão é {@code --sem-bandeja}, que mantém o Agent
 * só com a janela (usada também para homologar o modo sem bandeja). Demais argumentos são ignorados e
 * apenas contados no log (sem o conteúdo).
 */
public record AgentOptions(boolean trayDisabled, int ignoredArguments) {

    public static final String NO_TRAY = "--sem-bandeja";

    public static AgentOptions parse(String[] args) {
        boolean noTray = false;
        int ignored = 0;
        for (String a : args) {
            if (NO_TRAY.equals(a)) noTray = true;
            else ignored++;
        }
        return new AgentOptions(noTray, ignored);
    }
}
