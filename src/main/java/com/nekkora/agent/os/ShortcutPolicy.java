package com.nekkora.agent.os;

import com.nekkora.agent.core.ShortcutState;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Validação dos destinos dos atalhos (decisão D-09). Pura: não abre nada.
 *
 * <p>Platform: somente {@code http}/{@code https} com servidor, sem usuário/senha, sem parâmetros
 * ({@code ?…}) e sem fragmento ({@code #…}) — onde costumam ir tokens. Studio: caminho absoluto local
 * de um arquivo {@code .exe}; scripts ({@code .cmd}, {@code .bat}, {@code .ps1} …), interpretadores
 * ({@code cmd.exe}, {@code powershell.exe} …), caminhos de rede, aspas, curingas, variáveis
 * ({@code %…%}) e argumentos não são aceitos.
 */
public final class ShortcutPolicy {
    static final int MAX_URL = 2048;
    static final int MAX_PATH = 1024;
    /** Executáveis que interpretam comandos ou scripts: abrir um deles seria abrir um shell. */
    static final java.util.Set<String> INTERPRETERS = java.util.Set.of("cmd.exe", "powershell.exe", "powershell_ise.exe", "pwsh.exe",
            "wscript.exe", "cscript.exe", "mshta.exe", "rundll32.exe", "regsvr32.exe", "bash.exe", "wsl.exe", "conhost.exe",
            "java.exe", "javaw.exe", "python.exe", "pythonw.exe", "node.exe", "msiexec.exe", "explorer.exe");

    private ShortcutPolicy() {}

    /** Motivo de recusa, com texto para a interface. */
    public enum Reason {
        NOT_CONFIGURED("destino não configurado — defina em Configurações"),
        URL_TOO_LONG("endereço longo demais"),
        URL_SYNTAX("endereço malformado"),
        URL_SCHEME("use um endereço http:// ou https://"),
        URL_HOST("o endereço precisa indicar o servidor"),
        URL_CREDENTIALS("o endereço não pode conter usuário ou senha"),
        URL_PARAMETERS("o endereço não pode conter parâmetros (?…) nem fragmento (#…)"),
        PATH_TOO_LONG("caminho longo demais"),
        PATH_CHARACTERS("o caminho contém aspas, curingas, variáveis ou caracteres de controle"),
        PATH_NOT_ABSOLUTE("informe o caminho completo, por exemplo C:\\Programas\\Nekkora Studio\\Nekkora Studio.exe"),
        PATH_NETWORK("caminhos de rede não são aceitos"),
        PATH_NOT_EXE("somente arquivos .exe são aceitos (scripts e atalhos não)"),
        PATH_INTERPRETER("interpretadores de comando e de scripts não são aceitos"),
        PATH_MISSING("o arquivo não existe ou não é um arquivo comum");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /** Resultado da validação. */
    public sealed interface Check<T> permits Valid, Rejected {
        ShortcutState state();
    }

    public record Valid<T>(T target) implements Check<T> {
        @Override
        public ShortcutState state() {
            return ShortcutState.VALID;
        }
    }

    public record Rejected<T>(Reason reason) implements Check<T> {
        @Override
        public ShortcutState state() {
            return reason == Reason.NOT_CONFIGURED ? ShortcutState.NOT_CONFIGURED : ShortcutState.INVALID;
        }
    }

    public static Check<URI> platformUrl(String raw) {
        if (raw == null || raw.isBlank()) return new Rejected<>(Reason.NOT_CONFIGURED);
        String v = raw.strip();
        if (v.length() > MAX_URL) return new Rejected<>(Reason.URL_TOO_LONG);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) return new Rejected<>(Reason.URL_SYNTAX);
        }
        URI uri;
        try {
            uri = new URI(v);
        } catch (URISyntaxException e) {
            return new Rejected<>(Reason.URL_SYNTAX);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return new Rejected<>(Reason.URL_SCHEME);
        if (uri.getRawUserInfo() != null || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"))) {
            return new Rejected<>(Reason.URL_CREDENTIALS);
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || v.contains("?") || v.contains("#")) {
            return new Rejected<>(Reason.URL_PARAMETERS);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) return new Rejected<>(Reason.URL_HOST);
        return new Valid<>(uri);
    }

    /** Validação de sintaxe; a existência do arquivo é conferida por {@link #studioExecutable(String, boolean, FileProbe)}. */
    public static Check<Path> studioExecutableSyntax(String raw, boolean windows) {
        if (raw == null || raw.isBlank()) return new Rejected<>(Reason.NOT_CONFIGURED);
        String v = raw.strip();
        if (v.length() > MAX_PATH) return new Rejected<>(Reason.PATH_TOO_LONG);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isISOControl(c) || "\"<>|*?%".indexOf(c) >= 0) return new Rejected<>(Reason.PATH_CHARACTERS);
        }
        if (v.startsWith("\\\\") || v.startsWith("//")) return new Rejected<>(Reason.PATH_NETWORK);
        Path p;
        try {
            p = Path.of(v);
        } catch (InvalidPathException e) {
            return new Rejected<>(Reason.PATH_CHARACTERS);
        }
        if (!p.isAbsolute()) return new Rejected<>(Reason.PATH_NOT_ABSOLUTE);
        if (windows && (p.getRoot() == null || !p.getRoot().toString().matches("[A-Za-z]:\\\\"))) {
            return new Rejected<>(Reason.PATH_NETWORK);
        }
        String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (windows && !name.endsWith(".exe")) return new Rejected<>(Reason.PATH_NOT_EXE);
        if (INTERPRETERS.contains(name)) return new Rejected<>(Reason.PATH_INTERPRETER);
        return new Valid<>(p.normalize());
    }

    /** Consulta ao sistema de arquivos (substituível em testes). */
    @FunctionalInterface
    public interface FileProbe {
        boolean isRegularFile(Path p);

        FileProbe DEFAULT = p -> java.nio.file.Files.isRegularFile(p);
    }

    public static Check<Path> studioExecutable(String raw, boolean windows, FileProbe probe) {
        Check<Path> syntax = studioExecutableSyntax(raw, windows);
        if (syntax instanceof Valid<Path> ok && !probe.isRegularFile(ok.target())) return new Rejected<>(Reason.PATH_MISSING);
        return syntax;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
