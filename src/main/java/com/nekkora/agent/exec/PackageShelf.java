package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.StrictJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * AGENT-PYTHON-001 (Marco 2): pacotes PRESENTES nesta máquina, cada um numa pasta própria {@code <dados>/pacotes/<impressão>}
 * com o Python preparado pela pessoa e a pasta de saída escolhida ({@code indice.json}, {@code nekkora-agent/package-shelf}
 * 1.0.0). Instalar é um ato da pessoa NA MÁQUINA (comando {@code pacote instalar}), sempre junto da APROVAÇÃO pela impressão
 * ({@link ApprovalStore}): a Platform nunca entrega pacote e um pedido de execução só encontra o que a pessoa trouxe e aprovou.
 * <p>
 * Extração do ZIP sem confiança: caminho absoluto, {@code ..}, barra invertida, dois-pontos, caractere de controle, nome
 * repetido (inclusive só na caixa), mais de 1000 entradas ou de 50 MiB descompactados são recusados. Depois, o conteúdo é
 * conferido como qualquer pacote ({@link ExecutionPackage#inspect}).
 */
public final class PackageShelf {
    public static final String SCHEMA = "nekkora-agent/package-shelf";
    public static final String VERSION = "1.0.0";
    static final int MAX_ENTRIES = 1000;
    static final long MAX_BYTES = 50L * 1024 * 1024;

    /** Um pacote presente: impressão ({@code sha256:…}), pasta, Python preparado e pasta de saída das execuções. */
    public record Entry(String fingerprint, Path dir, Path python, Path outputRoot, Instant installedAt) {}

    private final Path root;
    private final AtomicWriter writer;

    public PackageShelf(Path root, AtomicWriter writer) {
        this.root = root;
        this.writer = writer;
    }

    public static Path defaultRoot(Path agentDataRoot) {
        return agentDataRoot.resolve("pacotes");
    }

    public synchronized Optional<Entry> find(String fingerprint) {
        return Optional.ofNullable(load().get(fingerprint));
    }

    /**
     * Prepara um pacote para a pessoa conferir: de um ZIP (extraído com as recusas acima) ou de uma pasta (copiada), numa
     * pasta de preparo; devolve o pacote INSPECIONADO. Nada fica aprovado aqui.
     */
    public synchronized ExecutionPackage stage(Path source) throws ExecRefused {
        Path staging = root.resolve("preparo-" + Long.toHexString(System.nanoTime()));
        try {
            Files.createDirectories(staging);
            if (Files.isDirectory(source)) copyTree(source, staging);
            else unzip(source, staging);
        } catch (IOException e) {
            RunStore.deleteTree(staging);
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
        }
        try {
            return ExecutionPackage.inspect(singleRoot(staging));
        } catch (ExecRefused e) {
            RunStore.deleteTree(staging);
            throw e;
        } catch (IOException e) {
            RunStore.deleteTree(staging);
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
        }
    }

    /**
     * Fixa o pacote preparado na pasta definitiva (pela impressão) e registra Python e saída. Idempotente.
     * <p>
     * O ponto de confirmação é o {@code indice.json}: uma pasta definitiva SEM entrada no índice é sobra de uma instalação
     * interrompida e é refeita. No Windows, renomear uma pasta com subpastas é negado enquanto outro programa observa a árvore
     * (IDE, sincronizador, servidor de desenvolvimento); nesse caso o pacote é COPIADO para a pasta definitiva e conferido de novo
     * pela impressão antes de entrar no índice.
     */
    public synchronized Entry install(ExecutionPackage staged, Path python, Path outputRoot, Instant at) throws ExecRefused {
        String hex = staged.fingerprint().substring("sha256:".length());
        Path target = root.resolve(hex);
        try {
            Map<String, Entry> known = load();
            if (Files.isDirectory(target) && !known.containsKey(staged.fingerprint()) && !staged.dir().equals(target)) {
                RunStore.deleteTree(target); // sobra de instalação interrompida (nunca confirmada no índice)
            }
            if (!Files.isDirectory(target)) {
                try {
                    Files.move(staged.dir(), target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AccessDeniedException | java.nio.file.AtomicMoveNotSupportedException denied) {
                    RunStore.deleteTree(target);
                    copyTree(staged.dir(), target);
                }
                RunStore.deleteTree(stagingOf(staged.dir())); // sobra da pasta de preparo (melhor esforço)
            } else if (!staged.dir().equals(target)) {
                discard(staged); // o mesmo pacote já está presente: a preparação sai
            }
            ExecutionPackage again = ExecutionPackage.inspect(target);
            if (!again.fingerprint().equals(staged.fingerprint())) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_CHANGED);
            Entry e = new Entry(staged.fingerprint(), target, python.toAbsolutePath().normalize(), outputRoot.toAbsolutePath().normalize(), at);
            Map<String, Entry> all = new TreeMap<>(load());
            all.put(e.fingerprint(), e);
            save(all);
            return e;
        } catch (IOException e) {
            throw new ExecRefused(ErrorCode.EXEC_STORAGE_FAILED);
        }
    }

    /** Descarta uma preparação não aprovada. */
    public void discard(ExecutionPackage staged) {
        RunStore.deleteTree(stagingOf(staged.dir()));
    }

    private Path stagingOf(Path dir) {
        Path p = dir;
        while (p.getParent() != null && !p.getParent().equals(root)) p = p.getParent();
        return p;
    }

    /** O ZIP pode trazer a pasta do pacote (um único nível): o pacote é ela. */
    private static Path singleRoot(Path staging) throws IOException {
        try (var s = Files.list(staging)) {
            var items = s.toList();
            if (items.size() == 1 && Files.isDirectory(items.get(0), LinkOption.NOFOLLOW_LINKS)) return items.get(0);
        }
        return staging;
    }

    static void unzip(Path zip, Path into) throws IOException {
        Set<String> seen = new HashSet<>();
        long total = 0;
        int entries = 0;
        try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = z.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("entradas demais");
                String name = e.getName();
                if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.contains("..") || name.contains("//")
                        || name.chars().anyMatch(ch -> ch < 0x20) || !seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IOException("caminho inseguro no ZIP");
                }
                Path target = into.resolve(name).normalize();
                if (!target.startsWith(into)) throw new IOException("fora da pasta");
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (var out = Files.newOutputStream(target)) {
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) throw new IOException("tamanho excedido");
                        out.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var s = Files.walk(from)) {
            for (Path p : s.toList()) {
                if (Files.isSymbolicLink(p)) throw new IOException("ligação");
                Path t = to.resolve(from.relativize(p).toString()).normalize();
                if (!t.startsWith(to)) throw new IOException("fora da pasta");
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    Map<String, Entry> load() {
        Path f = root.resolve("indice.json");
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        try {
            Map<String, Object> m = StrictJson.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            if (!SCHEMA.equals(m.get("schema")) || !VERSION.equals(m.get("version")) || !(m.get("packages") instanceof Map<?, ?> list)) return Map.of();
            Map<String, Entry> out = new TreeMap<>();
            for (Map.Entry<?, ?> x : list.entrySet()) {
                if (!(x.getKey() instanceof String fp) || !fp.matches("sha256:[0-9a-f]{64}") || !(x.getValue() instanceof Map<?, ?> e)
                        || !(e.get("dir") instanceof String d) || !(e.get("python") instanceof String py) || !(e.get("outputRoot") instanceof String o)
                        || !(e.get("installedAt") instanceof String at)) {
                    return Map.of();
                }
                out.put(fp, new Entry(fp, Path.of(d), Path.of(py), Path.of(o), Instant.parse(at)));
            }
            return out;
        } catch (IOException | StrictJson.SyntaxException | RuntimeException e) {
            return Map.of();
        }
    }

    private void save(Map<String, Entry> all) throws IOException {
        StringBuilder b = new StringBuilder("{\n  \"schema\": ").append(StrictJson.quote(SCHEMA)).append(",\n  \"version\": ").append(StrictJson.quote(VERSION))
                .append(",\n  \"packages\": {");
        int n = 0;
        for (Entry e : all.values()) {
            b.append(n++ == 0 ? "\n" : ",\n").append("    ").append(StrictJson.quote(e.fingerprint())).append(": {\"dir\": ").append(StrictJson.quote(e.dir().toString()))
                    .append(", \"python\": ").append(StrictJson.quote(e.python().toString())).append(", \"outputRoot\": ")
                    .append(StrictJson.quote(e.outputRoot().toString())).append(", \"installedAt\": ").append(StrictJson.quote(e.installedAt().toString())).append('}');
        }
        b.append(all.isEmpty() ? "}\n}\n" : "\n  }\n}\n");
        Files.createDirectories(root);
        writer.write(root.resolve("indice.json"), b.toString().getBytes(StandardCharsets.UTF_8));
    }
}
