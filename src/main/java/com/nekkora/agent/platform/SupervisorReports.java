package com.nekkora.agent.platform;

import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.ExecutionState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Relatório do supervisor (contrato, sec. 7): os campos do {@code estado.json} GRAVADO pelo supervisor, nunca deduzidos de
 * saída do processo, código de saída isolado ou evento do Framework. O transporte lê o registro em momentos próprios e
 * pode encontrá-lo vários passos adiante do último relatório aceito; como a Platform só aceita as transições do ciclo do
 * Agent, os passos intermediários que o registro GRAVADO comprova são relatados antes, na ordem:
 * {@code STARTING} (todo terminal que não é {@code BLOCKED}/{@code CANCELLED} a partir de {@code READY} passou por ele) e
 * {@code RUNNING} (o registro tem {@code started}, que o supervisor só preenche ao entrar em {@code RUNNING}). Os dados
 * desses passos são os do registro, sem desfecho (código, saída, contagens e fim vazios). Nada é inventado: sem
 * {@code started}, {@code RUNNING} não é relatado.
 * <p>
 * Os instantes vão truncados em microssegundos (o que a Platform guarda), para que a repetição compare igual.
 */
final class SupervisorReports {
    private SupervisorReports() {}

    /** Um relatório pronto para envio: estado, corpo JSON e resumo (para saber se mudou desde o último aceito). */
    record Report(ExecutionState state, String json, String digest) {
        boolean terminal() {
            return state.terminal();
        }
    }

    /** O relatório que o registro gravado descreve agora. */
    static Report of(ExecutionRecord r) {
        return build(r.state(), r);
    }

    /**
     * Relatórios a enviar, em ordem, para levar a Platform de {@code last} (último estado aceito; {@code null} = nada
     * aceito ainda: o registro leva o estado atual de uma vez) ao estado gravado. Vazio quando nada mudou.
     */
    static List<Report> plan(ExecutionState last, String lastDigest, ExecutionRecord r) {
        Report now = of(r);
        if (last == null) return List.of(now);
        if (last == now.state() && now.digest().equals(lastDigest)) return List.of();
        List<Report> out = new ArrayList<>();
        ExecutionState target = now.state();
        boolean early = last == ExecutionState.READY || last == ExecutionState.STARTING;
        if (early && r.started() != null && target != ExecutionState.RUNNING && ExecutionState.RUNNING.canMoveTo(target)) {
            // o registro comprova que a execução esteve em RUNNING: a Platform recebe o caminho real
            if (last == ExecutionState.READY) out.add(build(ExecutionState.STARTING, r));
            out.add(build(ExecutionState.RUNNING, r));
        } else if (last == ExecutionState.READY && !last.canMoveTo(target) && ExecutionState.STARTING.canMoveTo(target)) {
            out.add(build(ExecutionState.STARTING, r));
        }
        out.add(now);
        return out;
    }

    private static Report build(ExecutionState state, ExecutionRecord r) {
        boolean intermediate = state != r.state();
        boolean terminal = state.terminal();
        Json.Obj o = Json.obj().str("state", state.name());
        o.str("code", terminal && r.code() != null ? r.code().code() : null);
        o.num("exitCode", intermediate ? null : r.exitCode());
        Map<String, Long> counts = intermediate ? Map.of() : new TreeMap<>(r.counts());
        o.counts("counts", counts);
        o.str("created", micros(r.created()));
        o.str("started", state == ExecutionState.STARTING || state == ExecutionState.READY ? null : micros(r.started()));
        o.str("ended", terminal ? micros(r.ended()) : null);
        o.bool("adopted", r.adopted()).num("suspensions", r.suspensions()).num("locks", r.locks());
        String json = o.toString();
        return new Report(state, json, digest(json));
    }

    static String micros(Instant i) {
        return i == null ? null : i.truncatedTo(ChronoUnit.MICROS).toString();
    }

    static String digest(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
