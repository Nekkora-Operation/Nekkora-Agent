package com.nekkora.agent.storage;

import com.nekkora.agent.core.ErrorCode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Leitura e gravação da configuração ({@code config/agent-config.json}).
 *
 * <p>Regras: arquivo ausente ⇒ padrões (nada é gravado até o primeiro "Salvar"); arquivo
 * malformado, de versão não suportada ou ilegível ⇒ padrões só nesta sessão, o arquivo é preservado
 * e a gravação fica bloqueada até uma ação explícita ({@link #preserveAndSave}), que move o arquivo
 * incompatível para uma cópia antes de criar o novo. Gravação falha ⇒ nada é anunciado como salvo e
 * a última configuração válida continua em uso.
 */
public final class ConfigStore {

    /** Situação do arquivo. */
    public enum State {
        MISSING("AUSENTE", null),
        LOADED("CARREGADA", null),
        CORRUPTED("MALFORMADA", ErrorCode.CONFIG_CORRUPTED),
        UNSUPPORTED_VERSION("VERSAO_NAO_SUPORTADA", ErrorCode.CONFIG_UNSUPPORTED),
        UNREADABLE("ILEGIVEL", ErrorCode.CONFIG_UNREADABLE);

        private final String diagnosticValue;
        private final ErrorCode code;

        State(String diagnosticValue, ErrorCode code) {
            this.diagnosticValue = diagnosticValue;
            this.code = code;
        }

        public String diagnosticValue() {
            return diagnosticValue;
        }

        /** Código do problema, ou {@code null}. */
        public ErrorCode code() {
            return code;
        }

        /** O arquivo existente não é compatível: gravar exige preservá-lo antes. */
        public boolean blocksPlainSave() {
            return code != null;
        }
    }

    /** Resultado da leitura: a configuração efetiva desta sessão e a situação do arquivo. */
    public record Load(State state, AgentConfig config) {}

    /** Resultado de uma gravação. {@code preservedAs} é o nome (sem diretório) da cópia, quando houver. */
    public sealed interface SaveResult permits Saved, SaveFailed {}

    public record Saved(AgentConfig config, String preservedAs) implements SaveResult {}

    /** Falha; {@code preservedAs} indica a cópia já criada do arquivo incompatível, quando houver. */
    public record SaveFailed(ErrorCode code, String exceptionType, String preservedAs) implements SaveResult {}

    static final int MAX_BYTES = 64 * 1024;
    private static final Set<String> ROOT_KEYS = Set.of("format", "version", "theme", "shortcuts");
    private static final Set<String> SHORTCUT_KEYS = Set.of("platformUrl", "studioExecutable");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Path file;
    private final AtomicWriter writer;
    private final Clock clock;
    private State state = State.MISSING;
    private AgentConfig current = AgentConfig.defaults();

    public ConfigStore(Path file, AtomicWriter writer, Clock clock) {
        this.file = Objects.requireNonNull(file);
        this.writer = Objects.requireNonNull(writer);
        this.clock = Objects.requireNonNull(clock);
    }

    public Path file() {
        return file;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized AgentConfig current() {
        return current;
    }

    public synchronized Load load() {
        byte[] bytes;
        try {
            if (Files.isDirectory(file)) throw new IOException("destino é um diretório");
            long size = Files.size(file);
            if (size > MAX_BYTES) return apply(State.CORRUPTED, AgentConfig.defaults());
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return apply(State.MISSING, AgentConfig.defaults());
        } catch (IOException | SecurityException e) {
            return apply(State.UNREADABLE, AgentConfig.defaults());
        }
        try {
            return apply(State.LOADED, decode(bytes));
        } catch (UnsupportedVersion e) {
            return apply(State.UNSUPPORTED_VERSION, AgentConfig.defaults());
        } catch (Malformed e) {
            return apply(State.CORRUPTED, AgentConfig.defaults());
        }
    }

    private Load apply(State s, AgentConfig c) {
        state = s;
        current = c;
        return new Load(s, c);
    }

    /** Grava; recusa (sem tocar no disco) se o arquivo existente for incompatível. */
    public synchronized SaveResult save(AgentConfig config) {
        if (state.blocksPlainSave()) return new SaveFailed(state.code(), "ArquivoIncompativel", null);
        return write(config, null);
    }

    /**
     * Ação explícita para arquivo incompatível: move o arquivo atual para
     * {@code agent-config.json.incompativel-<data>} e grava a nova configuração.
     */
    public synchronized SaveResult preserveAndSave(AgentConfig config) {
        if (!state.blocksPlainSave()) return write(config, null);
        String preserved;
        try {
            preserved = preserveCurrentFile();
        } catch (IOException | SecurityException e) {
            return new SaveFailed(ErrorCode.CONFIG_PRESERVE_FAILED, e.getClass().getSimpleName(), null);
        }
        // a cópia existe; a partir daqui o destino está livre
        state = State.MISSING;
        return write(config, preserved);
    }

    private String preserveCurrentFile() throws IOException {
        if (Files.notExists(file)) return null;
        String base = file.getFileName() + ".incompativel-" + STAMP.format(clock.instant());
        for (int n = 1; n <= 50; n++) {
            Path target = file.resolveSibling(n == 1 ? base : base + "-" + n);
            try {
                Files.move(file, target);
                return target.getFileName().toString();
            } catch (FileAlreadyExistsException e) {
                // tenta o próximo nome
            }
        }
        throw new FileAlreadyExistsException("cópias demais");
    }

    private SaveResult write(AgentConfig config, String preservedAs) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            writer.write(file, encode(config).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | SecurityException e) {
            return new SaveFailed(ErrorCode.CONFIG_WRITE_FAILED, e.getClass().getSimpleName(), preservedAs);
        }
        state = State.LOADED;
        current = config;
        return new Saved(config, preservedAs);
    }

    // ----- formato -----

    static String encode(AgentConfig c) {
        return "{\n"
                + "  \"format\": " + StrictJson.quote(AgentConfig.FORMAT) + ",\n"
                + "  \"version\": " + AgentConfig.VERSION + ",\n"
                + "  \"theme\": " + StrictJson.quote(c.themeId()) + ",\n"
                + "  \"shortcuts\": {\n"
                + "    \"platformUrl\": " + jsonOrNull(c.platformUrl()) + ",\n"
                + "    \"studioExecutable\": " + jsonOrNull(c.studioExecutable()) + "\n"
                + "  }\n"
                + "}\n";
    }

    private static String jsonOrNull(String v) {
        return v == null ? "null" : StrictJson.quote(v);
    }

    static final class Malformed extends Exception {
        private static final long serialVersionUID = 1L;
    }

    static final class UnsupportedVersion extends Exception {
        private static final long serialVersionUID = 1L;
    }

    static AgentConfig decode(byte[] bytes) throws Malformed, UnsupportedVersion {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new Malformed();
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        Map<String, Object> root;
        try {
            root = StrictJson.parseObject(text);
        } catch (StrictJson.SyntaxException e) {
            throw new Malformed();
        }
        if (!AgentConfig.FORMAT.equals(root.get("format"))) throw new Malformed();
        if (!(root.get("version") instanceof Long v) || v < 1) throw new Malformed();
        if (v != AgentConfig.VERSION) throw new UnsupportedVersion();
        if (!ROOT_KEYS.containsAll(root.keySet())) throw new Malformed();
        if (!(root.get("theme") instanceof String theme) || !theme.matches("[a-z0-9-]{1,40}")) throw new Malformed();
        String platform = null;
        String studio = null;
        Object sc = root.get("shortcuts");
        if (sc != null && sc != StrictJson.NULL) {
            if (!(sc instanceof Map<?, ?> m)) throw new Malformed();
            if (!SHORTCUT_KEYS.containsAll(m.keySet())) throw new Malformed();
            platform = optionalString(m.get("platformUrl"));
            studio = optionalString(m.get("studioExecutable"));
        }
        return new AgentConfig(theme, platform, studio);
    }

    private static String optionalString(Object v) throws Malformed {
        if (v == null || v == StrictJson.NULL) return null;
        if (v instanceof String s && s.length() <= 4096) return s;
        throw new Malformed();
    }
}
