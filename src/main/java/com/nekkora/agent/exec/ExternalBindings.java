package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.StrictJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AGENT-PYTHON-001 (Marco 1): vínculos LOCAIS aprovados entre uma automação cadastrada na Platform ({@code automationId}) e
 * a configuração do Python do cliente nesta máquina: interpretador, script e pasta de trabalho, todos absolutos. Arquivo
 * {@code <dados>/externo/vinculos.json} ({@code nekkora-agent/external-bindings} 1.0.0), gravação atômica.
 * <p>
 * O vínculo é um ato da pessoa NA MÁQUINA (comando {@code vincular} no console); nada aqui vem da rede. Os caminhos NUNCA
 * saem da máquina: não vão para a Platform, para o registro da execução nem para os eventos. Um processo nunca é adotado
 * pelo nome: só o lançador, chamado com o nome do vínculo, cria o processo, e só se os caminhos forem os aprovados.
 * Arquivo ilegível vale como "nenhum vínculo" (nada é presumido aprovado).
 */
public final class ExternalBindings {
    public static final String SCHEMA = "nekkora-agent/external-bindings";
    public static final String VERSION = "1.0.0";
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    static final int MAX = 100;

    /** Um vínculo aprovado. {@code approvedAt}: instante local do ato. */
    public record Binding(String name, UUID automationId, Path interpreter, Path script, Path workDir, Instant approvedAt) {}

    private final Path file;
    private final AtomicWriter writer;

    public ExternalBindings(Path file, AtomicWriter writer) {
        this.file = file;
        this.writer = writer;
    }

    /** Pasta e arquivo padrão dentro da pasta de dados do Agent. */
    public static Path defaultFile(Path agentDataRoot) {
        return agentDataRoot.resolve("externo").resolve("vinculos.json");
    }

    public synchronized Optional<Binding> find(String name) {
        return Optional.ofNullable(load().get(name));
    }

    public synchronized Map<String, Binding> all() {
        return load();
    }

    /**
     * Confere e grava (substitui o de mesmo nome). Caminhos absolutos que existem: interpretador e script arquivos comuns,
     * pasta de trabalho diretório. Recusa: {@link ErrorCode#EXT_BINDING_INVALID}.
     */
    public synchronized Binding approve(String name, UUID automationId, Path interpreter, Path script, Path workDir, Instant at) throws ExecRefused {
        if (name == null || !NAME.matcher(name).matches() || automationId == null) throw new ExecRefused(ErrorCode.EXT_BINDING_INVALID);
        Path i = canonicalFile(interpreter);
        Path s = canonicalFile(script);
        Path w = canonicalDir(workDir);
        Binding b = new Binding(name, automationId, i, s, w, at);
        Map<String, Binding> all = new TreeMap<>(load());
        all.put(name, b);
        if (all.size() > MAX) throw new ExecRefused(ErrorCode.EXT_BINDING_INVALID);
        save(all);
        return b;
    }

    /** Retira um vínculo (o lançador passa a recusar esse nome). */
    public synchronized boolean remove(String name) throws ExecRefused {
        Map<String, Binding> all = new TreeMap<>(load());
        if (all.remove(name) == null) return false;
        save(all);
        return true;
    }

    /**
     * O pedido do lançador corresponde ao aprovado? Interpretador, script e pasta de trabalho iguais (sem diferença de
     * maiúsculas nem de barras no Windows; caminho real, sem ligação).
     */
    public static boolean matches(Binding b, Path interpreter, Path script, Path currentDir) {
        try {
            return same(b.interpreter(), canonicalFile(interpreter)) && same(b.script(), canonicalFile(script)) && same(b.workDir(), canonicalDir(currentDir));
        } catch (ExecRefused e) {
            return false;
        }
    }

    static boolean same(Path a, Path b) {
        String x = a.toString().replace('/', '\\');
        String y = b.toString().replace('/', '\\');
        return windows() ? x.equalsIgnoreCase(y) : x.equals(y);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static Path canonicalFile(Path p) throws ExecRefused {
        if (p == null || !p.isAbsolute()) throw new ExecRefused(ErrorCode.EXT_BINDING_INVALID);
        try {
            if (!Files.isRegularFile(p)) throw new ExecRefused(ErrorCode.EXT_BINDING_INVALID);
            return p.toRealPath();
        } catch (IOException e) {
            throw new ExecRefused(ErrorCode.EXT_BINDING_INVALID);
        }
    }

    static Path canonicalDir(Path p) throws ExecRefused {
        if (p == null || !p.isAbsolute()) throw new ExecRefused(ErrorCode.EXT_BINDING_INVALID);
        try {
            if (!Files.isDirectory(p)) throw new ExecRefused(ErrorCode.EXT_BINDING_INVALID);
            return p.toRealPath();
        } catch (IOException e) {
            throw new ExecRefused(ErrorCode.EXT_BINDING_INVALID);
        }
    }

    Map<String, Binding> load() {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        try {
            Map<String, Object> m = StrictJson.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            if (!SCHEMA.equals(m.get("schema")) || !VERSION.equals(m.get("version")) || !(m.get("bindings") instanceof Map<?, ?> list)) return Map.of();
            Map<String, Binding> out = new TreeMap<>();
            for (Map.Entry<?, ?> e : list.entrySet()) {
                if (!(e.getKey() instanceof String n) || !NAME.matcher(n).matches() || !(e.getValue() instanceof Map<?, ?> b)
                        || !(b.get("automationId") instanceof String a) || !(b.get("interpreter") instanceof String i) || !(b.get("script") instanceof String s)
                        || !(b.get("workDir") instanceof String w) || !(b.get("approvedAt") instanceof String at)) {
                    return Map.of();
                }
                out.put(n, new Binding(n, UUID.fromString(a), Path.of(i), Path.of(s), Path.of(w), Instant.parse(at)));
            }
            return out;
        } catch (IOException | StrictJson.SyntaxException | RuntimeException e) {
            return Map.of();
        }
    }

    private void save(Map<String, Binding> all) throws ExecRefused {
        StringBuilder b = new StringBuilder("{\n  \"schema\": ").append(StrictJson.quote(SCHEMA)).append(",\n  \"version\": ").append(StrictJson.quote(VERSION))
                .append(",\n  \"bindings\": {");
        int n = 0;
        for (Binding x : all.values()) {
            b.append(n++ == 0 ? "\n" : ",\n").append("    ").append(StrictJson.quote(x.name())).append(": {\"automationId\": ")
                    .append(StrictJson.quote(x.automationId().toString())).append(", \"interpreter\": ").append(StrictJson.quote(x.interpreter().toString()))
                    .append(", \"script\": ").append(StrictJson.quote(x.script().toString())).append(", \"workDir\": ")
                    .append(StrictJson.quote(x.workDir().toString())).append(", \"approvedAt\": ").append(StrictJson.quote(x.approvedAt().toString())).append('}');
        }
        b.append(all.isEmpty() ? "}\n}\n" : "\n  }\n}\n");
        try {
            Files.createDirectories(file.getParent());
            writer.write(file, b.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ExecRefused(ErrorCode.EXT_STORAGE_FAILED);
        }
    }
}
