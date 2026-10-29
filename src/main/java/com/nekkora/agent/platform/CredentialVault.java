package com.nekkora.agent.platform;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.StrictJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vínculo da máquina com a Platform (contrato, sec. 4). Dois lugares, com papéis separados:
 * <ul>
 *   <li>{@code vinculo.json}: identificadores e situação, SEM segredo (origem do pareamento, organização, Agent,
 *       instalação, Worker, credencial, validade, recusa e o resgate em andamento);</li>
 *   <li>{@link SecretStore} (DPAPI): {@code credencial} = a credencial inteira {@code nka1.<org>.<credencial>.<segredo>}, e
 *       {@code resgate} = o código e o segredo NOVO de um resgate cujo resultado ainda não se conhece.</li>
 * </ul>
 * Ordem das gravações (cada uma atômica), para sobreviver a uma queda em qualquer ponto:
 * <ol>
 *   <li>início: o material do resgate (código + segredo gerado aqui) vai para a guarda protegida ANTES de o pendente ser
 *       anotado no vínculo e, portanto, antes de qualquer tentativa — uma resposta perdida é repetida com o MESMO material;</li>
 *   <li>confirmação: primeiro a credencial nova (com o próprio identificador), depois o vínculo sem o pendente, e só
 *       então o material do resgate é apagado. Uma queda entre os passos deixa o pendente: a repetição devolve o mesmo
 *       resultado e os passos são refeitos. Na rotação, a credencial anterior só é sobrescrita depois que a nova foi
 *       confirmada, e o material do resgate continua até o vínculo registrar a nova.</li>
 * </ol>
 * Uma repetição recusada ({@code 401}) encerra o pendente sem restaurar nada (o servidor não reativa credencial
 * revogada nem desfaz rotação, sec. 4.2).
 */
final class CredentialVault {
    static final String FILE = "vinculo.json";
    static final String SCHEMA = "nekkora-agent/platform-link";
    static final String VERSION = "1.0.0";
    static final String CREDENTIAL = "credencial";
    static final String REDEEM = "resgate";
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern CODE = Pattern.compile("nkp1\\.(" + UUID_RE + ")\\.(" + UUID_RE + ")\\.[A-Za-z0-9_-]{43}");
    private static final Pattern TOKEN = Pattern.compile("nka1\\.(" + UUID_RE + ")\\.(" + UUID_RE + ")\\.[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Recusa local de uma operação do vínculo (nada foi gravado). */
    static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;
        private final transient ErrorCode code;

        Refused(ErrorCode code) {
            super(code.code(), null, false, false);
            this.code = code;
        }

        ErrorCode code() {
            return code;
        }
    }

    /** Vínculo confirmado pela Platform. {@code refused}: a Platform recusou a credencial (o vínculo fica, só não é usado). */
    record Link(Destination destination, UUID organizationId, UUID agentId, UUID installationId, UUID workerId, UUID credentialId,
                Instant credentialExpiresAt, Instant pairedAt, boolean refused, Instant refusedAt) {}

    enum Kind { PAIRING, ROTATION }

    /** Resgate cujo resultado ainda não se conhece. */
    record Pending(Kind kind, Destination destination, Instant startedAt) {}

    /** O material do resgate (só em memória, só para a chamada). */
    record Material(String code, String secret) {
        @Override
        public String toString() {
            return "Material[<segredo>]";
        }
    }

    /** Resposta 200 do resgate (sec. 4.2). */
    record Redeemed(UUID organizationId, UUID agentId, UUID installationId, UUID workerId, UUID credentialId, Instant credentialExpiresAt) {}

    private final Path dir;
    private final SecretStore secrets;
    private final AtomicWriter writer;
    private Link link;
    private Pending pending;
    private String lastRefusal;
    private volatile String token;

    CredentialVault(Path dir, SecretStore secrets, AtomicWriter writer) {
        this.dir = dir;
        this.secrets = secrets;
        this.writer = writer;
    }

