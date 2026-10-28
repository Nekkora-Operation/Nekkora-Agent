package com.nekkora.agent.platform;

import com.nekkora.agent.exec.JsonReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TRANSPORTE SIMULADO do canal da máquina (só em teste): reproduz, no processo, a semântica publicada pela API no commit
 * de referência ({@code nekkora-agent-gateway}, contrato sec. 4–8): resgate idempotente só enquanto a credencial vale,
 * rotação que revoga a anterior na mesma transação, 401 uniforme, registro idempotente por (Agent, execução), decisão do
 * relatório do supervisor (APPLIED/UNCHANGED/STALE/CONFLICT), eventos processados antes das ausências, DUPLICATE/CONFLICT,
 * ausências irrevogáveis e os dois cursores. Falhas são INJETADAS e identificadas como tais: queda antes do processamento
 * (nada gravado), resposta perdida depois do commit (ACK perdido), 503 e 429 com {@code Retry-After}, corpo malformado.
 * <p>
 * Não substitui a prova com a API real (laboratório): serve para os controles do Agent serem testados de forma
 * determinística e sem pausas arbitrárias.
 */
final class FakeGateway implements HttpTransport {
    static final String ORIGIN = "https://platform.test";
    static final String RULE = "nekkora-agent/package-fingerprint/1";
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern TOKEN = Pattern.compile("(nka1|nkp1)\\.(" + UUID_RE + ")\\.(" + UUID_RE + ")\\.([A-Za-z0-9_-]{43})");
    private static final Set<String> FIELDS = Set.of("schema", "version", "agent_execution_id", "sequence", "timestamp_utc", "observed_utc", "source",
            "event_code", "severity", "category", "phase", "transaction_ref", "step_id", "studio_step_id", "action", "attempt", "elapsed_ms", "status",
            "error_code", "counts", "correlation");

    /** Falha injetada (sempre identificada como tal nas evidências). */
    enum Fault { DROP_BEFORE, DROP_AFTER_COMMIT, UNAVAILABLE, RATE_LIMITED, MALFORMED, REDIRECT }

    static final class Enrollment {
        UUID org, id, worker, agentForRotation;
        String codeHash;
        int purpose;
        boolean expired, cancelled;
        UUID consumedCredential;
    }

    static final class Agent {
        UUID org, id, installation, worker;
        boolean active = true, assigned = true;
    }

    static final class Credential {
        UUID org, id, agent;
        String hash;
        boolean revoked;
        Instant expires;
    }

    static final class Authz {
        UUID org, id, worker, workspace, automation, version;
        String fingerprint, rule;
        Instant validUntil;
        boolean revoked;
    }

    static final class Run {
        UUID id, agent, authorization;
        String execution, fingerprint, state, details;
        final TreeMap<Long, String> events = new TreeMap<>();
        final List<long[]> declared = new ArrayList<>();
        final List<String> outcomes = new ArrayList<>();
        long stored, settled;
        int conflicts;
    }

    final Clock clock;
    final Map<UUID, Enrollment> enrollments = new LinkedHashMap<>();
    final Map<UUID, Agent> agents = new LinkedHashMap<>();
    final Map<UUID, Credential> credentials = new LinkedHashMap<>();
    final Map<UUID, Authz> authorizations = new LinkedHashMap<>();
    final Map<String, Run> runs = new LinkedHashMap<>();
    final Map<String, Deque<Fault>> faults = new LinkedHashMap<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    int unauthenticated;
    /** Injeção: sequências que o servidor trata como INVALID (divergência de versão do envelope). */
    final Set<Long> invalidSequences = new java.util.HashSet<>();
    /** Injeção: adulteração do PRÓXIMO ACK (uma vez), para provar que um ACK incoerente não avança nada. */
    java.util.function.UnaryOperator<String> ackTamper;

