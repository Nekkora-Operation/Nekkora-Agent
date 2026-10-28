package com.nekkora.agent.platform;

import com.nekkora.agent.exec.JsonReader;
import com.nekkora.agent.platform.DeliveryStore.Range;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * As operações da máquina do contrato Platform–Agent 1.0 ({@code /agent/api/v1}), tipadas. Toda chamada leva
 * {@code Nekkora-Agent-Contract: 1.0}; as autenticadas, {@code Authorization: Bearer nka1…} (nunca registrado). Nenhuma
 * decisão é tomada aqui: cada resposta volta classificada ({@link Result}) e quem chama decide o que fazer.
 */
final class MachineClient {
    static final String CONTRACT = "1.0";
    static final List<String> CAPABILITIES = List.of("RUN_LOCAL", "OBS_1_0", "SUPERVISOR_REPORT_1_0");

    /** Resultado de uma chamada. */
    sealed interface Result<T> permits Ok, Refused, Malformed, NoResponse {}

    /** Resposta esperada (2xx; ou 409 do relatório com o desfecho {@code CONFLICT}, que a Platform registrou). */
    record Ok<T>(int status, T value) implements Result<T> {}

    /** Recusa HTTP com o código do corpo ({@code {"error":"..."}}) e o {@code Retry-After}, se veio. */
    record Refused<T>(int status, String error, Optional<Long> retryAfterSeconds) implements Result<T> {}

    /** Status de sucesso com corpo fora do contrato: nada é aceito dele. */
    record Malformed<T>(int status) implements Result<T> {}

    /** Sem resposta (rede, TLS, tempo, limite): o resultado no servidor é desconhecido. */
    record NoResponse<T>() implements Result<T> {}

    record Presence(Instant serverTime, Instant credentialExpiresAt) {}

    record Registered(UUID runId, boolean created, String state, String outcome) {}

    record Reported(UUID runId, String outcome, String state) {}

    record ItemResult(long sequence, String status, String reason) {}

    record MissingResult(long from, long to, String status, String reason) {}

    record Ack(UUID runId, long storedThrough, long settledThrough, List<ItemResult> results, List<MissingResult> missing) {}

    record Authorizations(List<AuthorizationCache.Authorization> items, int rejected) {}

    private final HttpTransport http;
    private final String agentVersion;

    MachineClient(HttpTransport http, String agentVersion) {
        this.http = http;
        this.agentVersion = agentVersion;
    }

    Result<CredentialVault.Redeemed> redeem(Destination d, CredentialVault.Material m, Optional<String> currentToken) {
        String body = Json.obj().str("code", m.code()).str("credentialSecret", m.secret()).str("agentVersion", agentVersion)
                .raw("contractVersions", "[\"" + CONTRACT + "\"]").toString();
        return call(d, "POST", "/agent/api/v1/enrollments/redeem", currentToken.orElse(null), body, j -> {
            UUID org = Json.uuid(j, "organizationId");
            UUID agent = Json.uuid(j, "agentId");
            UUID cred = Json.uuid(j, "credentialId");
            Instant exp = Json.instant(j, "credentialExpiresAt");
            if (org == null || agent == null || cred == null || exp == null) return null;
            return new CredentialVault.Redeemed(org, agent, Json.uuid(j, "installationId"), Json.uuid(j, "workerId"), cred, exp);
        });
    }

    Result<Presence> heartbeat(Destination d, String token) {
        String body = Json.obj().str("agentVersion", agentVersion).str("contractVersion", CONTRACT)
                .raw("capabilities", "[\"" + String.join("\",\"", CAPABILITIES) + "\"]").toString();
        return call(d, "POST", "/agent/api/v1/heartbeat", token, body, j -> {
            Instant st = Json.instant(j, "serverTime");
            return st == null ? null : new Presence(st, Json.instant(j, "credentialExpiresAt"));
        });
    }

    Result<Authorizations> authorizations(Destination d, String token) {
        return call(d, "GET", "/agent/api/v1/authorizations", token, null, j -> {
            List<?> items = Json.list(j, "items");
            if (items == null || items.size() > AuthorizationCache.MAX_ITEMS) return null;
            List<AuthorizationCache.Authorization> ok = new ArrayList<>();
            int rejected = 0;
            for (Object o : items) {
                AuthorizationCache.Authorization z = AuthorizationCache.parseItem(Json.object(o));
                if (z == null) rejected++;
                else ok.add(z);
            }
            return new Authorizations(ok, rejected);
        });
    }

