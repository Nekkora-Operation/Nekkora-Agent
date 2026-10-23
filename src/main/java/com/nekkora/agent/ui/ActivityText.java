package com.nekkora.agent.ui;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.obs.ActivityEvent;
import java.util.Map;

/**
 * Textos em português da aba "Atividade". Os identificadores e códigos do envelope continuam em inglês (são o contrato);
 * aqui só a apresentação. Código sem tradução aparece como está — é um identificador validado, nunca um valor.
 */
final class ActivityText {
    private ActivityText() {}

    private static final Map<String, String> EVENTS = Map.ofEntries(
            // supervisor do Agent
            Map.entry("STARTING", "Iniciando"), Map.entry("RUNNING", "Processo em andamento"), Map.entry("SUCCEEDED", "Concluída com sucesso"),
            Map.entry("FAILED", "Falhou"), Map.entry("CANCELLED", "Cancelada"), Map.entry("TIMED_OUT", "Tempo esgotado"), Map.entry("BLOCKED", "Bloqueada"),
            Map.entry("OUTCOME_UNKNOWN", "Resultado desconhecido"), Map.entry("SUSPENSION_DETECTED", "Suspensão do Windows detectada"),
            Map.entry("SESSION_LOCKED", "Tela bloqueada"), Map.entry("SESSION_UNLOCKED", "Tela desbloqueada"), Map.entry("TERMINATED", "Processos encerrados"),
            Map.entry("RECONCILE", "Reconciliação após reinício"), Map.entry("ADOPTED", "Execução retomada (adotada)"),
            // Framework
            Map.entry("ENGINE_STARTED", "Motor iniciado"), Map.entry("ENGINE_COMPLETED", "Motor encerrado"), Map.entry("PHASE_STARTED", "Fase iniciada"),
            Map.entry("PHASE_COMPLETED", "Fase concluída"), Map.entry("PHASE_FAILED", "Fase falhou"), Map.entry("TRANSACTION_STARTED", "Transação iniciada"),
            Map.entry("TRANSACTION_COMPLETED", "Transação concluída"), Map.entry("TRANSACTION_FAILED", "Transação falhou"),
            Map.entry("TRANSACTION_SKIPPED", "Transação pulada"), Map.entry("TRANSACTION_RETRY_SCHEDULED", "Nova tentativa da transação"),
            Map.entry("TRANSACTION_RETRY_DENIED", "Nova tentativa recusada"), Map.entry("RECONCILIATION_STARTED", "Reconciliação iniciada"),
            Map.entry("RECONCILIATION_COMPLETED", "Reconciliação concluída"), Map.entry("TARGET_RESOLVED", "Elemento localizado"),
            Map.entry("TARGET_REJECTED", "Elemento recusado"), Map.entry("ACTION_STARTED", "Ação iniciada"), Map.entry("ACTION_COMPLETED", "Ação concluída"),
            Map.entry("ACTION_FAILED", "Ação falhou"), Map.entry("RETRY_SCHEDULED", "Nova tentativa agendada"), Map.entry("RETRY_DENIED", "Nova tentativa recusada"),
            Map.entry("SINK_COMMITTED", "Resultado gravado"), Map.entry("SINK_UNCERTAIN", "Gravação incerta"), Map.entry("SINK_FAILED", "Gravação falhou"),
            Map.entry("CHECKPOINT_SAVED", "Ponto de controle salvo"), Map.entry("CHECKPOINT_LOADED", "Ponto de controle carregado"),
            Map.entry("SECURITY_BLOCKED", "Bloqueado pela política de segurança"), Map.entry("CLEANUP_COMPLETED", "Limpeza concluída"),
            Map.entry("CLEANUP_FAILED", "Limpeza falhou"), Map.entry("OUTPUT_COMMITTED", "Saída consolidada"), Map.entry("OUTPUT_WITHHELD", "Saída retida"),
            Map.entry("OUTPUT_FAILED", "Saída falhou"),
            // coletor
            Map.entry("COLLECTION_STARTED", "Coleta de atividade iniciada"), Map.entry("COLLECTION_RESUMED", "Coleta retomada após reinício do Agent"),
            Map.entry("COLLECTION_COMPLETED", "Coleta de atividade encerrada"), Map.entry("EVENT_GAP", "Lacuna: eventos não guardados"),
            Map.entry("EVENT_INVALID", "Evento recusado"), Map.entry("SOURCE_UNAVAILABLE", "Eventos indisponíveis"),
            Map.entry("SOURCE_AVAILABLE", "Eventos voltaram a ser lidos"), Map.entry("SOURCE_UNSAFE", "Arquivo de eventos recusado (inseguro)"),
            Map.entry("SOURCE_REPLACED", "Arquivo de eventos trocado ou truncado"), Map.entry("HISTORY_PURGED", "Atividade removida pela retenção"));