    FakeGateway(Clock clock) {
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------- lado da pessoa (Platform)

    UUID worker() {
        return UUID.randomUUID();
    }

    String pairingCode(UUID org, UUID worker) {
        return code(org, worker, 1, null);
    }

    String rotationCode(UUID agent) {
        Agent a = agents.get(agent);
        return code(a.org, a.worker, 2, agent);
    }

    private String code(UUID org, UUID worker, int purpose, UUID agent) {
        Enrollment e = new Enrollment();
        e.org = org;
        e.id = UUID.randomUUID();
        e.worker = worker;
        e.purpose = purpose;
        e.agentForRotation = agent;
        String code = "nkp1." + org + "." + e.id + "." + secret();
        e.codeHash = sha(code);
        enrollments.put(e.id, e);
        return code;
    }

    UUID authorize(UUID org, UUID worker, String fingerprint, Instant until) {
        return authorize(org, worker, fingerprint, RULE, until);
    }

    UUID authorize(UUID org, UUID worker, String fingerprint, String rule, Instant until) {
        Authz z = new Authz();
        z.org = org;
        z.id = UUID.randomUUID();
        z.worker = worker;
        z.workspace = UUID.randomUUID();
        z.automation = UUID.randomUUID();
        z.version = UUID.randomUUID();
        z.fingerprint = fingerprint;
        z.rule = rule;
        z.validUntil = until;
        authorizations.put(z.id, z);
        return z.id;
    }

    void revokeAgent(UUID agent) {
        Agent a = agents.get(agent);
        a.active = false;
        a.assigned = false;
        for (Credential c : credentials.values()) if (c.agent.equals(agent)) c.revoked = true;
    }

    void revokeAuthorization(UUID id) {
        authorizations.get(id).revoked = true;
    }

    void fault(String pathPart, Fault... f) {
        faults.computeIfAbsent(pathPart, k -> new ArrayDeque<>()).addAll(List.of(f));
    }

    Run run(String execution) {
        return runs.values().stream().filter(r -> r.execution.equals(execution)).findFirst().orElse(null);
    }

    long count(String method, String pathPart) {
        return requests.stream().filter(r -> r.method().equals(method) && r.uri().getPath().contains(pathPart)).count();
    }

    // ------------------------------------------------------------------------------------------- transporte

    @Override
    public synchronized Response send(Request r) throws IOException {
        requests.add(r);
        String body = r.body() == null ? "" : new String(r.body(), StandardCharsets.UTF_8);
        bodies.add(body);
        URI u = r.uri();
        if (!(u.getScheme() + "://" + u.getAuthority()).equals(ORIGIN)) throw new IOException("destino desconhecido (simulado)");
        String path = u.getPath();
        Fault f = nextFault(path);
        if (f == Fault.DROP_BEFORE) throw new IOException("queda antes do processamento (falha injetada)");
        if (f == Fault.UNAVAILABLE) return err(503, "service_unavailable", 7L);
        if (f == Fault.RATE_LIMITED) return err(429, "rate_limited", 30L);
        if (f == Fault.MALFORMED) return new Response(200, Optional.empty(), "{\"oops\":true}".getBytes(StandardCharsets.UTF_8));
        if (f == Fault.REDIRECT) return new Response(302, Optional.empty(), new byte[0]);
        String contract = r.headers().get("Nekkora-Agent-Contract");
        if (contract == null || !contract.matches("1\\.[0-9]{1,3}")) return err(400, "unsupported_contract", null);
        Response res;
        try {
            Map<String, Object> json = body.isEmpty() ? Map.of() : JsonReader.object(body);
            res = route(r, path, json);
        } catch (JsonReader.Invalid e) {
            res = err(400, "invalid_request", null);
        }
        if (f == Fault.DROP_AFTER_COMMIT) throw new IOException("resposta perdida depois do commit (falha injetada)");
        return res;
    }

    private Fault nextFault(String path) {
        for (Map.Entry<String, Deque<Fault>> e : faults.entrySet()) {
            if (path.contains(e.getKey()) && !e.getValue().isEmpty()) return e.getValue().poll();
        }
        return null;
    }

    private Response route(Request r, String path, Map<String, Object> j) {
        String bearer = Optional.ofNullable(r.headers().get("Authorization")).filter(h -> h.startsWith("Bearer ")).map(h -> h.substring(7)).orElse(null);
        if (path.equals("/agent/api/v1/enrollments/redeem")) return redeem(j, bearer);
        Agent m = authenticate(bearer, false);
        if (m == null) {
            unauthenticated++;
            return err(401, "unauthenticated", null);
        }
        if (path.equals("/agent/api/v1/heartbeat")) {
            return ok(Json.obj().str("serverTime", clock.instant().toString()).str("credentialExpiresAt", credentialOf(bearer).expires.toString())
                    .str("contractVersion", "1.0").toString());
        }
        if (path.equals("/agent/api/v1/authorizations") && r.method().equals("GET")) {
            List<String> items = new ArrayList<>();
            for (Authz z : authorizations.values()) {
                if (!z.org.equals(m.org) || !z.worker.equals(m.worker) || z.revoked || !clock.instant().isBefore(z.validUntil)) continue;
                items.add(Json.obj().str("authorizationId", z.id.toString()).str("workspaceId", z.workspace.toString()).str("automationId", z.automation.toString())
                        .str("automationVersionId", z.version.toString()).str("packageFingerprint", z.fingerprint).str("packageFingerprintRule", z.rule)
                        .str("validUntil", z.validUntil.toString()).toString());
            }
            return ok("{\"items\":" + Json.list(items) + "}");
        }
        if (path.equals("/agent/api/v1/runs")) return register(m, j);
        Matcher sup = Pattern.compile("/agent/api/v1/runs/(ex-[0-9a-f]{32})/supervisor").matcher(path);
        if (sup.matches()) return supervisor(m, sup.group(1), j);
        Matcher ev = Pattern.compile("/agent/api/v1/runs/(ex-[0-9a-f]{32})/events").matcher(path);
        if (ev.matches()) return events(m, ev.group(1), j);
        return err(404, "not_found", null);
    }

    // ----- resgate (sec. 4.2 e 4.3)

    private Response redeem(Map<String, Object> j, String bearer) {
        String code = Json.text(j, "code");
        String secret = Json.text(j, "credentialSecret");
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}") || Json.text(j, "agentVersion") == null) return err(400, "invalid_request", null);
        Matcher m = code == null ? null : TOKEN.matcher(code);
        if (m == null || !m.matches() || !m.group(1).equals("nkp1")) return refused();
        Enrollment e = enrollments.get(UUID.fromString(m.group(3)));
        if (e == null || !e.codeHash.equals(sha(code)) || !e.org.toString().equals(m.group(2))) return refused();
        if (e.consumedCredential != null) {
            // repetição: só o MESMO segredo, e só enquanto a credencial emitida ainda vale
            Agent a = authenticate("nka1." + e.org + "." + e.consumedCredential + "." + secret, false);
            return a == null ? refused() : identity(credentials.get(e.consumedCredential));
        }
        if (e.expired || e.cancelled) return refused();
        if (e.purpose == 1) {
            boolean occupied = agents.values().stream().anyMatch(a -> a.org.equals(e.org) && a.worker.equals(e.worker) && a.assigned);
            if (occupied) return err(409, "conflict", null);
            Agent a = new Agent();
            a.org = e.org;
            a.id = UUID.randomUUID();
            a.installation = UUID.randomUUID();
            a.worker = e.worker;
            agents.put(a.id, a);
            Credential c = issue(a, secret);
            e.consumedCredential = c.id;
            return identity(c);
        }
        Agent current = authenticate(bearer, true);
        if (current == null || !current.id.equals(e.agentForRotation)) return refused();
        credentialOf(bearer).revoked = true; // a anterior é revogada na MESMA transação
        Credential c = issue(current, secret);
        e.consumedCredential = c.id;
        return identity(c);
    }

