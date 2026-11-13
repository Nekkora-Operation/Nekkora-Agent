package com.nekkora.agent.pack;

import com.nekkora.agent.exec.ExecRefused;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.pack.ImportProblem.Code;
import com.nekkora.agent.pack.ImportedProject.Kind;
import com.nekkora.agent.pack.ImportedProject.ProjectFile;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * AGENT-PACOTES-001: gera o pacote Python EXTERNO ({@code nekkora-external.json}, {@code nekkora-agent/external-python-package}
 * 1.0.0) a partir do ZIP importado. O descritor tem o mesmo texto que o empacotador Python gera (mesma ordem de campos,
 * mesma indentação) e a impressão é calculada pela MESMA função do validador ({@link ExecutionPackage#externalFingerprint}).
 * <p>
 * Gravação: arquivo temporário {@code .…parcial} na pasta escolhida → conferência do arquivo gravado pelo validador real
 * ({@link SafeZip} + {@link ExecutionPackage#inspect}, impressão igual à calculada) → troca de nome. Só depois da troca o
 * pacote existe com o nome final; uma falha (disco cheio, arquivo em uso, cancelamento, queda) deixa no máximo o temporário,
 * que nunca tem o nome de um pacote pronto. O ZIP de origem não é tocado; um arquivo existente só é substituído com
 * {@code overwrite} (consentimento da pessoa).
 */
public final class Packager {
    private Packager() {}

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
    static final Pattern SEMVER = Pattern.compile("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,6}");
    /** Regra da identidade de conteúdo LOCAL (ver {@link #contentIdentity}); nunca enviada à Platform. */
    public static final String CONTENT_RULE = "nekkora-agent/external-python-content/1";

    /** Arquivo fora do pacote e o motivo. */
    public record Excluded(String path, InclusionRules.Rule rule) {}

    /**
     * O pacote que SERIA gerado (nada gravado): descritor, arquivos, impressão e identidade de conteúdo.
     *
     * @param descriptor bytes do {@code nekkora-external.json}
     * @param files caminho → hash dos arquivos incluídos (ordem do descritor)
     * @param fingerprint impressão {@code sha256:…} pela regra {@code external-python-fingerprint/1}
     * @param contentIdentity identidade de conteúdo local (sem nome, versão declarada e gerador)
     * @param excluded arquivos fora, com o motivo
     * @param warned arquivos incluídos cujo nome sugere credencial
     * @param bytes total do conteúdo incluído
     */
    public record Preview(byte[] descriptor, Map<String, String> files, String fingerprint, String contentIdentity, List<Excluded> excluded,
                          List<String> warned, long bytes, PackagePlan plan) {}

    /** O pacote gravado e conferido. {@code sha256}: os bytes do ZIP (medida do artefato, nunca comparada com a impressão). */
    public record Built(Path zip, String fingerprint, String sha256, String contentIdentity, String name, String version, String entry, int files,
                        long size) {}

    /** Abre o fluxo do temporário (ponto de teste: disco cheio, arquivo em uso). */
    @FunctionalInterface
    interface Opener {
        OutputStream open(Path tmp) throws IOException;

        Opener FILES = tmp -> Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    static Opener opener = Opener.FILES;
    /** Ponto de teste: chamado depois da conferência, antes da troca de nome. */
    static Runnable beforeMove = () -> {};

    public static Preview preview(ImportedProject p, PackagePlan plan) throws ImportProblem {
        return preview(p, plan, PackagePlan.GENERATOR);
    }

    /** {@code generator}: declaração do descritor (os testes de equivalência usam a do empacotador Python). */
    static Preview preview(ImportedProject p, PackagePlan plan, String generator) throws ImportProblem {
        switch (p.kind()) {
            case CONFLICT -> throw new ImportProblem(Code.DESCRIPTOR_CONFLICT);
            case EXTERNAL_INVALID -> throw first(p, Code.EXTERNAL_INVALID);
            case STUDIO_INVALID -> throw first(p, Code.STUDIO_INVALID);
            case STUDIO_PACKAGE, LAB_PACKAGE -> throw new ImportProblem(Code.NOT_CONVERTIBLE);
            case EXTERNAL_PACKAGE -> {
                if (!plan.rebuild()) throw new ImportProblem(Code.ALREADY_PACKAGE);
            }
            case CODE -> {
                if (!p.blockers().isEmpty()) throw p.blockers().get(0);
            }
        }
        if (plan.name() == null || !NAME.matcher(plan.name()).matches()) throw new ImportProblem(Code.NAME_INVALID);
        if (plan.version() == null || !SEMVER.matcher(plan.version()).matches()) throw new ImportProblem(Code.VERSION_INVALID);
        if (!ExecutionPackage.validPythonRequirement(plan.python())) throw new ImportProblem(Code.PYTHON_INVALID);
        Set<String> seen = new HashSet<>();
        for (String d : plan.dependencies()) {
            if (!ExecutionPackage.validDependency(d) || !seen.add(ExecutionPackage.normalized(d.replaceAll("\\[.*", "").split("==", 2)[0]))) {
                throw new ImportProblem(Code.DEPENDENCY_INVALID, ZipNames.display(d));
            }
            if (seen.size() > 200) throw new ImportProblem(Code.DEPENDENCY_INVALID);
        }
        boolean sameAsExisting = p.kind() == Kind.EXTERNAL_PACKAGE && p.existing().dependencies().equals(plan.dependencies());
        if (p.declarations().dependenciesNeedConfirmation() && !plan.dependenciesConfirmed() && !sameAsExisting) {
            throw new ImportProblem(Code.DEPENDENCIES_UNCONFIRMED);
        }
        Map<String, String> files = new TreeMap<>();
        List<Excluded> excluded = new ArrayList<>();
        List<String> warned = new ArrayList<>();
        List<String> outside = new ArrayList<>();
        long bytes = 0;
        for (ProjectFile f : p.files()) {
            InclusionRules.Rule r = f.rule();
            boolean in;
            switch (r) {
                case DESCRIPTOR -> {
                    continue;
                }
                case ENVIRONMENT, SECRET, TOOLING -> {
                    if (plan.includeData().contains(f.path())) throw new ImportProblem(Code.FORBIDDEN_INCLUDED, ZipNames.display(f.path()));
                    in = false;
                }
                case DATA -> in = plan.includeData().contains(f.path());
                default -> in = !plan.exclude().contains(f.path());
            }
            if (!in) {
                excluded.add(new Excluded(f.path(), plan.exclude().contains(f.path()) ? InclusionRules.Rule.INCLUDE : r));
                continue;
            }
            if (!f.contractSafe()) {
                outside.add(f.path());
                continue;
            }
            if (!f.path().contains("/") && (f.path().equals(ExecutionPackage.STUDIO_MANIFEST) || f.path().equals(ExecutionPackage.LAB_DESCRIPTOR))) {
                throw new ImportProblem(Code.RESERVED_ROOT_NAME);
            }
            if (r == InclusionRules.Rule.INCLUDE_WARNED) warned.add(f.path());
            files.put(f.path(), f.hash());
            bytes += f.size();
        }
        if (!outside.isEmpty()) {
            throw new ImportProblem(Code.NAME_OUTSIDE_CONTRACT, ZipNames.display(outside.get(0)) + (outside.size() > 1 ? " e mais " + (outside.size() - 1) : ""));
        }
        String entry = plan.entry();
        if (entry == null || !entry.endsWith(".py") || !p.pythonFiles().contains(entry) || !files.containsKey(entry)) {
            throw new ImportProblem(Code.ENTRY_INVALID);
        }
        if (files.size() > ZipLimits.PACKAGE_FILES || bytes > ZipLimits.PACKAGE_BYTES) throw new ImportProblem(Code.PACKAGE_LIMIT);
        byte[] descriptor = descriptor(plan, files, generator);
        return new Preview(descriptor, files, ExecutionPackage.externalFingerprint(descriptor, files), contentIdentity(plan.entry(), plan.python(),
                plan.dependencies(), files), excluded, warned, bytes, plan);
    }

    private static ImportProblem first(ImportedProject p, Code fallback) {
        return p.blockers().isEmpty() ? new ImportProblem(fallback) : p.blockers().get(0);
    }

    /**
     * Identidade de conteúdo LOCAL: o que o pacote executa e exige, SEM nome, versão declarada e gerador. A impressão inclui o
     * descritor (e, nele, a versão), então dois pacotes com o mesmo código e versões diferentes têm impressões diferentes;
     * comparar pela impressão seria circular. Esta identidade responde só "o conteúdo é o mesmo?" (recompactar o ZIP ou mudar
     * a data não a muda). Nunca autoriza nada: aprovação e autorização continuam pela impressão.
     */
    public static String contentIdentity(String entry, String python, List<String> dependencies, Map<String, String> files) {
        StringBuilder b = new StringBuilder(CONTENT_RULE).append('\n');
        b.append("entry\t").append(entry).append('\n').append("python\t").append(python).append('\n');
        List<String> deps = new ArrayList<>();
        for (String d : dependencies) {
            String[] nv = d.split("==", 2);
            String extras = nv[0].contains("[") ? nv[0].substring(nv[0].indexOf('[')) : "";
            deps.add(ExecutionPackage.normalized(nv[0].replaceAll("\\[.*", "")) + extras.toLowerCase(java.util.Locale.ROOT) + "==" + nv[1]);
        }
        deps.stream().sorted().forEach(d -> b.append("dependency\t").append(d).append('\n'));
        new TreeMap<>(files).forEach((k, v) -> b.append("file\t").append(k).append('\t').append(v).append('\n'));
        return "sha256:" + HexFormat.of().formatHex(ProjectImporter.sha256().digest(b.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** Mesmo texto do {@code json.dumps(..., ensure_ascii=False, indent=2) + "\n"} do empacotador Python. */
    static byte[] descriptor(PackagePlan plan, Map<String, String> files, String generator) {
        StringBuilder b = new StringBuilder("{\n");
        field(b, "schema", ExecutionPackage.EXTERNAL_SCHEMA);
        field(b, "version", "1.0.0");
        field(b, "name", plan.name());
        field(b, "packageVersion", plan.version());
        field(b, "entry", plan.entry());
        field(b, "python", plan.python());
        if (plan.dependencies().isEmpty()) {
            b.append("  \"dependencies\": [],\n");
        } else {
            b.append("  \"dependencies\": [\n");
            for (int i = 0; i < plan.dependencies().size(); i++) {
                b.append("    ").append(quote(plan.dependencies().get(i))).append(i + 1 < plan.dependencies().size() ? ",\n" : "\n");
            }
            b.append("  ],\n");
        }
        b.append("  \"files\": {\n");
        int i = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            b.append("    ").append(quote(e.getKey())).append(": ").append(quote(e.getValue())).append(++i < files.size() ? ",\n" : "\n");
        }
        b.append("  },\n");
        b.append("  \"generator\": ").append(quote(generator)).append("\n}\n");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void field(StringBuilder b, String k, String v) {
        b.append("  ").append(quote(k)).append(": ").append(quote(v)).append(",\n");
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    // ------------------------------------------------------------------ gravação

    /** Nome do arquivo do pacote: {@code <nome>-<versão>.zip}. */
    public static String fileName(PackagePlan plan) {
        return plan.name() + "-" + plan.version() + ".zip";
    }

    /** O destino já existe (a interface pede consentimento antes de substituir). */
    public static boolean exists(Path outDir, PackagePlan plan) {
        return Files.exists(outDir.resolve(fileName(plan)), LinkOption.NOFOLLOW_LINKS);
    }

    public static Built build(ImportedProject p, PackagePlan plan, Path outDir, boolean overwrite, SafeZip.Cancel cancel) throws ImportProblem {
        Preview pv = preview(p, plan);
        return write(p, pv, outDir, overwrite, cancel);
    }

    static Built write(ImportedProject p, Preview pv, Path outDir, boolean overwrite, SafeZip.Cancel cancel) throws ImportProblem {
        if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
        if (!ProjectImporter.sourceUnchanged(p)) throw new ImportProblem(Code.SOURCE_CHANGED);
        if (outDir == null || !Files.isDirectory(outDir)) throw new ImportProblem(Code.WRITE_FAILED);
        Path target = outDir.resolve(fileName(pv.plan())).toAbsolutePath().normalize();
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(target, p.source())) throw new ImportProblem(Code.SAME_AS_SOURCE);
        } catch (IOException e) {
            throw Io.target(e, outDir);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !overwrite) throw new ImportProblem(Code.TARGET_EXISTS, fileName(pv.plan()));
        Path tmp;
        try {
            tmp = Files.createTempFile(outDir, "." + fileName(pv.plan()) + ".", ".parcial");
        } catch (IOException e) {
            throw Io.target(e, outDir);
        }
        boolean moved = false;
        try {
            zip(p, pv, tmp, cancel);
            if (Files.size(tmp) > ZipLimits.DEFAULT.zipBytes()) throw new ImportProblem(Code.PACKAGE_LIMIT);
            verify(p, pv, tmp);
            beforeMove.run();
            if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
            if (!ProjectImporter.sourceUnchanged(p)) throw new ImportProblem(Code.SOURCE_CHANGED);
            try {
                if (overwrite) Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                else Files.move(tmp, target); // sem substituir: se alguém criou o arquivo agora, falha
            } catch (FileAlreadyExistsException e) {
                throw new ImportProblem(Code.TARGET_EXISTS, fileName(pv.plan()));
            }
            moved = true;
            String sha = sha256(target);
            return new Built(target, pv.fingerprint(), sha, pv.contentIdentity(), pv.plan().name(), pv.plan().version(), pv.plan().entry(), pv.files().size(),
                    Files.size(target));
        } catch (IOException e) {
            throw Io.target(e, outDir);
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // o temporário tem nome de temporário: nunca passa por pacote pronto
                }
            }
        }
    }

    /** ZIP determinístico: descritor primeiro, depois os arquivos em ordem; data fixa; Deflate nível 9. */
    private static void zip(ImportedProject p, Preview pv, Path tmp, SafeZip.Cancel cancel) throws IOException, ImportProblem {
        long fixed = LocalDateTime.of(1980, 1, 1, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try (OutputStream raw = opener.open(tmp); ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(raw), StandardCharsets.UTF_8)) {
            z.setLevel(Deflater.BEST_COMPRESSION);
            put(z, ExecutionPackage.EXTERNAL_DESCRIPTOR, pv.descriptor(), fixed);
            for (Map.Entry<String, String> e : pv.files().entrySet()) {
                if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
                Path f = p.root().resolve(e.getKey()).normalize();
                if (!f.startsWith(p.root())) throw new ImportProblem(Code.VERIFY_FAILED);
                byte[] data = Files.readAllBytes(f);
                // os bytes empacotados são os conferidos na importação (a pasta da operação não muda entre uma coisa e outra)
                if (!e.getValue().equals(ExecutionPackage.fileHash(data))) throw new ImportProblem(Code.VERIFY_FAILED);
                put(z, e.getKey(), data, fixed);
            }
        }
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    private static void put(ZipOutputStream z, String name, byte[] data, long time) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(time);
        e.setMethod(ZipEntry.DEFLATED);
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    /** O arquivo gravado, lido de novo pelo leitor seguro e conferido pelo validador do Agent: mesma impressão, mesma entrada. */
    private static void verify(ImportedProject p, Preview pv, Path tmp) throws ImportProblem {
        Path dir = null;
        try {
            dir = Files.createTempDirectory(p.workDir(), "verificacao-");
            SafeZip.extract(tmp, dir, ZipLimits.DEFAULT, SafeZip.Cancel.NEVER);
            ExecutionPackage pk = ExecutionPackage.inspect(dir);
            if (pk.kind() != ExecutionPackage.Kind.EXTERNAL_PYTHON || !pk.fingerprint().equals(pv.fingerprint()) || !pk.entry().equals(pv.plan().entry())
                    || !pv.plan().version().equals(pk.packageVersion())) {
                throw new ImportProblem(Code.VERIFY_FAILED);
            }
        } catch (ExecRefused e) {
            throw new ImportProblem(Code.VERIFY_FAILED, e.code().code());
        } catch (ImportProblem e) {
            throw e.code() == Code.NO_SPACE ? e : new ImportProblem(Code.VERIFY_FAILED, e.code().code());
        } catch (IOException e) {
            throw Io.work(e, p.workDir());
        } finally {
            Io.deleteTree(dir);
        }
    }

    static String sha256(Path f) throws IOException {
        var md = ProjectImporter.sha256();
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
