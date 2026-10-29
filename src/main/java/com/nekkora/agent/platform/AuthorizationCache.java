package com.nekkora.agent.platform;

import com.nekkora.agent.exec.JsonReader;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Autorizações de execução local do Worker desta máquina (contrato, sec. 6.1 e 6.2), baixadas de
 * {@code GET /agent/api/v1/authorizations} e guardadas para uso OFFLINE dentro do {@code validUntil} de cada uma. O
 * arquivo pertence ao Agent que o baixou: com outro vínculo ele não vale. A lista do servidor só traz as ATIVAS; uma que
 * sumiu num download posterior passa a ser "encerrada na Platform" (revogada, vencida ou Worker desautorizado).
 * <p>
 * Uma autorização só serve a um pacote cuja impressão ({@code nekkora-agent/package-fingerprint/1}, calculada pelo
 * próprio Agent) é a dela. Regra desconhecida = não serve. Nome de pacote nunca decide nada.
 */
final class AuthorizationCache {
    static final String FILE = "autorizacoes.json";
    static final String SCHEMA = "nekkora-agent/platform-authorizations";
    static final String VERSION = "1.0.0";
    static final int MAX_ITEMS = 500;

    /** Uma autorização como o servidor a publicou. {@code fingerprint}: 64 hex, sem o prefixo {@code sha256:}. */
    record Authorization(UUID authorizationId, UUID workspaceId, UUID automationId, UUID automationVersionId, String fingerprint, String rule,
                         Instant validUntil) {}

    /**
     * Situação de uma autorização diante de um pacote, no instante {@code now} do relógio local — nunca antes do último
     * instante já observado (D-AP-2: atrasar o relógio não devolve o prazo de uma autorização vencida).
     */
    enum Usability { USABLE, EXPIRED, RULE_UNKNOWN }

    private final Path dir;
    private final AtomicWriter writer;
    private UUID agentId;
    private Instant downloadedAt;
    /** Maior hora informada pelo servidor (presença, canal autenticado por TLS), gravada no download (D-AP-2). */
    private Instant serverSeen;
    /**
     * Piso do relógio (D-AP-2), só na memória: a hora do servidor e, entre contatos, o maior instante local já consultado.
     * Atrasar o relógio não devolve o prazo de uma autorização vencida; um relógio adiantado só vence antes (falha
     * fechada) até a próxima hora do servidor, que é a referência e corrige o piso.
     */
    private Instant observed;
    private List<Authorization> items = List.of();
    private Set<UUID> ended = new TreeSet<>();

    AuthorizationCache(Path dir, AtomicWriter writer) {
        this.dir = dir;
        this.writer = writer;
    }

    synchronized void load(UUID currentAgent) {
        agentId = null;
        downloadedAt = null;
        serverSeen = null;
        observed = null;
        items = List.of();
        ended = new TreeSet<>();
        try {
            Map<String, Object> m = Json.parse(Files.readString(dir.resolve(FILE), StandardCharsets.UTF_8));
            if (!SCHEMA.equals(m.get("schema")) || !VERSION.equals(m.get("version"))) return;
            UUID a = Json.uuid(m, "agentId");
            if (a == null || !a.equals(currentAgent)) return; // de outro vínculo: não vale
            List<Authorization> list = new ArrayList<>();
            for (Object o : Json.list(m, "items")) {
                Authorization z = parseItem(Json.object(o));
                if (z != null) list.add(z);
            }
            Set<UUID> gone = new TreeSet<>();
            List<?> e = Json.list(m, "ended");
            if (e != null) for (Object o : e) if (o instanceof String s) gone.add(UUID.fromString(s));
            agentId = a;
            downloadedAt = Json.instant(m, "downloadedAt");
            serverSeen = Json.instant(m, "serverTimeAt"); // ausente nas gravações anteriores
            observed = serverSeen;
            items = List.copyOf(list);
            ended = gone;
        } catch (NoSuchFileException e) {
            // nunca baixadas
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            // ilegível: como se nunca tivessem sido baixadas (nada é presumido autorizado)
        }
    }