    private Credential issue(Agent a, String secret) {
        Credential c = new Credential();
        c.org = a.org;
        c.id = UUID.randomUUID();
        c.agent = a.id;
        c.hash = sha(secret);
        c.expires = clock.instant().plus(Duration.ofDays(90));
        credentials.put(c.id, c);
        return c;
    }

    private Response identity(Credential c) {
        Agent a = agents.get(c.agent);
        return ok(Json.obj().str("organizationId", c.org.toString()).str("agentId", a.id.toString()).str("installationId", a.installation.toString())
                .str("workerId", a.assigned ? a.worker.toString() : null).str("credentialId", c.id.toString())
                .str("credentialExpiresAt", c.expires.toString()).str("contractVersion", "1.0").toString());
    }

    private Response refused() {
        unauthenticated++;
        return err(401, "unauthenticated", null);
    }

    /** sec. 4.4 (e a rotação aceita a credencial vencida, nunca a revogada). */
    private Agent authenticate(String token, boolean rotation) {
        Credential c = credentialOf(token);
        if (c == null || c.revoked || !rotation && !clock.instant().isBefore(c.expires)) return null;
        Matcher m = TOKEN.matcher(token);
        if (!m.matches() || !sha(m.group(4)).equals(c.hash) || !c.org.toString().equals(m.group(2))) return null;
        Agent a = agents.get(c.agent);
        return a != null && a.active && a.assigned ? a : null;
    }

    private Credential credentialOf(String token) {
        if (token == null) return null;
        Matcher m = TOKEN.matcher(token);
        if (!m.matches() || !m.group(1).equals("nka1")) return null;
        Credential c = credentials.get(UUID.fromString(m.group(3)));
        return c != null && c.org.toString().equals(m.group(2)) ? c : null; // RLS: só a credencial da organização do token
    }

