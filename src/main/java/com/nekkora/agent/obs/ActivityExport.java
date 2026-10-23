package com.nekkora.agent.obs;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Exportação MANUAL da atividade de uma execução: os mesmos envelopes já saneados, recodificados um a um a partir do
 * modelo validado (nunca uma cópia de arquivo bruto do Framework ou do supervisor). A tela mostra antes a lista
 * {@link #FIELDS} — o que pode sair no arquivo — e a pessoa escolhe o destino.
 */
public final class ActivityExport {
    private ActivityExport() {}

    /** Todos os campos que um envelope pode ter (nada fora disto é escrito). */
    public static final List<String> FIELDS = List.of("schema", "version", "agent_execution_id", "sequence", "timestamp_utc", "observed_utc", "source",
            "event_code", "severity", "category", "phase", "transaction_ref", "step_id", "studio_step_id", "action", "attempt", "elapsed_ms", "status",
            "error_code", "counts", "correlation.framework_execution_id", "correlation.source_position");

    /** Conteúdo JSONL (uma linha por evento, na ordem de sequência). */
    public static byte[] jsonl(List<ActivityEvent> events) {
        StringBuilder b = new StringBuilder(events.size() * 256);
        for (ActivityEvent e : events) b.append(ActivityCodec.encode(e)).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }
}
