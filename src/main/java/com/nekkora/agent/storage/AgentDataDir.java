package com.nekkora.agent.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Diretório de dados do Agent, próprio do usuário e separado do Studio e da extensão.
 *
 * <p>Ordem: propriedade {@code nekkora.agent.dataDir}, variável {@code NEKKORA_AGENT_DATA_DIR}
 * (testes e homologação isolados), {@code %LOCALAPPDATA%\Nekkora\Agent} no Windows,
 * {@code $XDG_DATA_HOME/nekkora-agent} ou {@code ~/.local/share/nekkora-agent} nos demais.
 *
 * <p>{@link #open} cria o diretório e o resolve para o caminho canônico ({@code toRealPath}): a
 * instância única vale por usuário e por diretório canônico.
 */
public final class AgentDataDir {

    /** De onde veio o diretório (vai para o diagnóstico; o caminho em si não). */
    public enum Kind {
        DEFAULT("PADRAO"),
        OVERRIDE("SUBSTITUIDO");

        private final String diagnosticValue;

        Kind(String diagnosticValue) {
            this.diagnosticValue = diagnosticValue;
        }

        public String diagnosticValue() {
            return diagnosticValue;
        }
    }

    /** Local escolhido, ainda não criado. */
    public record Location(Path path, Kind kind) {}

    public static final String PROPERTY = "nekkora.agent.dataDir";
    public static final String ENV = "NEKKORA_AGENT_DATA_DIR";

    private final Path root;
    private final Kind kind;

    private AgentDataDir(Path root, Kind kind) {
        this.root = root;
        this.kind = kind;
    }

    public static Location locate(String property, Map<String, String> env, String osName, String userHome) {
        String explicit = property;
        if (explicit == null || explicit.isBlank()) explicit = env.get(ENV);
        if (explicit != null && !explicit.isBlank()) return new Location(Path.of(explicit.strip()).toAbsolutePath().normalize(), Kind.OVERRIDE);
        if (osName != null && osName.toLowerCase(Locale.ROOT).contains("win")) {
            String local = env.get("LOCALAPPDATA");
            Path base = local != null && !local.isBlank() ? Path.of(local) : Path.of(userHome, "AppData", "Local");
            return new Location(base.resolve("Nekkora").resolve("Agent"), Kind.DEFAULT);
        }
        String xdg = env.get("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) return new Location(Path.of(xdg, "nekkora-agent"), Kind.DEFAULT);
        return new Location(Path.of(userHome, ".local", "share", "nekkora-agent"), Kind.DEFAULT);
    }

    /** Local da instalação corrente (propriedades do sistema e ambiente reais). */
    public static Location locateCurrent() {
        return locate(System.getProperty(PROPERTY), System.getenv(), System.getProperty("os.name"), System.getProperty("user.home"));
    }

    /** Cria (se preciso) e resolve o caminho canônico. */
    public static AgentDataDir open(Location location) throws IOException {
        Objects.requireNonNull(location);
        Files.createDirectories(location.path());
        Path real = location.path().toRealPath();
        Files.createDirectories(real.resolve("config"));
        Files.createDirectories(real.resolve("logs"));
        return new AgentDataDir(real, location.kind());
    }

    public Path root() {
        return root;
    }

    public Kind kind() {
        return kind;
    }

    public Path lockFile() {
        return root.resolve("agent.lock");
    }

    public Path configFile() {
        return root.resolve("config").resolve("agent-config.json");
    }

    public Path logsDir() {
        return root.resolve("logs");
    }

    /**
     * Descrição para a interface sem o caminho pessoal: o padrão aparece com a variável do Windows;
     * um diretório substituído aparece apenas como tal.
     */
    public String displayLocation() {
        return kind == Kind.DEFAULT ? "%LOCALAPPDATA%\\Nekkora\\Agent" : "diretório definido em " + ENV;
    }

    /** Local dos logs para a interface, no mesmo formato de {@link #displayLocation()}. */
    public String displayLogsLocation() {
        return kind == Kind.DEFAULT ? displayLocation() + "\\logs" : "pasta logs do " + displayLocation();
    }
}
