package com.nekkora.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * O que a janela principal mostra, montado sem Swing (testável sem tela). Os estados de integração
 * vêm de {@link IntegrationStatus}: nada aqui sugere pareamento, licença ativa ou execução disponível.
 */
public record MainViewModel(
        String machine,
        String version,
        String build,
        List<IntegrationStatus> integration,
        Shortcut studio,
        Shortcut platform,
        List<Notice> notices,
        String closeHint) {

    public static final String NOT_AVAILABLE = "Não disponível";

    public MainViewModel {
        integration = List.copyOf(integration);
        notices = List.copyOf(notices);
    }

    /** Gravidade de um aviso (mapeada para o componente de alerta do design system). */
    public enum Level { INFO, WARNING, ERROR }

    public record Notice(Level level, String title, String message) {}

    /**
     * Atalho: habilitado só com destino válido. {@code detail} é sempre texto visível (o motivo da
     * indisponibilidade não depende de dica flutuante nem de cor).
     */
    public record Shortcut(String label, boolean enabled, String detail) {}

    /** Entradas do modelo. */
    public record Inputs(
            Optional<String> machineName,
            BuildInfo build,
            TrayState tray,
            ErrorCode configProblem,
            boolean logsUnavailable,
            ShortcutView studio,
            ShortcutView platform) {}

    /** Situação de um atalho: estado, motivo (se indisponível) e descrição curta do destino (se válido). */
    public record ShortcutView(ShortcutState state, String reason, String target) {}

    public static MainViewModel from(Inputs in) {
        List<Notice> notices = new ArrayList<>();
        switch (in.tray()) {
            case UNSUPPORTED, FAILED -> notices.add(new Notice(Level.WARNING, "Bandeja do sistema indisponível",
                    "O ícone do Agent não pôde ser colocado na bandeja (" + in.tray().code().code() + "). "
                            + "A janela continua sendo o acesso ao Agent: fechar a minimiza na barra de tarefas. Use “Sair” para encerrar."));
            case DISABLED -> notices.add(new Notice(Level.INFO, "Bandeja desativada",
                    "O Agent foi iniciado com --sem-bandeja (" + in.tray().code().code() + "). "
                            + "Fechar a janela a minimiza na barra de tarefas. Use “Sair” para encerrar."));
            default -> { }
        }
        if (in.configProblem() != null) {
            notices.add(new Notice(Level.WARNING, "Configuração não carregada",
                    in.configProblem().description() + " (" + in.configProblem().code() + "). O arquivo foi preservado sem alterações "
                            + "e esta sessão usa os valores padrão. Em Configurações é possível guardar uma cópia dele e criar um novo."));
        }
        if (in.logsUnavailable()) {
            notices.add(new Notice(Level.WARNING, "Logs locais indisponíveis",
                    "Os logs técnicos não puderam ser gravados (" + ErrorCode.LOG_UNAVAILABLE.code() + " ou "
                            + ErrorCode.LOG_ROTATION_FAILED.code() + "). O Agent continua funcionando e o diagnóstico registra o problema."));
        }
        String hint = in.tray().canHideToTray() || in.tray() == TrayState.PENDING
                ? "Fechar a janela mantém o Agent em execução na bandeja do sistema. Para encerrar, use “Sair”."
                : "Sem bandeja: fechar a janela a minimiza na barra de tarefas. Para encerrar, use “Sair”.";
        return new MainViewModel(
                in.machineName().orElse(NOT_AVAILABLE),
                in.build().version(),
                in.build().buildLabel(),
                List.of(IntegrationStatus.values()),
                shortcut("Abrir Studio", in.studio(), "Abre "),
                shortcut("Abrir Platform", in.platform(), "Abre no navegador: "),
                notices,
                hint);
    }

    private static Shortcut shortcut(String label, ShortcutView v, String validPrefix) {
        if (v.state() == ShortcutState.VALID) return new Shortcut(label, true, validPrefix + v.target());
        return new Shortcut(label, false, "Indisponível: " + v.reason());
    }
}