    private static final Map<String, String> ACTIONS = Map.ofEntries(Map.entry("click", "clique"), Map.entry("double_click", "clique duplo"),
            Map.entry("fill", "preenchimento"), Map.entry("wait_visible", "espera (visível)"), Map.entry("wait_hidden", "espera (oculto)"),
            Map.entry("exists", "verificação"), Map.entry("is_visible", "verificação (visível)"), Map.entry("read_text", "leitura"),
            Map.entry("navigate", "navegação"), Map.entry("download", "download"), Map.entry("mark", "marcação"),
            Map.entry("read_text_matching", "leitura com padrão"), Map.entry("press", "tecla"), Map.entry("read_items", "leitura de itens"),
            Map.entry("current_address", "endereço da página"));

    private static final Map<String, String> REASONS = Map.ofEntries(Map.entry("MALFORMED_JSON", "linha corrompida (JSON inválido)"),
            Map.entry("LINE_TOO_LONG", "linha grande demais"), Map.entry("ENCODING_INVALID", "texto fora de UTF-8"),
            Map.entry("PARTIAL_LINE", "linha incompleta (escrita interrompida)"), Map.entry("SCHEMA_MISMATCH", "fora do contrato de eventos"),
            Map.entry("SCHEMA_UNSUPPORTED", "versão de contrato não suportada"), Map.entry("FIELD_INVALID", "campo obrigatório inválido"),
            Map.entry("NESTING_TOO_DEEP", "estrutura aninhada demais"), Map.entry("STALE_EVENT", "evento anterior ao início da execução"),
            Map.entry("CORRELATION_INVALID", "identificação da execução do Framework inválida"),
            Map.entry("CORRELATION_CONFLICT", "evento de outra execução do Framework"));

    static String event(String code) {
        return EVENTS.getOrDefault(code, code);
    }

    static String action(String action) {
        return action == null ? null : ACTIONS.getOrDefault(action, action);
    }

    static String phase(String p) {
        if (p == null) return "—";
        return switch (p) {
            case "TRANSFER_DATA_IN" -> "DATA IN";
            case "TRANSFER_DATA_OUT" -> "DATA OUT";
            default -> p;
        };
    }

    static String source(ActivityEvent.Source s) {
        return switch (s) {
            case AGENT -> "Agent";
            case FRAMEWORK -> "Framework";
            case COLLECTOR -> "Coletor";
        };
    }

    static String level(ActivityEvent.Level l) {
        return switch (l) {
            case INFO -> "Info";
            case WARNING -> "Aviso";
            case ERROR -> "Erro";
        };
    }

    static String category(ActivityEvent.Category c) {
        return switch (c) {
            case LIFECYCLE -> "Ciclo de vida";
            case ACTIVITY -> "Atividade";
            case BUSINESS -> "Rejeição de negócio";
            case FAILURE -> "Falha de execução";
            case TELEMETRY -> "Telemetria (leitura de eventos)";
        };
    }

    /** Código de erro/motivo para a tela: catálogo do Agent com descrição; motivo do coletor traduzido; senão o código. */
    static String error(String code) {
        if (code == null) return null;
        for (ErrorCode c : ErrorCode.values()) if (c.code().equals(code)) return c.description() + " (" + code + ")";
        String r = REASONS.get(code);
        return r == null ? code : r + " (" + code + ")";
    }

    static String origin(String status) {
        if ("AGENT_SOURCE".equals(status)) return "eventos do supervisor";
        if ("FRAMEWORK_SOURCE".equals(status)) return "eventos do Framework";
        if ("ACTIVITY_STORE".equals(status)) return "histórico de atividade";
        return status;
    }

    static String duration(Long ms) {
        if (ms == null) return "";
        if (ms < 1000) return ms + " ms";
        return String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0).replace('.', ',');
    }
}
