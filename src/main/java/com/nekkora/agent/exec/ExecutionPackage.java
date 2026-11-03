package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pacote de automação local inspecionado: o que é, o que precisa e a sua IMPRESSÃO DIGITAL. Dois tipos:
 * <ul>
 *   <li>{@link Kind#STUDIO_PYTHON} — projeto Python no FORMATO do gerador do Studio ({@code manifest.json} 1.4.0+, layout {@code automation/}
 *       + {@code main.py}): cada arquivo listado precisa ter o hash do manifesto (texto com LF, como o gerador calcula);</li>
 *   <li>{@link Kind#LAB_GENERIC} — processo genérico de laboratório ({@code nekkora-agent-lab.json}): um script Python que
 *       não depende do {@code nekkora_rpa} e publica {@code agent-result.json}.</li>
 * </ul>
 * O descritor diz como o pacote foi gerado e o que exige; ele NÃO autoriza a execução (a aprovação é um ato da pessoa no
 * Agent, por impressão digital). Só os arquivos listados contam e só eles são copiados para a execução.
 * <p>
 * PROCEDÊNCIA: o formato não prova quem gerou o pacote (o manifesto não é assinado; qualquer um pode escrevê-lo à mão).
 * O tipo {@code STUDIO_PYTHON} nomeia o formato, nunca a origem; o campo {@code generator} do manifesto é guardado só
 * como DECLARAÇÃO do pacote ({@link #declaredGenerator()}) e a interface o mostra como não verificado.
 */
public record ExecutionPackage(Kind kind, Path dir, String name, Mode mode, Map<String, String> files, String fingerprint,
                               Map<String, String> options, String frameworkVersion, String entry, List<String> origins, String generator,
                               String pythonRequirement, List<String> dependencies, String packageVersion) {

    /**
     * AGENT-PYTHON-001: {@code EXTERNAL_PYTHON} = pacote Python externo ({@code nekkora-external.json}, regra
     * {@code nekkora-agent/external-python-fingerprint/1}); {@code EXTERNAL_MONITORED} = processo do cliente disparado pelo
     * agendador dele e supervisionado pelo lançador (sem pacote e sem impressão: só aparece no registro da execução).
     */
    public enum Kind { STUDIO_PYTHON, LAB_GENERIC, EXTERNAL_PYTHON, EXTERNAL_MONITORED }

    /** Como o processo recebe dados: execução única, lista → planilha, planilha baixada (tabular) ou laboratório. */
    public enum Mode { SINGLE, LIST, TABULAR, LAB }

    public static final String STUDIO_MANIFEST = "manifest.json";
    public static final String LAB_DESCRIPTOR = "nekkora-agent-lab.json";
    public static final String LAB_SCHEMA = "nekkora-agent/lab-package";
    /** AGENT-PYTHON-001: pacote Python externo (sem framework Nekkora) e a regra PRÓPRIA da impressão dele. */
    public static final String EXTERNAL_DESCRIPTOR = "nekkora-external.json";
    public static final String EXTERNAL_SCHEMA = "nekkora-agent/external-python-package";
    public static final String EXTERNAL_RULE = "nekkora-agent/external-python-fingerprint/1";
    public static final String STUDIO_RULE = "nekkora-agent/package-fingerprint/1";
    private static final Pattern EXTERNAL_PYTHON_REQ = Pattern.compile("(>=|==|~=)([0-9]{1,2})\\.([0-9]{1,2})(\\.\\*)?(,<([0-9]{1,2})\\.([0-9]{1,2}))?");
    private static final Pattern EXTERNAL_DEP = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]{0,79})(\\[[A-Za-z0-9,._-]{1,80}])?==([0-9][0-9A-Za-z.+!-]{0,40})");
    static final int MAX_FILES = 500;
    static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final Pattern OPTION = Pattern.compile("--[a-z][a-z-]{0,30}");
    private static final Pattern SHA = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}");
    private static final Pattern FRAMEWORK = Pattern.compile("nekkora-rpa(\\[excel])?==([0-9]+\\.[0-9]+\\.[0-9]+)");
    private static final Pattern ORIGINS = Pattern.compile("(?m)^allowed_origins\\s*=\\s*\\[(.*)]\\s*$");

    /** Gerador declarado exibível (ex.: {@code nekkora-studio-codegen/1.2.0}); fora disso não é mostrado. */
    private static final Pattern GENERATOR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/+-]{0,79}");

    public ExecutionPackage {
        files = java.util.Collections.unmodifiableMap(new TreeMap<>(files));
        options = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(options));
        origins = List.copyOf(origins);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    /** Pacote do Studio ou de laboratório (sem os campos do pacote externo). */
    public ExecutionPackage(Kind kind, Path dir, String name, Mode mode, Map<String, String> files, String fingerprint, Map<String, String> options,
                            String frameworkVersion, String entry, List<String> origins, String generator) {
        this(kind, dir, name, mode, files, fingerprint, options, frameworkVersion, entry, origins, generator, null, List.of(), null);
    }

    /** O descritor do tipo (o arquivo que entra na impressão junto com os listados). */
    public static String descriptorOf(Kind kind) {
        return switch (kind) {
            case STUDIO_PYTHON -> STUDIO_MANIFEST;
            case LAB_GENERIC -> LAB_DESCRIPTOR;
            case EXTERNAL_PYTHON -> EXTERNAL_DESCRIPTOR;
            case EXTERNAL_MONITORED -> throw new IllegalArgumentException("monitoramento não tem pacote");
        };
    }

    /** A regra da impressão do tipo: pacote externo tem a sua; Studio e laboratório, a /1. */
    public static String ruleOf(Kind kind) {
        return kind == Kind.EXTERNAL_PYTHON ? EXTERNAL_RULE : STUDIO_RULE;
    }

    /** As 12 primeiras posições da impressão digital, para a interface. */
    public String shortFingerprint() {
        return fingerprint.substring("sha256:".length(), "sha256:".length() + 12);
    }

    /** O pacote precisa de um arquivo de entrada (lista CSV/XLSX ou JSON)? */
    public boolean needsInput() {
        return options.containsKey("input");
    }

    /** Lê e confere a pasta. Qualquer problema → {@link ExecRefused} com o código do catálogo. */
    public static ExecutionPackage inspect(Path folder) throws ExecRefused {
        Path dir;
        try {
            dir = folder.toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_NOT_FOUND);
        }
        if (!Files.isDirectory(dir)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_NOT_FOUND);
        Path studio = dir.resolve(STUDIO_MANIFEST);
        Path lab = dir.resolve(LAB_DESCRIPTOR);
        Path external = dir.resolve(EXTERNAL_DESCRIPTOR);
        if (Files.isRegularFile(external, LinkOption.NOFOLLOW_LINKS)) {
            // um pacote não passa por dois tipos
            if (Files.exists(studio, LinkOption.NOFOLLOW_LINKS) || Files.exists(lab, LinkOption.NOFOLLOW_LINKS)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
            return external(dir, read(external));
        }
        if (Files.isRegularFile(lab, LinkOption.NOFOLLOW_LINKS)) return lab(dir, read(lab));
        if (Files.isRegularFile(studio, LinkOption.NOFOLLOW_LINKS)) return studio(dir, read(studio));
        throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNKNOWN);
    }

    private static Map<String, Object> read(Path descriptor) throws ExecRefused {
        try {
            if (Files.size(descriptor) > JsonReader.MAX_CHARS) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
            return JsonReader.object(Files.readString(descriptor, StandardCharsets.UTF_8));
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        }
    }

    private static ExecutionPackage studio(Path dir, Map<String, Object> m) throws ExecRefused {
        String version = JsonReader.text(m, "version");
        if (!"nekkora-studio/codegen-manifest".equals(JsonReader.text(m, "schema")) || version == null || !version.matches("1\\.[4-9]\\.[0-9]+")) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID); // antes da 1.4.0 não há contrato de linha de comando
        }
        Mode mode = switch (String.valueOf(JsonReader.text(m, "execution"))) {
            case "single" -> Mode.SINGLE;
            case "list" -> Mode.LIST;
            case "tabular" -> Mode.TABULAR;
            default -> throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        };
        Matcher fw = FRAMEWORK.matcher(String.valueOf(JsonReader.text(m, "framework")));
        if (!fw.matches()) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        Map<String, Object> cli = JsonReader.obj(m, "cli");
        Map<String, Object> declared = cli == null ? null : JsonReader.obj(cli, "options");
        if (declared == null || declared.isEmpty()) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        Map<String, String> options = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : declared.entrySet()) {
            // só as opções que o Agent sabe preencher; qualquer outra é recusada (nada de argumento adivinhado)
            if (!List.of("input", "spreadsheet", "output").contains(e.getKey()) || !(e.getValue() instanceof String o) || !OPTION.matcher(o).matches()) {
                throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
            }
            options.put(e.getKey(), o);
        }
        if (!options.containsKey("output") || mode == Mode.LIST && !(options.containsKey("input") && options.containsKey("spreadsheet"))) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        }
        List<?> steps = JsonReader.list(m, "steps");
        if (steps != null) {
            for (Object s : steps) {
                if (s instanceof Map<?, ?> st && Boolean.TRUE.equals(st.get("credential"))) throw new ExecRefused(ErrorCode.EXEC_SECRETS_UNSUPPORTED);
            }
        }
        Map<String, String> files = files(JsonReader.obj(m, "files"));
        if (!files.containsKey("main.py")) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        verify(dir, files);
        String name = dir.getFileName() == null ? "pacote" : dir.getFileName().toString();
        return new ExecutionPackage(Kind.STUDIO_PYTHON, dir, safeName(name), mode, files, fingerprint(Kind.STUDIO_PYTHON, dir, files, STUDIO_MANIFEST),
                options, fw.group(2), "main.py", origins(dir.resolve("config").resolve("execution.toml")), generator(m));
    }

    private static ExecutionPackage lab(Path dir, Map<String, Object> m) throws ExecRefused {
        if (!LAB_SCHEMA.equals(JsonReader.text(m, "schema")) || !"1.0.0".equals(JsonReader.text(m, "version"))) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        }
        String name = JsonReader.text(m, "name");
        String entry = JsonReader.text(m, "entry");
        if (name == null || !TOKEN.matcher(name).matches() || entry == null || !entry.matches("[a-z0-9_]{1,40}\\.py")) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        }
        Map<String, String> files = files(JsonReader.obj(m, "files"));
        if (!files.containsKey(entry)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        verify(dir, files);
        return new ExecutionPackage(Kind.LAB_GENERIC, dir, name, Mode.LAB, files, fingerprint(Kind.LAB_GENERIC, dir, files, LAB_DESCRIPTOR),
                Map.of("output", "--output"), null, entry, List.of(), generator(m));
    }

    /**
     * AGENT-PYTHON-001: pacote Python externo. Descritor 1.0.x com nome, versão do projeto, entrada relativa {@code .py} listada,
     * versão de Python, dependências com versão EXATA e os arquivos com hash. Ambiente virtual, credenciais e cache nunca
     * entram (recusa). O pacote não pede segredo nem framework: o processo roda com o Python preparado pela pessoa.
     */
    private static ExecutionPackage external(Path dir, Map<String, Object> m) throws ExecRefused {
        String version = JsonReader.text(m, "version");
        if (!EXTERNAL_SCHEMA.equals(JsonReader.text(m, "schema")) || version == null || !version.matches("1\\.0\\.[0-9]{1,3}")) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        }
        String name = JsonReader.text(m, "name");
        String pkgVersion = JsonReader.text(m, "packageVersion");
        String entry = JsonReader.text(m, "entry");
        String python = JsonReader.text(m, "python");
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}") || pkgVersion == null || !pkgVersion.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,6}")
                || python == null || !EXTERNAL_PYTHON_REQ.matcher(python).matches()) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        }
        List<?> deps = JsonReader.list(m, "dependencies");
        if (deps == null || deps.size() > 200) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        List<String> dependencies = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object d : deps) {
            Matcher dm = d instanceof String s ? EXTERNAL_DEP.matcher(s) : null;
            if (dm == null || !dm.matches() || !seen.add(normalized(dm.group(1)))) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
            dependencies.add((String) d);
        }
        Map<String, String> files = files(JsonReader.obj(m, "files"));
        if (files.containsKey(EXTERNAL_DESCRIPTOR) || files.containsKey(STUDIO_MANIFEST) || files.containsKey(LAB_DESCRIPTOR)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
        for (String rel : files.keySet()) if (forbidden(rel)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
        if (entry == null || !entry.endsWith(".py") || !entry.matches("[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}") || entry.contains("..") || entry.startsWith("/")) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE); // entrada fora da raiz
        }
        if (!files.containsKey(entry)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        verify(dir, files);
        return new ExecutionPackage(Kind.EXTERNAL_PYTHON, dir, name, Mode.LAB, files, fingerprint(Kind.EXTERNAL_PYTHON, dir, files, EXTERNAL_DESCRIPTOR), Map.of(),
                null, entry, List.of(), generator(m), python, dependencies, pkgVersion);
    }

    /** Nome de distribuição normalizado (PEP 503): comparação de dependências sem diferença de caixa, "_" ou ".". */
    public static String normalized(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replace('_', '-').replace('.', '-');
    }

    /** Nunca no pacote externo: ambiente virtual, cache, controle de versão, credenciais. */
    static boolean forbidden(String rel) {
        for (String seg : rel.split("/")) {
            String l = seg.toLowerCase(java.util.Locale.ROOT);
            if (List.of(".venv", "venv", "env", "__pycache__", ".git", ".hg", ".svn", ".idea", ".vscode", "node_modules").contains(l) || l.startsWith(".env")
                    || l.endsWith(".pem") || l.endsWith(".key") || l.endsWith(".pfx") || l.endsWith(".p12") || l.startsWith("id_rsa") || l.startsWith("id_ed25519")
                    || l.equals(".netrc") || l.equals(".pypirc") || l.endsWith(".pyc")) {
                return true;
            }
        }
        return false;
    }

    /** {@code python} do descritor ({@code >=3.10,<3.14}, {@code ==3.12.*}, {@code ~=3.11}) atende à versão {@code major.minor}? */
    public static boolean pythonSatisfies(String requirement, int major, int minor) {
        Matcher m = EXTERNAL_PYTHON_REQ.matcher(requirement == null ? "" : requirement);
        if (!m.matches()) return false;
        int rM = Integer.parseInt(m.group(2));
        int rm = Integer.parseInt(m.group(3));
        int cmp = Integer.compare(major * 100 + minor, rM * 100 + rm);
        boolean ok = switch (m.group(1)) {
            case ">=" -> cmp >= 0;
            case "==" -> cmp == 0;
            default -> major == rM && cmp >= 0; // ~=X.Y: mesma versão maior, menor >= Y
        };
        if (ok && m.group(5) != null) ok = major * 100 + minor < Integer.parseInt(m.group(6)) * 100 + Integer.parseInt(m.group(7));
        return ok;
    }

    private static Map<String, String> files(Map<String, Object> declared) throws ExecRefused {
        if (declared == null || declared.isEmpty()) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        if (declared.size() > MAX_FILES) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : declared.entrySet()) {
            String rel = e.getKey();
            if (!(e.getValue() instanceof String sha) || !SHA.matcher(sha).matches()) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
            // caminho relativo simples, com barras normais, sem subir de pasta nem ser absoluto
            if (!rel.matches("[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}") || rel.contains("..") || rel.contains("//") || rel.startsWith("/")) {
                throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
            }
            out.put(rel, sha);
        }
        return out;
    }

    /** Confere cada arquivo listado: dentro da pasta, arquivo comum (sem ligação), dentro dos limites e com o hash. */
    static void verify(Path dir, Map<String, String> files) throws ExecRefused {
        long total = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path f = dir.resolve(e.getKey()).normalize();
            if (!f.startsWith(dir)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
            try {
                if (Files.isSymbolicLink(f) || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || !f.toRealPath().startsWith(dir)) {
                    throw new ExecRefused(Files.exists(f, LinkOption.NOFOLLOW_LINKS) ? ErrorCode.EXEC_PACKAGE_UNSAFE : ErrorCode.EXEC_PACKAGE_CHANGED);
                }
                total += Files.size(f);
                if (total > MAX_BYTES) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
                if (!e.getValue().equals(hash(Files.readAllBytes(f)))) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_CHANGED);
            } catch (IOException | RuntimeException ex) {
                throw new ExecRefused(ErrorCode.EXEC_PACKAGE_CHANGED);
            }
        }
    }

    /**
     * Hash do arquivo como o gerador do Studio o calcula: sobre o TEXTO UTF-8 com LF (um checkout do Windows pode ter
     * gravado CRLF). Conteúdo que não é UTF-8 válido entra em bytes.
     */
    static String hash(byte[] data) {
        byte[] bytes = data;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data)).toString();
            bytes = text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            // binário: os bytes como estão
        }
        return "sha256:" + HexFormat.of().formatHex(sha256(bytes));
    }

    /** Impressão digital: tipo + descritor + cada arquivo e hash (ordem fixa). Qualquer arquivo mudado a muda. */
    static String fingerprint(Kind kind, Path dir, Map<String, String> files, String descriptor) throws ExecRefused {
        // a primeira linha é a regra: o pacote externo tem a sua (external-python-fingerprint/1); a /1 do Studio não muda
        StringBuilder b = new StringBuilder(ruleOf(kind)).append('\n').append(kind).append('\n');
        try {
            b.append(descriptor).append('\t').append(hash(Files.readAllBytes(dir.resolve(descriptor)))).append('\n');
        } catch (IOException e) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        }
        new TreeMap<>(files).forEach((k, v) -> b.append(k).append('\t').append(v).append('\n'));
        return "sha256:" + HexFormat.of().formatHex(sha256(b.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Origens declaradas no {@code config/execution.toml} do projeto (só para mostrar na aprovação). */
    static List<String> origins(Path toml) {
        List<String> out = new ArrayList<>();
        try {
            if (!Files.isRegularFile(toml, LinkOption.NOFOLLOW_LINKS)) return out;
            Matcher m = ORIGINS.matcher(Files.readString(toml, StandardCharsets.UTF_8));
            if (!m.find()) return out;
            for (String part : m.group(1).split(",")) {
                String o = part.strip().replaceAll("^\"|\"$", "");
                if (o.matches("https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?")) out.add(o);
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return out;
    }

    private static String safeName(String folder) {
        String n = folder.replaceAll("[^A-Za-z0-9 ._-]", "_");
        n = n.isEmpty() || !Character.isLetterOrDigit(n.charAt(0)) ? "pacote" : n;
        return n.length() > 80 ? n.substring(0, 80) : n;
    }

    /** O {@code generator} que o descritor DECLARA (não verificado); vazio se ausente ou fora do formato exibível. */
    public Optional<String> declaredGenerator() {
        return Optional.ofNullable(generator);
    }

    private static String generator(Map<String, Object> m) {
        String g = JsonReader.text(m, "generator");
        return g != null && GENERATOR.matcher(g).matches() ? g : null;
    }

    /** Versão exata do {@code nekkora-rpa} que o pacote exige (Studio); vazio no pacote de laboratório. */
    public Optional<String> requiredFramework() {
        return Optional.ofNullable(frameworkVersion);
    }
}
