package com.nekkora.agent.pack;

import com.nekkora.agent.exec.ExecRefused;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.JsonReader;
import com.nekkora.agent.pack.ImportProblem.Code;
import com.nekkora.agent.pack.ImportedProject.Kind;
import com.nekkora.agent.pack.ImportedProject.ProjectFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * AGENT-PACOTES-001: abre um ZIP de projeto escolhido pela pessoa.
 * <ol>
 *   <li>copia o ZIP para a pasta PRIVADA da operação ({@code <dados>/importacao/op-…}), calculando o SHA-256 dos bytes
 *       copiados, e confere que a origem não mudou durante a cópia — daí em diante só a cópia é lida (os mesmos bytes
 *       conferidos são os empacotados);</li>
 *   <li>lê e extrai a cópia ({@link SafeZip}: limites, nomes, tipos, colisões);</li>
 *   <li>reconhece a raiz (uma pasta externa única vira a raiz, mostrada à pessoa) e o formato, SÓ pelos descritores da raiz
 *       (nenhuma busca recursiva escolhe manifesto); pacote existente é conferido pelo validador do Agent
 *       ({@link ExecutionPackage#inspect}).</li>
 * </ol>
 * Nada é executado: nenhum Python, {@code setup.py}, gancho ou import. Cancelar ou falhar apaga só a pasta da operação.
 */
public final class ProjectImporter {
    /** Pastas de operação mais velhas que isto, deixadas por um encerramento abrupto, são apagadas na abertura seguinte. */
    static final Duration STALE = Duration.ofHours(12);

    private final Path workRoot;
    private final ZipLimits limits;

    /** {@code workRoot}: pasta do Agent para as operações de importação (ex.: {@code <dados>/importacao}). */
    public ProjectImporter(Path workRoot, ZipLimits limits) {
        this.workRoot = workRoot;
        this.limits = limits;
    }

    public static Path defaultRoot(Path agentDataRoot) {
        return agentDataRoot.resolve("importacao");
    }

    /** Ponto de teste: chamado entre a cópia e a conferência final da origem (simula a origem mudando). */
    Runnable afterCopy = () -> {};

    public ImportedProject open(Path zip, SafeZip.Cancel cancel) throws ImportProblem {
        Path op = newOperation();
        try {
            ImportedProject p = openIn(zip, op, cancel);
            if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
            return p;
        } catch (ImportProblem | RuntimeException e) {
            Io.deleteTree(op);
            throw e;
        }
    }

    /** Fecha a operação: apaga a pasta privada (o ZIP original e as saídas não são tocados). */
    public void close(ImportedProject p) {
        if (p != null && p.workDir().startsWith(workRoot)) Io.deleteTree(p.workDir());
    }

    /** A origem continua como quando foi copiada (tamanho e data)? Senão a importação precisa ser refeita. */
    public static boolean sourceUnchanged(ImportedProject p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p.source(), BasicFileAttributes.class);
            return a.size() == p.sourceSize() && a.lastModifiedTime().toInstant().equals(p.sourceModified());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private Path newOperation() throws ImportProblem {
        try {
            Files.createDirectories(workRoot);
            cleanStale();
            return Files.createTempDirectory(workRoot, "op-");
        } catch (IOException e) {
            throw Io.work(e, workRoot);
        }
    }

    /** Sobras de operações interrompidas (só as pastas {@code op-*} desta raiz, antigas). */
    void cleanStale() {
        try (var s = Files.list(workRoot)) {
            Instant limit = Instant.now().minus(STALE);
            for (Path p : s.toList()) {
                String n = String.valueOf(p.getFileName());
                if (n.startsWith("op-") && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(limit)) {
                    Io.deleteTree(p);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // melhor esforço
        }
    }

    private ImportedProject openIn(Path zip, Path op, SafeZip.Cancel cancel) throws ImportProblem {
        Path copy = op.resolve("origem.zip");
        BasicFileAttributes before;
        String sha;
        try {
            if (!Files.isRegularFile(zip)) throw new ImportProblem(Code.SOURCE_NOT_FOUND);
            before = Files.readAttributes(zip, BasicFileAttributes.class);
            if (before.size() == 0) throw new ImportProblem(Code.EMPTY_FILE);
            if (before.size() > limits.zipBytes()) throw new ImportProblem(Code.ZIP_TOO_LARGE);
            sha = copy(zip, copy, cancel);
            afterCopy.run();
            BasicFileAttributes after = Files.readAttributes(zip, BasicFileAttributes.class);
            if (after.size() != before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime()) || Files.size(copy) != before.size()) {
                throw new ImportProblem(Code.SOURCE_CHANGED);
            }
        } catch (java.nio.file.NoSuchFileException e) {
            throw new ImportProblem(Code.SOURCE_NOT_FOUND);
        } catch (FileSystemException e) {
            throw locked(e) ? new ImportProblem(Code.SOURCE_LOCKED) : new ImportProblem(Code.SOURCE_NOT_FOUND);
        } catch (IOException e) {
            // trava de trecho do Windows (outro programa com o arquivo travado): IOException comum, com o motivo no texto
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            throw m.contains("lock") || m.contains("another process") || m.contains("outro processo") ? new ImportProblem(Code.SOURCE_LOCKED)
                    : new ImportProblem(Code.SOURCE_NOT_FOUND);
        }
        Path extracted = op.resolve("fonte");
        try {
            Files.createDirectories(extracted);
        } catch (IOException e) {
            throw Io.work(e, op);
        }
        SafeZip.Content content = SafeZip.extract(copy, extracted, limits, cancel);
        return scan(zip, before, sha, op, extracted, content, cancel);
    }

    private static boolean locked(FileSystemException e) {
        String r = String.valueOf(e.getReason()).toLowerCase(Locale.ROOT);
        return e instanceof java.nio.file.AccessDeniedException || r.contains("another process") || r.contains("outro processo") || r.contains("locked");
    }

    private String copy(Path from, Path to, SafeZip.Cancel cancel) throws IOException, ImportProblem {
        MessageDigest md = sha256();
        long total = 0;
        try (InputStream in = Files.newInputStream(from); OutputStream out = Files.newOutputStream(to, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
                total += n;
                if (total > limits.zipBytes()) throw new ImportProblem(Code.ZIP_TOO_LARGE);
                md.update(buf, 0, n);
                try {
                    out.write(buf, 0, n);
                } catch (IOException e) {
                    throw Io.work(e, to.getParent());
                }
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    // ------------------------------------------------------------------ reconhecimento

    private ImportedProject scan(Path zip, BasicFileAttributes attrs, String sha, Path op, Path extracted, SafeZip.Content content, SafeZip.Cancel cancel)
            throws ImportProblem {
        String prefix = rootPrefix(content);
        Path root = prefix.isEmpty() ? extracted : extracted.resolve(prefix);
        List<ProjectFile> files = new ArrayList<>();
        Map<String, byte[]> rootSmall = new HashMap<>();
        List<String> paths = new ArrayList<>();
        for (SafeZip.Item it : content.files()) {
            if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
            String rel = prefix.isEmpty() ? it.name() : it.name().substring(prefix.length() + 1);
            byte[] data;
            try {
                data = Files.readAllBytes(it.file());
            } catch (IOException e) {
                throw Io.work(e, op);
            }
            paths.add(rel);
            if (!rel.contains("/") && data.length <= 1024 * 1024) rootSmall.put(rel, data);
            files.add(new ProjectFile(rel, it.size(), ExecutionPackage.fileHash(data), InclusionRules.of(rel), ZipNames.contractSafe(rel)));
        }
        files.sort(java.util.Comparator.comparing(ProjectFile::path));
        List<String> notes = new ArrayList<>();
        List<ImportProblem> blockers = new ArrayList<>();
        boolean ext = rootSmall.containsKey(ExecutionPackage.EXTERNAL_DESCRIPTOR) || paths.contains(ExecutionPackage.EXTERNAL_DESCRIPTOR);
        boolean manifest = paths.contains(ExecutionPackage.STUDIO_MANIFEST);
        boolean lab = paths.contains(ExecutionPackage.LAB_DESCRIPTOR);
        for (String p : paths) {
            String name = p.substring(p.lastIndexOf('/') + 1);
            if (p.contains("/") && List.of(ExecutionPackage.EXTERNAL_DESCRIPTOR, ExecutionPackage.STUDIO_MANIFEST, ExecutionPackage.LAB_DESCRIPTOR).contains(name)) {
                notes.add("Há um " + name + " em subpasta (" + ZipNames.display(p) + "). Ele não é usado para reconhecer o pacote: só a raiz conta.");
            }
        }
        Kind kind;
        ExecutionPackage existing = null;
        ExecRefused refusal = null;
        if (ext && (manifest || lab)) {
            kind = Kind.CONFLICT;
            blockers.add(new ImportProblem(Code.DESCRIPTOR_CONFLICT));
        } else if (ext) {
            try {
                existing = ExecutionPackage.inspect(root);
                kind = Kind.EXTERNAL_PACKAGE;
            } catch (ExecRefused e) {
                kind = Kind.EXTERNAL_INVALID;
                refusal = e;
                blockers.add(new ImportProblem(Code.EXTERNAL_INVALID, e.code().description() + " " + e.code().code()));
            }
        } else if (manifest && schema(rootSmall.get(ExecutionPackage.STUDIO_MANIFEST), "nekkora-studio/codegen-manifest")
                || lab && schema(rootSmall.get(ExecutionPackage.LAB_DESCRIPTOR), ExecutionPackage.LAB_SCHEMA)) {
            try {
                existing = ExecutionPackage.inspect(root);
                kind = existing.kind() == ExecutionPackage.Kind.LAB_GENERIC ? Kind.LAB_PACKAGE : Kind.STUDIO_PACKAGE;
            } catch (ExecRefused e) {
                kind = Kind.STUDIO_INVALID;
                refusal = e;
                blockers.add(new ImportProblem(Code.STUDIO_INVALID, e.code().description() + " " + e.code().code()));
            }
        } else {
            kind = Kind.CODE;
            if (manifest || lab) {
                // um manifest.json qualquer (de outra ferramenta) na raiz: o formato externo reserva o nome
                notes.add(Code.RESERVED_ROOT_NAME.text());
            }
        }
        if (existing != null && kind.readyPackage()) {
            ExecutionPackage ex = existing;
            int ignored = (int) files.stream().filter(f -> !ex.files().containsKey(f.path()) && !f.path().equals(ExecutionPackage.descriptorOf(ex.kind()))).count();
            if (ignored > 0) notes.add(ignored + " arquivo(s) do ZIP não estão listados no descritor e ficam fora da execução.");
        }
        if (kind == Kind.EXTERNAL_PACKAGE || kind == Kind.EXTERNAL_INVALID) {
            files.replaceAll(f -> f.path().equals(ExecutionPackage.EXTERNAL_DESCRIPTOR)
                    ? new ProjectFile(f.path(), f.size(), f.hash(), InclusionRules.Rule.DESCRIPTOR, f.contractSafe()) : f);
        }
        List<String> py = new ArrayList<>();
        for (ProjectFile f : files) if (f.path().endsWith(".py") && !f.rule().equals(InclusionRules.Rule.ENVIRONMENT)) py.add(f.path());
        if (kind == Kind.CODE && py.isEmpty()) blockers.add(new ImportProblem(Code.NO_PYTHON_FILES));
        Optional<String> suggested = py.contains("main.py") ? Optional.of("main.py") : py.contains("__main__.py") ? Optional.of("__main__.py")
                : py.stream().filter(p -> !p.contains("/")).count() == 1 ? py.stream().filter(p -> !p.contains("/")).findFirst() : Optional.empty();
        Declarations decl = Declarations.read(rootSmall, paths);
        return new ImportedProject(zip, attrs.size(), attrs.lastModifiedTime().toInstant(), sha, op, root, prefix, kind, existing, refusal, files, py, suggested,
                decl, blockers, notes);
    }

    /** Uma pasta externa única (sem arquivo solto na raiz do ZIP) é a raiz do projeto; só um nível, como na Platform. */
    static String rootPrefix(SafeZip.Content c) {
        String first = null;
        for (SafeZip.Item it : c.files()) {
            int slash = it.name().indexOf('/');
            if (slash <= 0) return "";
            String seg = it.name().substring(0, slash);
            if (first == null) first = seg;
            else if (!first.equals(seg)) return "";
        }
        for (String d : c.dirs()) {
            String seg = d.contains("/") ? d.substring(0, d.indexOf('/')) : d;
            if (!seg.equals(first)) return "";
        }
        return first == null ? "" : first;
    }

    private static boolean schema(byte[] json, String expected) {
        if (json == null) return false;
        try {
            return expected.equals(JsonReader.text(JsonReader.object(new String(json, StandardCharsets.UTF_8)), "schema"));
        } catch (JsonReader.Invalid | RuntimeException e) {
            return false;
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Uso em teste: o mapa ordenado dos hashes incluídos. */
    static Map<String, String> hashes(List<ProjectFile> files) {
        Map<String, String> m = new TreeMap<>();
        for (ProjectFile f : files) m.put(f.path(), f.hash());
        return m;
    }
}