    // ----- registro e relatório (sec. 6.3 e 7)

    private Response register(Agent m, Map<String, Object> j) {
        String ex = Json.text(j, "agentExecutionId");
        UUID auth = Json.uuid(j, "authorizationId");
        String fp = Json.text(j, "packageFingerprint");
        Map<String, Object> sup = Json.object(j.get("supervisor"));
        if (ex == null || !ex.matches("ex-[0-9a-f]{32}") || auth == null || fp == null || !fp.matches("[0-9a-f]{64}") || sup == null || !validReport(sup)) {
            return err(400, "invalid_request", null);
        }
        Run existing = runs.get(m.id + "/" + ex);
        if (existing != null) {
            if (!existing.authorization.equals(auth) || !existing.fingerprint.equals(fp)) return err(409, "conflict", null);
            String o = decide(existing, sup);
            return status(200, Json.obj().str("runId", existing.id.toString()).bool("created", false).str("state", existing.state).str("reportOutcome", o).toString());
        }
        Authz z = authorizations.get(auth);
        if (z == null || !z.org.equals(m.org) || !z.worker.equals(m.worker)) return err(403, "forbidden", null);
        if (!z.fingerprint.equals(fp)) return err(403, "forbidden", null);
        Run run = new Run();
        run.id = UUID.randomUUID();
        run.agent = m.id;
        run.execution = ex;
        run.authorization = auth;
        run.fingerprint = fp;
        run.state = (String) sup.get("state");
        run.details = details(sup);
        run.outcomes.add("APPLIED");
        runs.put(m.id + "/" + ex, run);
        return status(201, Json.obj().str("runId", run.id.toString()).bool("created", true).str("state", run.state).str("reportOutcome", "APPLIED").toString());
    }

    private Response supervisor(Agent m, String ex, Map<String, Object> j) {
        Run run = runs.get(m.id + "/" + ex);
        if (run == null) return err(404, "not_found", null);
        if (!validReport(j)) return err(400, "invalid_request", null);
        String o = decide(run, j);
        return status("CONFLICT".equals(o) ? 409 : 200, Json.obj().str("runId", run.id.toString()).str("outcome", o).str("state", run.state).toString());
    }

    private static final List<String> STATES = List.of("READY", "STARTING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "BLOCKED",
            "OUTCOME_UNKNOWN");

    private static boolean validReport(Map<String, Object> s) {
        String st = Json.text(s, "state");
        if (st == null || !STATES.contains(st)) return false;
        String code = Json.text(s, "code");
        return code == null || terminal(st) && code.matches("AGT-[A-Z]{2,5}-[0-9]{3}");
    }

    static boolean terminal(String s) {
        return STATES.indexOf(s) >= 3;
    }

    private static int rank(String s) {
        return Math.min(STATES.indexOf(s), 3);
    }

