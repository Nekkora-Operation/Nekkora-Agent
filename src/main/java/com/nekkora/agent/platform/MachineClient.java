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
    static final List<String> CAPABILITIES = List.of("RUN_LOCAL", "OBS_1_0", "SUPERVISOR_REPORT_1_0", "EXTERNAL_PYTHON_1_0", "RUN_REQUEST_1_0");

    /** Resultado de uma chamada. */
    sealed interface Result<T> permits Ok, Refused, Malformed, NoResponse {}

    /** Resposta esperada (2xx; ou 409 do relatório com o desfecho {@code CONFLICT}, que a Platform registrou). */
    record Ok<T>(int status, T value) implements Result<T> {}

    /**
     * Recusa HTTP com o código do corpo ({@code {"error":"...","reason":"..."}}; {@code reason} é opcional) e o
     * {@code Retry-After}, se veio.
     */
    record Refused<T>(int status, String error, String reason, Optional<Long> retryAfterSeconds) implements Result<T> {}

    /** Status de sucesso com corpo fora do contrato: nada é aceito dele. */
    record Malformed<T>(int status) implements Result<T> {}

    /**
     * Sem resposta: o resultado no servidor é desconhecido. {@code kind} (PLATFORM-AGENT-CATALOGO-001): TLS (certificado ou
     * protocolo recusado), TIMEOUT (tempo esgotado) ou NETWORK (conexão recusada, nome, rede) — para não chamar tudo de "offline".
     */
    record NoResponse<T>(NoResponseKind kind) implements Result<T> {
        NoResponse() {
            this(NoResponseKind.NETWORK);
        }
    }

    enum NoResponseKind { NETWORK, TLS, TIMEOUT }

    static NoResponseKind kindOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof javax.net.ssl.SSLException || t instanceof java.security.cert.CertificateException) return NoResponseKind.TLS;
            if (t instanceof java.net.http.HttpTimeoutException || t instanceof java.io.InterruptedIOException) return NoResponseKind.TIMEOUT; // inclui o tempo esgotado do soquete
            if (t.getCause() == t) break;
        }
        return NoResponseKind.NETWORK;
    }

    /** PLATFORM-AGENT-CATALOGO-001: uma página do catálogo da máquina (só metadados; validados aqui, conferidos com o vínculo por quem chama). */
    record CatalogPage(Instant observedAt, RemoteCatalog.Organization organization, UUID agentId, UUID workerId, List<RemoteCatalog.Project> items,
                       String nextCursor) {}

    /** Consulta direta de uma automação do catálogo. */
    record CatalogOne(Instant observedAt, RemoteCatalog.Organization organization, UUID agentId, UUID workerId, RemoteCatalog.Project item) {}

    static final int CATALOG_PAGE = 100;
    private static final java.util.regex.Pattern CURSOR = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,400}");

    record Presence(Instant serverTime, Instant credentialExpiresAt) {}

    record Registered(UUID runId, boolean created, String state, String outcome) {}

    record Reported(UUID runId, String outcome, String state) {}

    record ItemResult(long sequence, String status, String reason) {}

    record MissingResult(long from, long to, String status, String reason) {}

    record Ack(UUID runId, long storedThrough, long settledThrough, List<ItemResult> results, List<MissingResult> missing) {}

    record Authorizations(List<AuthorizationCache.Authorization> items, int rejected) {}

    /** AGENT-PYTHON-001: pedido de execução entregue a esta máquina (só identificadores, validade e o pacote esperado). */
    record PendingRequest(UUID runRequestId, UUID authorizationId, UUID automationVersionId, String sourceKind, String fingerprint, String rule,
                          Instant validUntil, String state, String executionId) {}

    record PendingRequests(List<PendingRequest> items, int rejected) {}

    /** Resultado de uma decisão: {@code outcome} APPLIED, UNCHANGED, EXPIRED, REVOKED ou CONFLICT; {@code state} o vigente. */
    record Decision(UUID runRequestId, String outcome, String state, String executionId) {}

    private final HttpTransport http;
    private final String agentVersion;

    MachineClient(HttpTransport http, String agentVersion) {
        this.http = http;
        this.agentVersion = agentVersion;
    }

    /** PLATFORM-AGENT-V2-002 T06: intenção declarada no resgate ({@code Nekkora-Agent-Intent}). */
    enum Intent {
        PAIR("pair"), ROTATE("rotate");

        final String header;

        Intent(String header) {
            this.header = header;
        }
    }

    static final String INTENT_HEADER = "Nekkora-Agent-Intent";

    Result<CredentialVault.Redeemed> redeem(Destination d, CredentialVault.Material m, Optional<String> currentToken, Intent intent) {
        String body = Json.obj().str("code", m.code()).str("credentialSecret", m.secret()).str("agentVersion", agentVersion)
                .raw("contractVersions", "[\"" + CONTRACT + "\"]").toString();
        return call(d, "POST", "/agent/api/v1/enrollments/redeem", currentToken.orElse(null), Map.of(INTENT_HEADER, intent.header), body, j -> {
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

    /** PLATFORM-AGENT-V2-004 T13: a Platform encerrou o vínculo por ESTA operação (inclusive numa repetição). */
    record Unlinked(UUID operationId, UUID agentId, UUID workerId, Instant unlinkedAt) {}

    /**
     * Desvínculo pela máquina ({@code POST /agent/api/v1/link/unlink}). Só um {@code 200} com o mesmo {@code operationId} e o
     * mesmo Agent conclui; um {@code 409} com motivo conhecido diz que o vínculo já terminou de outro modo; um {@code 401}
     * isolado NÃO prova nada; um {@code 404} é uma Platform sem a rota (versão anterior).
     */
    Result<Unlinked> unlink(Destination d, String token, UUID operationId, UUID agentId, List<UUID> launchedRunRequests) {
        List<String> launched = new ArrayList<>();
        for (UUID u : launchedRunRequests) launched.add("\"" + u + "\"");
        String body = Json.obj().str("operationId", operationId.toString()).str("agentId", agentId.toString())
                .raw("launchedRunRequests", "[" + String.join(",", launched) + "]").toString();
        return call(d, "POST", "/agent/api/v1/link/unlink", token, body, j -> {
            if (!"UNLINKED".equals(Json.text(j, "outcome"))) return null;
            UUID op = Json.uuid(j, "operationId");
            UUID agent = Json.uuid(j, "agentId");
            Instant at = Json.instant(j, "unlinkedAt");
            if (!operationId.equals(op) || !agentId.equals(agent) || at == null) return null;
            return new Unlinked(op, agent, Json.uuid(j, "workerId"), at);
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

    /** Uma página do catálogo ({@code cursor} nulo = a primeira). Cursor fora do formato: nenhuma chamada. */
    Result<CatalogPage> catalog(Destination d, String token, String cursor) {
        if (cursor != null && !CURSOR.matcher(cursor).matches()) return new Malformed<>(0);
        String path = "/agent/api/v1/catalog?limit=" + CATALOG_PAGE + (cursor == null ? "" : "&cursor=" + cursor);
        return call(d, "GET", path, token, null, j -> {
            Head h = head(j);
            List<?> items = Json.list(j, "items");
            // nextCursor: ausente ou null JSON (marcador do leitor) = fim; texto fora do formato = resposta fora do contrato
            String next = Json.text(j, "nextCursor");
            boolean nextIsNull = !j.containsKey("nextCursor") || j.get("nextCursor") == com.nekkora.agent.exec.JsonReader.NULL;
            if (h == null || items == null || items.size() > CATALOG_PAGE || !nextIsNull && (next == null || !CURSOR.matcher(next).matches())) return null;
            List<RemoteCatalog.Project> ok = new ArrayList<>();
            for (Object o : items) {
                RemoteCatalog.Project p = project(Json.object(o));
                if (p == null) return null; // um item fora do contrato invalida a página: nada é aceito pela metade
                ok.add(p);
            }
            return new CatalogPage(h.observedAt(), h.organization(), h.agentId(), h.workerId(), ok, nextIsNull ? null : next);
        });
    }

    Result<CatalogOne> catalogAutomation(Destination d, String token, UUID automationId) {
        return call(d, "GET", "/agent/api/v1/catalog/automations/" + automationId, token, null, j -> {
            Head h = head(j);
            RemoteCatalog.Project p = project(Json.object(j.get("item")));
            if (h == null || p == null || !p.automationId().equals(automationId)) return null;
            return new CatalogOne(h.observedAt(), h.organization(), h.agentId(), h.workerId(), p);
        });
    }

    private record Head(Instant observedAt, RemoteCatalog.Organization organization, UUID agentId, UUID workerId) {}

    private static Head head(Map<String, Object> j) {
        if (!"1.0".equals(Json.text(j, "catalogVersion")) || !"WORKER_AUTHORIZED_SUBSET".equals(Json.text(j, "coverage"))) return null;
        Instant at = Json.instant(j, "observedAt");
        Map<String, Object> o = Json.object(j.get("organization"));
        Map<String, Object> m = Json.object(j.get("machine"));
        if (at == null || o == null || m == null) return null;
        UUID org = Json.uuid(o, "organizationId");
        String name = Json.text(o, "displayName");
        UUID agent = Json.uuid(m, "agentId");
        UUID worker = Json.uuid(m, "workerId");
        if (org == null || name == null || name.isBlank() || name.length() > 200 || agent == null || worker == null) return null;
        return new Head(at, new RemoteCatalog.Organization(org, name.strip()), agent, worker);
    }

    private static RemoteCatalog.Project project(Map<String, Object> m) {
        if (m == null) return null;
        UUID ws = Json.uuid(m, "workspaceId");
        UUID auto = Json.uuid(m, "automationId");
        String wsName = Json.text(m, "workspaceName");
        String name = Json.text(m, "automationName");
        List<?> vs = Json.list(m, "versions");
        Boolean truncated = Json.bool(m, "versionsTruncated");
        if (ws == null || auto == null || wsName == null || name == null || wsName.length() > 200 || name.length() > 200 || vs == null || vs.size() > 20
                || truncated == null) return null;
        List<RemoteCatalog.Version> out = new ArrayList<>();
        for (Object o : vs) {
            Map<String, Object> v = Json.object(o);
            if (v == null) return null;
            UUID id = Json.uuid(v, "automationVersionId");
            Long n = Json.number(v, "versionNumber");
            Instant published = Json.instant(v, "publishedAt");
            String kind = Json.text(v, "sourceKind");
            String rule = Json.text(v, "packageFingerprintRule");
            String fp = Json.text(v, "packageFingerprint");
            String pkgName = Json.text(v, "packageName");
            String pkgVersion = Json.text(v, "packageVersion");
            if (id == null || n == null || n < 1 || n > Integer.MAX_VALUE || published == null || !("STUDIO_PACKAGE".equals(kind) || "EXTERNAL_PACKAGE".equals(kind))
                    || !PackageFingerprint.known(rule) || fp == null || !fp.matches("[0-9a-f]{64}") || pkgName != null && pkgName.length() > 80
                    || pkgVersion != null && pkgVersion.length() > 40) return null;
            out.add(new RemoteCatalog.Version(id, n.intValue(), published, kind, rule, "sha256:" + fp, pkgName, pkgVersion));
        }
        return new RemoteCatalog.Project(ws, wsName.strip(), auto, name.strip(), out, truncated);
    }

    Result<PendingRequests> runRequests(Destination d, String token) {
        return call(d, "GET", "/agent/api/v1/run-requests", token, null, j -> {
            List<?> items = Json.list(j, "items");
            if (items == null || items.size() > 50) return null;
            List<PendingRequest> ok = new ArrayList<>();
            int rejected = 0;
            for (Object o : items) {
                Map<String, Object> m = Json.object(o);
                UUID id = m == null ? null : Json.uuid(m, "runRequestId");
                UUID auth = m == null ? null : Json.uuid(m, "authorizationId");
                String fp = m == null ? null : Json.text(m, "packageFingerprint");
                String rule = m == null ? null : Json.text(m, "packageFingerprintRule");
                Instant until = m == null ? null : Json.instant(m, "validUntil");
                String st = m == null ? null : Json.text(m, "state");
                String ex = m == null ? null : Json.text(m, "agentExecutionId");
                if (id == null || auth == null || fp == null || !fp.matches("[0-9a-f]{64}") || !PackageFingerprint.known(rule) || until == null
                        || !("DELIVERED".equals(st) || "ACCEPTED".equals(st)) || ex != null && !ex.matches("ex-[0-9a-f]{32}")) {
                    rejected++;
                    continue;
                }
                ok.add(new PendingRequest(id, auth, Json.uuid(m, "automationVersionId"), Json.text(m, "sourceKind"), fp, rule, until, st, ex));
            }
            return new PendingRequests(ok, rejected);
        });
    }

    Result<Decision> decide(Destination d, String token, UUID runRequest, String decision, String reason, String executionId) {
        Json.Obj o = Json.obj().str("decision", decision);
        if (reason != null) o.str("reason", reason);
        if (executionId != null) o.str("agentExecutionId", executionId);
        return call(d, "POST", "/agent/api/v1/run-requests/" + runRequest + "/decision", token, o.toString(), j -> {
            String outcome = Json.text(j, "outcome");
            if (outcome == null || !java.util.Set.of("APPLIED", "UNCHANGED", "EXPIRED", "REVOKED", "CONFLICT").contains(outcome)) return null;
            return new Decision(Json.uuid(j, "runRequestId"), outcome, Json.text(j, "state"), Json.text(j, "agentExecutionId"));
        });
    }

    Result<Registered> register(Destination d, String token, String executionId, UUID authorization, String fingerprint, String supervisorJson) {
        return register(d, token, executionId, authorization, fingerprint, null, supervisorJson);
    }

    /**
     * AGENT-PYTHON-001: {@code fingerprint} nulo (monitoramento de Python externo: sem impressão) vai AUSENTE do corpo;
     * {@code runRequest}: execução de um pedido da Platform aceito por esta máquina.
     */
    Result<Registered> register(Destination d, String token, String executionId, UUID authorization, String fingerprint, UUID runRequest,
                                String supervisorJson) {
        Json.Obj o = Json.obj().str("agentExecutionId", executionId).str("authorizationId", authorization.toString());
        if (fingerprint != null) o.str("packageFingerprint", fingerprint);
        if (runRequest != null) o.str("runRequestId", runRequest.toString());
        String body = o.raw("supervisor", supervisorJson).toString();
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
        return call(d, method, path, token, Map.of(), body, parser);
    }

    private <T> Result<T> call(Destination d, String method, String path, String token, Map<String, String> extra, String body, Parser<T> parser) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Nekkora-Agent-Contract", CONTRACT);
        h.put("Accept", "application/json");
        h.put("User-Agent", "nekkora-agent/" + agentVersion);
        if (body != null) h.put("Content-Type", "application/json");
        if (token != null) h.put("Authorization", "Bearer " + token);
        h.putAll(extra);
        HttpTransport.Response res;
        try {
            res = http.send(new HttpTransport.Request(method, d.resolve(path), h, body == null ? null : body.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return new NoResponse<>(kindOf(e));
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
        String reason = json == null ? null : Json.text(json, "reason");
        return new Refused<>(s, err == null || err.length() > 40 ? null : err, reason == null || reason.length() > 60 ? null : reason, res.retryAfterSeconds());
    }
}
