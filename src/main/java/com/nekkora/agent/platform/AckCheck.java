package com.nekkora.agent.platform;

import com.nekkora.agent.platform.DeliveryStore.Delivery;
import com.nekkora.agent.platform.DeliveryStore.Range;
import com.nekkora.agent.platform.MachineClient.Ack;
import com.nekkora.agent.platform.MachineClient.ItemResult;
import com.nekkora.agent.platform.MachineClient.MissingResult;
import java.util.List;
import java.util.Set;

/**
 * Conferência do ACK (contrato, sec. 8.1 e 8.3) contra o lote que foi enviado, ANTES de qualquer avanço local. Um ACK
 * malformado, de outra execução, que recua, que vai além do que este Agent já enviou ou que contradiz os próprios
 * resultados é RECUSADO: nada é gravado nem liberado a partir dele (o próximo reenvio é seguro: {@code DUPLICATE}).
 * <p>
 * Aceito, ele diz até onde a entrega avançou ({@code settledThrough}, o cursor) e o que o servidor tem
 * ({@code storedThrough}, o recebimento — o que foi declarado ausente conta só no primeiro), e se a entrega parou num
 * evento ({@code INVALID}) ou numa declaração recusada.
 */
final class AckCheck {
    private AckCheck() {}

    static final Set<String> EVENT_STATUS = Set.of("STORED", "DUPLICATE", "CONFLICT", "INVALID");
    static final Set<String> MISSING_STATUS = Set.of("STORED", "DUPLICATE", "CONFLICT");

    /**
     * {@code valid} falso: recusado ({@code why} diz por quê). Aceito: os cursores novos, a parada (sequência e motivo) se
     * houver, e quantos eventos tinham outro conteúdo no servidor (o original prevaleceu).
     */
    record Verdict(boolean valid, String why, long stored, long settled, Long stoppedAt, String stopReason, long contentConflicts) {
        static Verdict refused(String why) {
            return new Verdict(false, why, 0, 0, null, null, 0);
        }
    }

    static Verdict check(Delivery d, List<EventReader.Line> sent, List<Range> declared, Ack a) {
        if (a == null) return Verdict.refused("malformado");
        if (d.runId() == null || !d.runId().equals(a.runId())) return Verdict.refused("outra_execucao");
        if (a.storedThrough() < 0 || a.settledThrough() < a.storedThrough()) return Verdict.refused("cursores_incoerentes");
        if (a.storedThrough() < d.storedThrough() || a.settledThrough() < d.settledThrough()) return Verdict.refused("cursor_recuou");
        long upper = Math.max(d.maxSent(), d.settledThrough());
        for (EventReader.Line l : sent) upper = Math.max(upper, l.sequence());
        for (Range r : declared) upper = Math.max(upper, r.to());
        for (Range r : d.declared()) upper = Math.max(upper, r.to());
        if (a.settledThrough() > upper) return Verdict.refused("alem_do_enviado");
        List<ItemResult> rs = a.results();
        if (rs.size() != sent.size()) return Verdict.refused("resultados_divergentes");
        for (int i = 0; i < rs.size(); i++) {
            if (rs.get(i).sequence() != sent.get(i).sequence() || !EVENT_STATUS.contains(rs.get(i).status())) return Verdict.refused("resultados_divergentes");
        }
        List<MissingResult> ms = a.missing();
        if (ms.size() != declared.size()) return Verdict.refused("ausencias_divergentes");
        for (int i = 0; i < ms.size(); i++) {
            MissingResult m = ms.get(i);
            if (m.from() != declared.get(i).from() || m.to() != declared.get(i).to() || !MISSING_STATUS.contains(m.status())) {
                return Verdict.refused("ausencias_divergentes");
            }
        }
        // caminho contínuo a partir do cursor anterior: até onde o próprio lote leva a entrega
        long reach = d.settledThrough();
        Long stoppedAt = null;
        String stop = null;
        long conflicts = 0;
        int ei = 0;
        int mi = 0;
        while (stoppedAt == null && (ei < sent.size() || mi < declared.size())) {
            boolean missingNext = mi < declared.size() && (ei >= sent.size() || declared.get(mi).from() < sent.get(ei).sequence());
            if (missingNext) {
                Range r = declared.get(mi);
                MissingResult m = ms.get(mi++);
                boolean settles = !"CONFLICT".equals(m.status()) || "evento_gravado".equals(m.reason());
                if (r.from() > reach + 1) break;
                if (!settles) {
                    stoppedAt = r.from();
                    stop = "ausencia_" + (m.reason() == null ? "recusada" : m.reason());
                    break;
                }
                reach = Math.max(reach, r.to());
            } else {
                EventReader.Line l = sent.get(ei);
                ItemResult it = rs.get(ei++);
                if (l.sequence() > reach + 1) break;
                if ("INVALID".equals(it.status())) {
                    stoppedAt = l.sequence();
                    stop = "invalido_" + (it.reason() == null ? "sem_motivo" : it.reason());
                    break;
                }
                if ("CONFLICT".equals(it.status())) {
                    if (!"conteudo_diferente".equals(it.reason())) {
                        stoppedAt = l.sequence();
                        stop = "conflito_" + (it.reason() == null ? "sem_motivo" : it.reason());
                        break;
                    }
                    conflicts++; // o original do servidor prevalece e conta nos dois cursores (sec. 8.3)
                }
                reach = Math.max(reach, l.sequence());
            }
        }
        // o servidor pode estar adiante do lote (reenvio depois de ACK perdido), nunca atrás do que o lote comprova
        if (a.settledThrough() < reach) return Verdict.refused("cursor_aquem_do_lote");
        if (stoppedAt != null && a.settledThrough() >= stoppedAt) {
            // o servidor já tinha aquele número (gravado antes): a recusa deste reenvio não para a entrega
            stoppedAt = null;
            stop = null;
        }
        return new Verdict(true, null, a.storedThrough(), a.settledThrough(), stoppedAt, stop, conflicts);
    }
}