    private static boolean canMove(String from, String to) {
        return switch (from) {
            case "READY" -> List.of("STARTING", "BLOCKED", "CANCELLED").contains(to);
            case "STARTING" -> List.of("RUNNING", "BLOCKED", "FAILED", "CANCELLED", "OUTCOME_UNKNOWN").contains(to);
            case "RUNNING" -> List.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "OUTCOME_UNKNOWN").contains(to);
            default -> false;
        };
    }

    /** {@code SupervisorReport.decide} da API. */
    private String decide(Run run, Map<String, Object> next) {
        String st = (String) next.get("state");
        String d = details(next);
        String o;
        if (st.equals(run.state)) {
            o = d.equals(run.details) ? "UNCHANGED" : terminal(st) ? "CONFLICT" : "APPLIED";
        } else if (canMove(run.state, st)) {
            o = "APPLIED";
        } else if (!terminal(st) && rank(st) < rank(run.state)) {
            o = "STALE";
        } else {
            o = "CONFLICT";
        }
        if (o.equals("APPLIED")) {
            run.state = st;
            run.details = d;
        }
        if (o.equals("CONFLICT")) run.conflicts++;
        run.outcomes.add(o);
        return o;
    }

    private static String details(Map<String, Object> s) {
        Map<String, Object> t = new TreeMap<>(s);
        t.remove("state");
        return t.toString();
    }

    // ----- eventos (sec. 8): eventos primeiro, ausências depois, cursores no fim

    private Response events(Agent m, String ex, Map<String, Object> j) {
        Run run = runs.get(m.id + "/" + ex);
        List<?> events = Json.list(j, "events");
        List<?> missing = j.containsKey("declaredMissing") ? Json.list(j, "declaredMissing") : List.of();
        if (events == null || missing == null || events.size() > 500 || missing.size() > 100) return err(400, "invalid_request", null);
        if (run == null) return err(404, "not_found", null);
        List<String> results = new ArrayList<>();
        Map<Long, String> inBatch = new LinkedHashMap<>();
        for (Object o : events) {
            Map<String, Object> e = Json.object(o);
            Long seq = e == null ? null : Json.number(e, "sequence");
            String invalid = e == null ? "objeto" : invalidSequences.contains(seq) ? "unknown_field" : invalid(e, ex);
            if (invalid != null) {
                results.add(item(seq, "INVALID", invalid));
                continue;
            }
            String hash = sha(new TreeMap<>(e).toString());
            if (inBatch.containsKey(seq)) {
                boolean same = inBatch.get(seq).equals(hash);
                results.add(item(seq, same ? "DUPLICATE" : "CONFLICT", same ? null : "conflito_no_lote"));
                continue;
            }
            inBatch.put(seq, hash);
            if (covered(run.declared, seq)) {
                results.add(item(seq, "CONFLICT", "declarada_ausente"));
            } else if (run.events.containsKey(seq)) {
                boolean same = run.events.get(seq).equals(hash);
                results.add(item(seq, same ? "DUPLICATE" : "CONFLICT", same ? null : "conteudo_diferente"));
            } else {
                run.events.put(seq, hash);
                results.add(item(seq, "STORED", null));
            }
        }
        List<String> mres = new ArrayList<>();
        for (Object o : missing) {
            Map<String, Object> r = Json.object(o);
            long from = Json.number(r, "from");
            long to = Json.number(r, "to");
            boolean same = run.declared.stream().anyMatch(d -> d[0] == from && d[1] == to);
            boolean overlaps = run.declared.stream().anyMatch(d -> d[0] <= to && from <= d[1]);
            boolean storedInside = !run.events.subMap(from, true, to, true).isEmpty();
            String st;
            String reason = null;
            if (same) st = "DUPLICATE";
            else if (storedInside || overlaps) {
                st = "CONFLICT";
                reason = storedInside ? "evento_gravado" : "sobreposicao";
            } else {
                run.declared.add(new long[] {from, to});
                st = "STORED";
            }
            mres.add(Json.obj().num("from", from).num("to", to).str("status", st).str("reason", reason).toString());
        }
        // cursores: só avançam sobre intervalo contínuo; declarado conta só no settled
        long stored = run.stored;
        while (run.events.containsKey(stored + 1)) stored++;
        long settled = Math.max(run.settled, stored);
        while (true) {
            long n = settled + 1;
            if (run.events.containsKey(n) || covered(run.declared, n)) settled = n;
            else break;
        }
        run.stored = stored;
        run.settled = settled;
        String ack = Json.obj().str("runId", run.id.toString()).num("storedThrough", stored).num("settledThrough", settled).raw("results", Json.list(results))
                .raw("missing", Json.list(mres)).toString();
        if (ackTamper != null) {
            ack = ackTamper.apply(ack);
            ackTamper = null;
        }
        return ok(ack);
    }

    private static String invalid(Map<String, Object> e, String ex) {
        if (!"nekkora-agent/observability".equals(e.get("schema"))) return "schema";
        String v = Json.text(e, "version");
        if (v == null || !v.matches("1\\.[0-9]+\\.[0-9]+")) return "versao";
        if (v.startsWith("1.0.") && !FIELDS.containsAll(e.keySet())) return "unknown_field";
        if (!ex.equals(e.get("agent_execution_id"))) return "execucao";
        Long seq = Json.number(e, "sequence");
        if (seq == null || seq < 1 || seq > 25_000) return "sequence";
        return null;
    }

    private static boolean covered(List<long[]> declared, long seq) {
        return declared.stream().anyMatch(d -> d[0] <= seq && seq <= d[1]);
    }

    private static String item(Long seq, String status, String reason) {
        return Json.obj().num("sequence", seq).str("status", status).str("reason", reason).toString();
    }

    // ----- apoio

    private static Response ok(String json) {
        return status(200, json);
    }

    private static Response status(int s, String json) {
        return new Response(s, Optional.empty(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static Response err(int s, String code, Long retryAfter) {
        return new Response(s, Optional.ofNullable(retryAfter), ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    static String secret() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