    /** Lê o vínculo; um material de resgate sem pendente anotado (queda entre os passos) é apagado. */
    synchronized void load() throws IOException {
        link = null;
        pending = null;
        token = null;
        Path f = dir.resolve(FILE);
        try {
            Map<String, Object> m = StrictJson.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            if (!SCHEMA.equals(m.get("schema")) || !VERSION.equals(m.get("version"))) throw new IOException("vínculo de versão desconhecida");
            lastRefusal = m.get("lastRefusal") instanceof String s ? s : null;
            if (m.get("agentId") instanceof String) {
                link = new Link(new Destination((String) m.get("origin")), id(m, "organizationId"), id(m, "agentId"), id(m, "installationId"),
                        id(m, "workerId"), id(m, "credentialId"), instant(m.get("credentialExpiresAt")), instant(m.get("pairedAt")),
                        Boolean.TRUE.equals(m.get("refused")), instant(m.get("refusedAt")));
            }
            if (m.get("pending") instanceof Map<?, ?> p) {
                pending = new Pending(Kind.valueOf((String) p.get("kind")), new Destination((String) p.get("origin")), instant(p.get("startedAt")));
            }
        } catch (NoSuchFileException e) {
            // sem vínculo
        } catch (StrictJson.SyntaxException | RuntimeException e) {
            throw new IOException("vínculo ilegível");
        }
        if (pending == null) secrets.delete(REDEEM);
        if (link != null) {
            Optional<byte[]> t = secrets.read(CREDENTIAL);
            String s = t.map(b -> new String(b, StandardCharsets.US_ASCII)).orElse(null);
            Matcher mt = s == null ? null : TOKEN.matcher(s);
            // a credencial guardada tem de ser a do vínculo (organização e identificador); senão o vínculo não é usado
            token = mt != null && mt.matches() && mt.group(1).equals(link.organizationId().toString()) ? s : null;
            if (token != null && !mt.group(2).equals(link.credentialId().toString())) {
                // queda entre gravar a credencial nova e o vínculo: o identificador certo é o da credencial guardada
                link = new Link(link.destination(), link.organizationId(), link.agentId(), link.installationId(), link.workerId(),
                        UUID.fromString(mt.group(2)), link.credentialExpiresAt(), link.pairedAt(), link.refused(), link.refusedAt());
            }
        }
    }

    synchronized Optional<Link> link() {
        return Optional.ofNullable(link);
    }

    synchronized Optional<Pending> pending() {
        return Optional.ofNullable(pending);
    }

    /** Código do último resgate recusado (para a tela), ou {@code null}. */
    synchronized String lastRefusal() {
        return lastRefusal;
    }

    /** A credencial inteira para o {@code Authorization} (só enquanto o vínculo vale e não foi recusado). */
    Optional<String> token() {
        String t = token;
        Link l;
        synchronized (this) {
            l = link;
        }
        return l == null || l.refused() ? Optional.empty() : Optional.ofNullable(t);
    }

    /** Material de resgate pendente (lido da guarda protegida). */
    synchronized Optional<Material> material() throws IOException {
        if (pending == null) return Optional.empty();
        Optional<byte[]> b = secrets.read(REDEEM);
        if (b.isEmpty()) return Optional.empty();
        String s = new String(b.get(), StandardCharsets.US_ASCII);
        int nl = s.indexOf('\n');
        if (nl < 0) return Optional.empty();
        return Optional.of(new Material(s.substring(0, nl), s.substring(nl + 1)));
    }

    /** Pareamento iniciado pela pessoa: o material vai para a guarda ANTES do pendente (e de qualquer tentativa). */
    synchronized void beginPairing(Destination d, String code, Instant now) throws Refused, IOException {
        if (!secrets.available()) throw new Refused(ErrorCode.PLT_SECRET_STORE_UNAVAILABLE);
        if (link != null) throw new Refused(ErrorCode.PLT_ALREADY_PAIRED);
        if (pending != null) throw new Refused(ErrorCode.PLT_ALREADY_PAIRED);
        if (code == null || !CODE.matcher(code).matches()) throw new Refused(ErrorCode.PLT_CODE_INVALID);
        secrets.write(REDEEM, (code + "\n" + newSecret()).getBytes(StandardCharsets.US_ASCII));
        pending = new Pending(Kind.PAIRING, d, now);
        lastRefusal = null;
        save();
    }

    /** Rotação iniciada pela pessoa com o código gerado na Platform (sec. 4.3); exige o vínculo atual. */
    synchronized void beginRotation(String code, Instant now) throws Refused, IOException {
        if (!secrets.available()) throw new Refused(ErrorCode.PLT_SECRET_STORE_UNAVAILABLE);
        if (link == null) throw new Refused(ErrorCode.PLT_NOT_PAIRED);
        if (pending != null) throw new Refused(ErrorCode.PLT_ALREADY_PAIRED);
        Matcher m = code == null ? null : CODE.matcher(code);
        if (m == null || !m.matches() || !m.group(1).equals(link.organizationId().toString())) throw new Refused(ErrorCode.PLT_CODE_INVALID);
        secrets.write(REDEEM, (code + "\n" + newSecret()).getBytes(StandardCharsets.US_ASCII));
        pending = new Pending(Kind.ROTATION, link.destination(), now);
        lastRefusal = null;
        save();
    }

