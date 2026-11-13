package com.nekkora.agent.pack;

import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.StrictJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AGENT-PACOTES-001: pacotes gerados NESTA máquina pelo assistente ({@code nekkora-agent/package-history} 1.0.0), com a
 * ASSOCIAÇÃO LOCAL à organização (o manifesto não tem campo para ela). Serve para: mostrar a organização associada, oferecer
 * reutilizar um pacote local de mesmo conteúdo e sugerir a próxima versão declarada a partir do que esta máquina gerou.
 * Nunca é a lista de versões da Platform, nunca é enviada a ela e nunca autoriza nada.
 * <p>
 * Isolamento: toda consulta exige a origem da Platform e a organização; um pacote de outra organização nunca aparece. O nome do
 * projeto entra só como resumo SHA-256 (o nome pode ser de um cliente); o caminho do ZIP é o que a pessoa escolheu.
 */
public final class PackageHistory {
    public static final String SCHEMA = "nekkora-agent/package-history";
    public static final String VERSION = "1.0.0";
    static final int MAX = 500;
    private static final Pattern SEMVER = Pattern.compile("([0-9]{1,4})\\.([0-9]{1,4})\\.([0-9]{1,6})");

    /**
     * Um pacote gerado.
     *
     * @param projectKey {@code automation:<uuid>} (automação escolhida por identificador) ou {@code local:<resumo do nome>}
     */
    public record Entry(String origin, UUID organizationId, UUID agentId, String projectKey, String version, String contentIdentity, String fingerprint,
                        Path zip, Instant createdAt) {}

    private final Path file;
    private final AtomicWriter writer;

    public PackageHistory(Path file, AtomicWriter writer) {
        this.file = file;
        this.writer = writer;
    }

    public static Path defaultFile(Path agentDataRoot) {
        return ProjectImporter.defaultRoot(agentDataRoot).resolve("historico.json");
    }

    /** Chave de um projeto escolhido por identificador estável. */
    public static String automationKey(UUID automationId) {
        return "automation:" + automationId;
    }

    /** Chave de um projeto novo, local, pelo nome (sem diferença de caixa); nome igual NÃO prova que é o mesmo projeto. */
    public static String localKey(String name) {
        var md = ProjectImporter.sha256();
        return "local:" + java.util.HexFormat.of().formatHex(md.digest(name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
    }

    /** Pacotes desta origem e organização para o projeto, mais recentes primeiro. */
    public synchronized List<Entry> forProject(String origin, UUID organization, String projectKey) {
        return load().stream().filter(e -> e.origin().equals(origin) && e.organizationId().equals(organization) && e.projectKey().equals(projectKey))
                .sorted(Comparator.comparing(Entry::createdAt).reversed()).toList();
    }

    /** Um pacote desta origem e organização com o MESMO conteúdo, cujo arquivo ainda existe. */
    public synchronized Optional<Entry> sameContent(String origin, UUID organization, String contentIdentity) {
        return load().stream().filter(e -> e.origin().equals(origin) && e.organizationId().equals(organization) && e.contentIdentity().equals(contentIdentity))
                .filter(e -> Files.isRegularFile(e.zip(), LinkOption.NOFOLLOW_LINKS)).max(Comparator.comparing(Entry::createdAt));
    }

    /**
     * Próxima versão declarada sugerida para o projeto: a maior gerada aqui com o terceiro número + 1. Vazio se esta máquina não
     * gerou nada para ele (aí não há base: a pessoa informa).
     */
    public synchronized Optional<String> nextVersion(String origin, UUID organization, String projectKey) {
        return forProject(origin, organization, projectKey).stream().map(Entry::version).filter(v -> SEMVER.matcher(v).matches())
                .max(PackageHistory::compare).map(v -> {
                    var m = SEMVER.matcher(v);
                    m.matches();
                    return m.group(1) + "." + m.group(2) + "." + (Long.parseLong(m.group(3)) + 1);
                });
    }

    static int compare(String a, String b) {
        var x = SEMVER.matcher(a);
        var y = SEMVER.matcher(b);
        x.matches();
        y.matches();
        for (int i = 1; i <= 3; i++) {
            int c = Long.compare(Long.parseLong(x.group(i)), Long.parseLong(y.group(i)));
            if (c != 0) return c;
        }
        return 0;
    }

    public synchronized void record(Entry e) throws IOException {
        List<Entry> all = new ArrayList<>(load());
        all.removeIf(x -> x.zip().equals(e.zip()) && x.organizationId().equals(e.organizationId()));
        all.add(e);
        while (all.size() > MAX) all.remove(0);
        save(all);
    }

    List<Entry> load() {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try {
            Map<String, Object> m = com.nekkora.agent.exec.JsonReader.object(Files.readString(file, StandardCharsets.UTF_8));
            if (!SCHEMA.equals(m.get("schema")) || !VERSION.equals(m.get("version")) || !(m.get("packages") instanceof List<?> list)) return List.of();
            List<Entry> out = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> x)) return List.of();
                out.add(new Entry((String) x.get("origin"), UUID.fromString((String) x.get("organizationId")), UUID.fromString((String) x.get("agentId")),
                        (String) x.get("projectKey"), (String) x.get("version"), (String) x.get("contentIdentity"), (String) x.get("fingerprint"),
                        Path.of((String) x.get("zip")), Instant.parse((String) x.get("createdAt"))));
            }
            return out;
        } catch (IOException | com.nekkora.agent.exec.JsonReader.Invalid | RuntimeException e) {
            return List.of(); // ilegível: sem histórico (nada é presumido)
        }
    }

    private void save(List<Entry> all) throws IOException {
        StringBuilder b = new StringBuilder("{\n  \"schema\": ").append(StrictJson.quote(SCHEMA)).append(",\n  \"version\": ").append(StrictJson.quote(VERSION))
                .append(",\n  \"packages\": [");
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("    {\"origin\": ").append(StrictJson.quote(e.origin())).append(", \"organizationId\": ")
                    .append(StrictJson.quote(e.organizationId().toString())).append(", \"agentId\": ").append(StrictJson.quote(e.agentId().toString()))
                    .append(", \"projectKey\": ").append(StrictJson.quote(e.projectKey())).append(", \"version\": ").append(StrictJson.quote(e.version()))
                    .append(", \"contentIdentity\": ").append(StrictJson.quote(e.contentIdentity())).append(", \"fingerprint\": ")
                    .append(StrictJson.quote(e.fingerprint())).append(", \"zip\": ").append(StrictJson.quote(e.zip().toString())).append(", \"createdAt\": ")
                    .append(StrictJson.quote(e.createdAt().toString())).append('}');
        }
        b.append(all.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        Files.createDirectories(file.getParent());
        writer.write(file, b.toString().getBytes(StandardCharsets.UTF_8));
    }
}
