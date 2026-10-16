package com.nekkora.agent.core;

/**
 * Catálogo fechado de códigos de erro do Agent. O código e a descrição são seguros para logs,
 * diagnóstico e interface: não carregam caminhos, endereços, nomes nem mensagens de exceção.
 */
public enum ErrorCode {
    DATA_DIR_UNAVAILABLE("AGT-DATA-001", "O diretório de dados do Agent não pôde ser criado ou usado."),
    LOCK_FAILED("AGT-LOCK-002", "A trava de instância única não pôde ser obtida."),
    CONFIG_CORRUPTED("AGT-CFG-002", "O arquivo de configuração está malformado."),
    CONFIG_UNSUPPORTED("AGT-CFG-003", "O arquivo de configuração é de uma versão não suportada."),
    CONFIG_UNREADABLE("AGT-CFG-004", "O arquivo de configuração não pôde ser lido."),
    CONFIG_WRITE_FAILED("AGT-CFG-005", "A configuração não pôde ser gravada."),
    CONFIG_PRESERVE_FAILED("AGT-CFG-006", "A cópia do arquivo de configuração incompatível não pôde ser criada."),
    LOG_UNAVAILABLE("AGT-LOG-001", "Os logs locais não puderam ser abertos ou gravados."),
    LOG_ROTATION_FAILED("AGT-LOG-002", "A rotação dos logs locais falhou; o registro em arquivo foi suspenso."),
    LOG_EVENTS_DROPPED("AGT-LOG-003", "Eventos de log foram descartados por excesso de volume."),
    TRAY_UNSUPPORTED("AGT-TRAY-001", "A bandeja do sistema não está disponível nesta sessão."),
    TRAY_FAILED("AGT-TRAY-002", "O ícone da bandeja não pôde ser instalado."),
    TRAY_DISABLED("AGT-TRAY-003", "A bandeja foi desativada pela opção de inicialização --sem-bandeja."),
    MACHINE_NAME_UNAVAILABLE("AGT-HOST-001", "O nome da máquina não está disponível no ambiente local."),
    STUDIO_INVALID("AGT-SC-002", "O executável configurado para o Studio não é válido."),
    STUDIO_LAUNCH_FAILED("AGT-SC-003", "O Studio não pôde ser iniciado."),
    PLATFORM_INVALID("AGT-SC-012", "O endereço configurado para a Platform não é válido."),
    PLATFORM_OPEN_FAILED("AGT-SC-013", "O navegador não pôde abrir o endereço da Platform."),
    BROWSER_UNAVAILABLE("AGT-SC-014", "Não há navegador padrão acessível nesta sessão."),
    DIAGNOSTIC_EXPORT_FAILED("AGT-DIAG-001", "O arquivo de diagnóstico não pôde ser gravado."),
    UNEXPECTED("AGT-APP-001", "Falha interna inesperada.");

    private final String code;
    private final String description;

    ErrorCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /** Código estável, por exemplo {@code AGT-CFG-002}. */
    public String code() {
        return code;
    }

    /** Descrição em português, sem dados do ambiente. */
    public String description() {
        return description;
    }
}