    /** Item da resposta do servidor; campo faltando ou malformado = item recusado. */
    static Authorization parseItem(Map<String, Object> m) {
        if (m == null) return null;
        UUID id = Json.uuid(m, "authorizationId");
        String fp = Json.text(m, "packageFingerprint");
        String rule = Json.text(m, "packageFingerprintRule");
        Instant until = Json.instant(m, "validUntil");
        if (id == null || fp == null || !fp.matches("[0-9a-f]{64}") || rule == null || rule.length() > 80 || until == null) return null;
        return new Authorization(id, Json.uuid(m, "workspaceId"), Json.uuid(m, "automationId"), Json.uuid(m, "automationVersionId"), fp, rule, until);
    }

    /** Download bem-sucedido: substitui a lista; as que estavam e sumiram passam a "encerradas na Platform". */
    synchronized void replace(UUID agent, List<Authorization> fresh, Instant now) throws IOException {
        Set<UUID> before = new TreeSet<>();
        if (agent.equals(agentId)) for (Authorization z : items) before.add(z.authorizationId());
        Set<UUID> gone = agent.equals(agentId) ? new TreeSet<>(ended) : new TreeSet<>();
        for (Authorization z : fresh) gone.remove(z.authorizationId());
        for (UUID b : before) if (fresh.stream().noneMatch(z -> z.authorizationId().equals(b))) gone.add(b);
        List<String> js = new ArrayList<>();
        for (Authorization z : fresh) {
            js.add(Json.obj().str("authorizationId", z.authorizationId().toString()).str("workspaceId", s(z.workspaceId())).str("automationId", s(z.automationId()))
                    .str("automationVersionId", s(z.automationVersionId())).str("packageFingerprint", z.fingerprint()).str("packageFingerprintRule", z.rule())
                    .str("validUntil", z.validUntil().toString()).toString());
        }
        List<String> endedJs = new ArrayList<>();
        for (UUID g : gone) endedJs.add(com.nekkora.agent.storage.StrictJson.quote(g.toString()));
        Json.Obj o = Json.obj().str("schema", SCHEMA).str("version", VERSION).str("agentId", agent.toString()).str("downloadedAt", now.toString());
        if (serverSeen != null) o.str("serverTimeAt", serverSeen.toString());
        String doc = o
                .raw("items", Json.list(js)).raw("ended", Json.list(endedJs)) + "\n";
        Files.createDirectories(dir);
        writer.write(dir.resolve(FILE), doc.getBytes(StandardCharsets.UTF_8));
        agentId = agent;
        downloadedAt = now;
        items = List.copyOf(fresh);
        ended = gone;
    }

    synchronized Optional<Instant> downloadedAt() {
        return Optional.ofNullable(downloadedAt);
    }

    synchronized List<Authorization> all() {
        return items;
    }

    /** As autorizações que citam exatamente esta impressão, com a situação de cada uma agora. */
    synchronized Map<Authorization, Usability> forFingerprint(String fingerprintHex, Instant now) {
        now = effective(now);
        Map<Authorization, Usability> out = new LinkedHashMap<>();
        for (Authorization z : items) {
            if (!z.fingerprint().equals(fingerprintHex)) continue;
            Usability u = !PackageFingerprint.RULE.equals(z.rule()) ? Usability.RULE_UNKNOWN : now.isBefore(z.validUntil()) ? Usability.USABLE : Usability.EXPIRED;
            out.put(z, u);
        }
        return out;
    }

    /** Instante usado para conferir prazos: o relógio local, nunca abaixo do piso; a consulta também eleva o piso. */
    synchronized Instant effective(Instant now) {
        observed = later(observed, now);
        return observed;
    }

    /** Hora informada pelo servidor (presença): passa a ser o piso (gravada no próximo download). */
    synchronized void observe(Instant serverTime) {
        if (serverTime == null) return;
        serverSeen = later(serverSeen, serverTime);
        observed = serverTime;
    }

    private static Instant later(Instant a, Instant b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }

    synchronized Optional<Authorization> find(UUID id) {
        return items.stream().filter(z -> z.authorizationId().equals(id)).findFirst();
    }

    synchronized boolean endedOnPlatform(UUID id) {
        return ended.contains(id);
    }

    synchronized void clear() throws IOException {
        Files.deleteIfExists(dir.resolve(FILE));
        agentId = null;
        downloadedAt = null;
        items = List.of();
        ended = new TreeSet<>();
    }

    private static String s(UUID u) {
        return u == null ? null : u.toString();
    }
}