    Result<Registered> register(Destination d, String token, String executionId, UUID authorization, String fingerprint, String supervisorJson) {
        String body = Json.obj().str("agentExecutionId", executionId).str("authorizationId", authorization.toString()).str("packageFingerprint", fingerprint)
                .raw("supervisor", supervisorJson).toString();
        return call(d, "POST", "/agent/api/v1/runs", token, body, j -> {
            UUID run = Json.uuid(j, "runId");
            Boolean created = Json.bool(j, "created");
            String outcome = Json.text(j, "reportOutcome");
            if (run == null || created == null || !outcome(outcome)) return null;
            return new Registered(run, created, Json.text(j, "state"), outcome);
        });
    }

    Result<Reported> report(Destination d, String token, String executionId, String supervisorJson) {
        return call(d, "POST", "/agent/api/v1/runs/" + executionId + "/supervisor", token, supervisorJson, j -> {
            UUID run = Json.uuid(j, "runId");
            String outcome = Json.text(j, "outcome");
            if (run == null || !outcome(outcome)) return null;
            return new Reported(run, outcome, Json.text(j, "state"));
        });
    }

    Result<Ack> events(Destination d, String token, String executionId, List<String> envelopes, List<Range> missing) {
        Json.Obj o = Json.obj().raw("events", Json.list(envelopes));
        if (!missing.isEmpty()) {
            List<String> ranges = new ArrayList<>();
            for (Range r : missing) ranges.add(Json.obj().num("from", r.from()).num("to", r.to()).toString());
            o.raw("declaredMissing", Json.list(ranges));
        }
        return call(d, "POST", "/agent/api/v1/runs/" + executionId + "/events", token, o.toString(), MachineClient::ack);
    }

    /** ACK como veio (a coerência com o lote enviado é conferida por quem chama: {@link AckCheck}). */
    static Ack ack(Map<String, Object> j) {
        UUID run = Json.uuid(j, "runId");
        Long stored = Json.number(j, "storedThrough");
        Long settled = Json.number(j, "settledThrough");
        List<?> results = Json.list(j, "results");
        List<?> missing = Json.list(j, "missing");
        if (run == null || stored == null || settled == null || results == null || missing == null) return null;
        List<ItemResult> rs = new ArrayList<>();
        for (Object o : results) {
            Map<String, Object> m = Json.object(o);
            if (m == null) return null;
            Long seq = Json.number(m, "sequence");
            String st = Json.text(m, "status");
            if (seq == null || st == null) return null;
            rs.add(new ItemResult(seq, st, Json.text(m, "reason")));
        }
        List<MissingResult> ms = new ArrayList<>();
        for (Object o : missing) {
            Map<String, Object> m = Json.object(o);
            if (m == null) return null;
            Long from = Json.number(m, "from");
            Long to = Json.number(m, "to");
            String st = Json.text(m, "status");
            if (from == null || to == null || st == null) return null;
            ms.add(new MissingResult(from, to, st, Json.text(m, "reason")));
        }
        return new Ack(run, stored, settled, rs, ms);
    }

    private static boolean outcome(String o) {
        return "APPLIED".equals(o) || "UNCHANGED".equals(o) || "STALE".equals(o) || "CONFLICT".equals(o);
    }

    private interface Parser<T> {
        T parse(Map<String, Object> json);
    }

    private <T> Result<T> call(Destination d, String method, String path, String token, String body, Parser<T> parser) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Nekkora-Agent-Contract", CONTRACT);
        h.put("Accept", "application/json");
        h.put("User-Agent", "nekkora-agent/" + agentVersion);
        if (body != null) h.put("Content-Type", "application/json");
        if (token != null) h.put("Authorization", "Bearer " + token);
        HttpTransport.Response res;
        try {
            res = http.send(new HttpTransport.Request(method, d.resolve(path), h, body == null ? null : body.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return new NoResponse<>();
        }
        Map<String, Object> json = null;
        try {
            if (res.body().length > 0) json = Json.parse(new String(res.body(), StandardCharsets.UTF_8));
        } catch (JsonReader.Invalid e) {
            json = null;
        }
        int s = res.status();
        if (s >= 200 && s < 300 || s == 409 && json != null && json.containsKey("outcome")) {
            T v = json == null ? null : parser.parse(json);
            return v == null ? new Malformed<>(s) : new Ok<>(s, v);
        }
        String err = json == null ? null : Json.text(json, "error");
        return new Refused<>(s, err == null || err.length() > 40 ? null : err, res.retryAfterSeconds());
    }
}
