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
    // ----- DESKTOP-001: execução local supervisionada -----
    EXEC_PACKAGE_NOT_FOUND("AGT-EXE-001", "A pasta do pacote não existe ou não pôde ser lida."),
    EXEC_PACKAGE_UNKNOWN("AGT-EXE-002", "A pasta não é um pacote reconhecido (manifesto do Studio ou descritor de laboratório)."),
    EXEC_PACKAGE_INVALID("AGT-EXE-003", "O descritor do pacote é malformado ou de versão não suportada."),
    EXEC_PACKAGE_CHANGED("AGT-EXE-004", "Um arquivo do pacote difere do que o descritor registra (alterado depois da geração)."),
    EXEC_PACKAGE_UNSAFE("AGT-EXE-005", "O pacote tem caminho fora da pasta, ligação simbólica ou tamanho acima do limite."),
    EXEC_SECRETS_UNSUPPORTED("AGT-EXE-006", "O pacote precisa de segredos; esta versão do Agent não os fornece."),
    EXEC_NOT_APPROVED("AGT-EXE-007", "O pacote não foi aprovado nesta máquina, ou mudou depois da aprovação."),
    EXEC_PYTHON_INVALID("AGT-EXE-008", "O Python escolhido não existe, não é executável ou não respondeu à verificação."),
    EXEC_PYTHON_INCOMPATIBLE("AGT-EXE-009", "O Python escolhido não tem a versão exigida pelo pacote."),
    EXEC_INPUT_MISSING("AGT-EXE-010", "A entrada exigida pelo pacote não foi escolhida ou não existe."),
    EXEC_OUTPUT_INVALID("AGT-EXE-011", "A pasta de saída não foi escolhida, não existe ou fica dentro do pacote ou do Agent."),
    EXEC_SESSION_UNAVAILABLE("AGT-EXE-012", "Não há sessão interativa do usuário disponível para executar."),
    EXEC_SESSION_LOCKED("AGT-EXE-013", "A sessão está bloqueada: a execução não começa com a tela bloqueada."),
    EXEC_ALREADY_RUNNING("AGT-EXE-014", "Já há uma execução em andamento neste usuário."),
    EXEC_LAUNCH_FAILED("AGT-EXE-015", "O processo não pôde ser iniciado."),
    EXEC_STORAGE_FAILED("AGT-EXE-016", "O registro da execução não pôde ser gravado."),
    EXEC_UNSUPPORTED_OS("AGT-EXE-017", "A execução local só é suportada no Windows nesta versão."),
    EXEC_EXIT_NONZERO("AGT-EXE-020", "O processo terminou com código de saída diferente de zero."),
    EXEC_NO_PROTOCOL("AGT-EXE-021", "O processo terminou sem resultado estruturado: o desfecho não é presumido."),
    EXEC_REPORTED_FAILURE("AGT-EXE-022", "O resultado estruturado do processo informa falha."),
    EXEC_TIMEOUT("AGT-EXE-023", "O prazo da execução terminou e o processo foi encerrado."),
    EXEC_CANCELLED("AGT-EXE-024", "A execução foi cancelada pela pessoa operadora."),
    EXEC_AGENT_RESTARTED("AGT-EXE-025", "O Agent reiniciou durante a execução; o fim não foi observado."),
    EXEC_SUSPENDED("AGT-EXE-026", "O Windows foi suspenso durante a execução."),
    // ----- DESKTOP-001 OBS-001: atividade da execução (telemetria; nunca muda o desfecho da automação) -----
    OBS_SOURCE_UNAVAILABLE("AGT-OBS-001", "Os eventos de atividade da automação não puderam ser lidos (arquivo ausente ou sem permissão)."),
    OBS_EVENT_INVALID("AGT-OBS-002", "Um evento de atividade foi recusado (linha parcial, corrompida, grande demais ou fora do contrato)."),
    OBS_SOURCE_UNSAFE("AGT-OBS-003", "O arquivo de eventos não é um arquivo comum da execução (ligação, junção ou outro dono) e não foi lido."),
    OBS_SOURCE_REPLACED("AGT-OBS-004", "O arquivo de eventos foi truncado, trocado ou rotacionado durante a leitura; a coleta dessa origem parou."),
    OBS_RETENTION_LIMIT("AGT-OBS-005", "O limite de eventos guardados desta execução foi atingido; os seguintes foram descartados e contados."),
    OBS_AGENT_EVENTS_LIMIT("AGT-OBS-006", "O registro do supervisor atingiu o limite de 500 eventos; os seguintes do supervisor não foram gravados."),
    OBS_SCHEMA_UNSUPPORTED("AGT-OBS-007", "Os eventos do Framework são de uma versão de contrato não suportada e não foram exibidos."),
    OBS_CORRELATION_CONFLICT("AGT-OBS-008", "Eventos de outra execução do Framework, ou anteriores ao início, foram recusados."),
    OBS_STORE_FAILED("AGT-OBS-009", "O histórico de atividade não pôde ser gravado; a automação não é afetada."),
    OBS_HISTORY_PURGED("AGT-OBS-010", "A atividade detalhada desta execução foi removida pela retenção do histórico."),
    OBS_UNKNOWN_CONTENT("AGT-OBS-011", "Campos ou códigos desconhecidos dos eventos foram descartados e contados."),
    OBS_EXPORT_FAILED("AGT-OBS-012", "A atividade não pôde ser exportada."),
    OBS_SEQUENCE_GAP("AGT-OBS-013", "O histórico de atividade tem lacuna de sequência ou linha ilegível."),
    OBS_HISTORY_UNAVAILABLE("AGT-OBS-014", "O histórico de execuções não pôde ser lido por completo; isto não significa que não haja execuções."),
    // AGENT-PLATFORM-001: canal da máquina (contrato Platform–Agent 1.0). Falha de transporte nunca muda uma execução.
    PLT_SECRET_STORE_UNAVAILABLE("AGT-PLT-001", "O armazenamento protegido do sistema não está disponível: o pareamento não é possível nesta máquina."),
    PLT_DESTINATION_INVALID("AGT-PLT-002", "O endereço da Platform não é uma origem https válida."),
    PLT_CODE_INVALID("AGT-PLT-003", "O código informado não tem o formato de um código de pareamento da Platform."),
    PLT_CODE_REFUSED("AGT-PLT-004", "A Platform recusou o código (inválido, vencido, cancelado ou já usado com outro segredo)."),
    PLT_WORKER_UNAVAILABLE("AGT-PLT-005", "O Worker do código já está associado a outro Agent ativo."),
    PLT_ALREADY_PAIRED("AGT-PLT-006", "Este Agent já está pareado; remova o vínculo local antes de parear de novo."),
    PLT_NOT_PAIRED("AGT-PLT-007", "Este Agent não está pareado com a Platform."),
    PLT_CREDENTIAL_REFUSED("AGT-PLT-008", "A Platform recusou a credencial da máquina (revogada, vencida ou Agent encerrado)."),
    PLT_DESTINATION_CHANGED("AGT-PLT-009", "O endereço da Platform na configuração não é o do pareamento: nada é enviado até a pessoa decidir."),
    PLT_UNREACHABLE("AGT-PLT-010", "Sem conexão com a Platform (rede, nome, TLS ou tempo esgotado)."),
    PLT_SERVER_UNAVAILABLE("AGT-PLT-011", "A Platform respondeu com indisponibilidade; o envio será repetido."),
    PLT_RATE_LIMITED("AGT-PLT-012", "A Platform pediu para esperar antes de novo envio."),
    PLT_CONTRACT_REFUSED("AGT-PLT-013", "A Platform não aceita esta versão do contrato do Agent."),
    PLT_RESPONSE_INVALID("AGT-PLT-014", "A resposta da Platform é malformada ou incoerente com o protocolo; nada foi descartado."),
    PLT_AUTHORIZATION_REFUSED("AGT-PLT-015", "A Platform recusou o registro da execução sob a autorização escolhida."),
    PLT_REGISTRATION_CONFLICT("AGT-PLT-016", "A Platform já tem esta execução com outra autorização ou outro pacote."),
    PLT_REPORT_CONFLICT("AGT-PLT-017", "A Platform registrou o relatório do supervisor como contraditório; o estado dela não mudou."),
    PLT_EVENT_INVALID("AGT-PLT-018", "A Platform recusou um evento como inválido; a entrega desta execução parou nele."),
    PLT_EVENT_CONFLICT("AGT-PLT-019", "A Platform já tinha um evento com a mesma sequência e outro conteúdo; o original prevaleceu."),
    PLT_HISTORY_GAP("AGT-PLT-020", "O histórico local tem uma lacuna sem perda registrada; a entrega parou até uma decisão."),
    PLT_STORAGE_FAILED("AGT-PLT-021", "O progresso local da entrega não pôde ser gravado; nada foi dado por entregue."),
    PLT_ROTATION_REFUSED("AGT-PLT-022", "A Platform recusou o código de rotação; a credencial atual continua."),
    PLT_LOSS_DECLARED("AGT-PLT-023", "Eventos que deixaram de existir localmente foram declarados perdidos à Platform."),
    PLT_LOCAL_HISTORY_REMOVED("AGT-PLT-024", "Os registros locais desta execução não existem mais; a entrega não pode continuar."),
    PLT_PAYLOAD_INVALID("AGT-PLT-025", "A Platform recusou o conteúdo enviado como inválido; a entrega desta execução parou."),
    PLT_LINK_MISMATCH("AGT-PLT-026", "A entrega pertence a um vínculo anterior desta máquina e não é enviada pelo vínculo atual."),
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