    /**
     * Resgate confirmado (200). Pareamento: o vínculo nasce aqui. Rotação: só vale para o MESMO Agent e organização
     * (senão a resposta é incoerente e nada muda). Devolve se foi aplicado.
     */
    synchronized boolean confirm(Redeemed r, Instant now) throws IOException {
        if (pending == null) return false;
        Material mat = material().orElseThrow(() -> new IOException("material do resgate ausente"));
        if (pending.kind() == Kind.ROTATION && (link == null || !link.agentId().equals(r.agentId()) || !link.organizationId().equals(r.organizationId()))) {
            return false;
        }
        String t = "nka1." + r.organizationId() + "." + r.credentialId() + "." + mat.secret();
        secrets.write(CREDENTIAL, t.getBytes(StandardCharsets.US_ASCII));
        Destination d = pending.kind() == Kind.PAIRING ? pending.destination() : link.destination();
        Instant paired = pending.kind() == Kind.PAIRING || link == null ? now : link.pairedAt();
        link = new Link(d, r.organizationId(), r.agentId(), r.installationId(), r.workerId(), r.credentialId(), r.credentialExpiresAt(), paired, false, null);
        token = t;
        pending = null;
        save();
        secrets.delete(REDEEM);
        return true;
    }

    /** O resgate pendente foi recusado de forma definitiva: o pendente sai, nada é restaurado. */
    synchronized void abandon(ErrorCode why) throws IOException {
        pending = null;
        lastRefusal = why.code();
        save();
        secrets.delete(REDEEM);
    }

    /** A Platform recusou a credencial ({@code 401}): nenhuma chamada autenticada até a pessoa decidir. */
    synchronized void refuse(Instant now) throws IOException {
        if (link == null || link.refused()) return;
        link = new Link(link.destination(), link.organizationId(), link.agentId(), link.installationId(), link.workerId(), link.credentialId(),
                link.credentialExpiresAt(), link.pairedAt(), true, now);
        save();
    }

    /** Nova validade informada pela Platform (heartbeat). */
    synchronized void expiresAt(Instant at) throws IOException {
        if (link == null || at == null || at.equals(link.credentialExpiresAt())) return;
        link = new Link(link.destination(), link.organizationId(), link.agentId(), link.installationId(), link.workerId(), link.credentialId(), at,
                link.pairedAt(), link.refused(), link.refusedAt());
        save();
    }

    /**
     * Remove o vínculo local (decisão da pessoa, depois de uma recusa ou para parear de novo): credencial, material e
     * vínculo. No servidor nada muda — a revogação é ato da Platform (sec. 4.3).
     */
    synchronized void remove() throws IOException {
        secrets.delete(CREDENTIAL);
        secrets.delete(REDEEM);
        Files.deleteIfExists(dir.resolve(FILE));
        link = null;
        pending = null;
        token = null;
        lastRefusal = null;
    }

    static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static boolean validCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    private void save() throws IOException {
        Json.Obj o = Json.obj().str("schema", SCHEMA).str("version", VERSION);
        if (link != null) {
            o.str("origin", link.destination().origin()).str("organizationId", link.organizationId().toString()).str("agentId", link.agentId().toString())
                    .str("installationId", link.installationId() == null ? null : link.installationId().toString())
                    .str("workerId", link.workerId() == null ? null : link.workerId().toString()).str("credentialId", link.credentialId().toString())
                    .str("credentialExpiresAt", text(link.credentialExpiresAt())).str("pairedAt", text(link.pairedAt())).bool("refused", link.refused())
                    .str("refusedAt", text(link.refusedAt()));
        }
        if (pending != null) {
            o.raw("pending", Json.obj().str("kind", pending.kind().name()).str("origin", pending.destination().origin()).str("startedAt", text(pending.startedAt()))
                    .toString());
        }
        o.str("lastRefusal", lastRefusal);
        Files.createDirectories(dir);
        writer.write(dir.resolve(FILE), (o + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String text(Instant i) {
        return i == null ? null : i.toString();
    }

    private static UUID id(Map<String, Object> m, String k) {
        return m.get(k) instanceof String s ? UUID.fromString(s) : null;
    }

    private static Instant instant(Object v) {
        return v instanceof String s ? Instant.parse(s) : null;
    }
}
