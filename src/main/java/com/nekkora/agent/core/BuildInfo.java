package com.nekkora.agent.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Identificação do build, gravada pelo Maven em {@code agent-build.properties}. Valores ausentes
 * (por exemplo, construção fora de um repositório Git) aparecem como {@link #UNKNOWN}, nunca inventados.
 */
public record BuildInfo(
        String version,
        String commit,
        String commitShort,
        String dirty,
        String commitTime,
        String designSystemCommit,
        String designSystemVersion,
        String flatlafVersion) {

    public static final String UNKNOWN = "desconhecido";
    private static final String RESOURCE = "/com/nekkora/agent/agent-build.properties";

    public static BuildInfo load() {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            // segue com valores desconhecidos
        }
        return from(p);
    }

    public static BuildInfo from(Properties p) {
        return new BuildInfo(
                value(p, "version"),
                value(p, "commit"),
                value(p, "commitShort"),
                value(p, "dirty"),
                value(p, "commitTime"),
                value(p, "designSystemCommit"),
                value(p, "designSystemVersion"),
                value(p, "flatlafVersion"));
    }

    /** Rótulo curto do build para a interface: commit curto e marca de alterações locais. */
    public String buildLabel() {
        if (UNKNOWN.equals(commitShort)) return "build sem identificação de commit";
        return "true".equals(dirty) ? commitShort + " (com alterações locais)" : commitShort;
    }

    private static String value(Properties p, String key) {
        String v = p.getProperty(key);
        // placeholder não substituído (${...}) ou vazio: desconhecido
        if (v == null || v.isBlank() || v.contains("${")) return UNKNOWN;
        return SafeText.token(v.trim(), 80);
    }
}
