package com.nekkora.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.tokens.NekkoraThemes;
import com.nekkora.ui.brand.NekkoraLogo;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * A01: as bibliotecas visuais carregadas vêm do repositório isolado preparado a partir do commit
 * fixado — não de outra cópia SNAPSHOT do Maven local. Compara caminho e SHA-256 com o lock.
 */
class DesignSystemProvenanceTest {
    private static final Path BASE = Path.of(System.getProperty("agent.basedir", ".")).toAbsolutePath().normalize();

    @Test
    void loadedDesignSystemJarsMatchThePinnedPreparation() throws Exception {
        Properties pinned = load(BASE.resolve("design-system.properties"));
        Properties lock = load(BASE.resolve(".design-system").resolve("design-system.lock.properties"));
        assertEquals(pinned.getProperty("studio.commit"), lock.getProperty("studio.commit"), "lock preparado a partir do commit fixado");

        Path isolated = BASE.resolve(".design-system").resolve("m2").toRealPath();
        check(NekkoraLogo.class, "nekkora-swing-ui", lock, isolated);
        check(NekkoraThemes.class, "nekkora-design-tokens", lock, isolated);
    }

    @Test
    void buildInfoRecordsTheDesignSystemCommit() throws IOException {
        Properties build = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/com/nekkora/agent/agent-build.properties")) {
            build.load(in);
        }
        Properties pinned = load(BASE.resolve("design-system.properties"));
        assertEquals(pinned.getProperty("studio.commit"), build.getProperty("designSystemCommit"));
        assertEquals("3.7.2", build.getProperty("flatlafVersion"));
    }

    private static void check(Class<?> type, String artifact, Properties lock, Path isolated) throws Exception {
        Path jar = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        assertTrue(jar.startsWith(isolated), artifact + " carregado de fora do repositório isolado: " + jar);
        assertTrue(jar.getFileName().toString().startsWith(artifact + "-"), jar.toString());
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        assertEquals(lock.getProperty("sha256." + artifact), sha, artifact + " difere do construído pela preparação");
    }

    private static Properties load(Path p) throws IOException {
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            props.load(r);
        }
        return props;
    }
}
