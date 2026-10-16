package com.nekkora.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fronteiras: estado/aplicação, persistência e diagnóstico não dependem de Swing, AWT, FlatLaf nem
 * do design system; a integração com o SO não depende da apresentação.
 */
class ArchitectureTest {
    private static final Path SRC = Path.of(System.getProperty("agent.basedir", "."), "src", "main", "java", "com", "nekkora", "agent");

    @Test
    void layersRespectTheirBoundaries() throws IOException {
        List<String> violations = new ArrayList<>();
        check("core", List.of("java.awt", "javax.swing", "com.nekkora.ui", "com.nekkora.tokens", "com.formdev",
                "com.nekkora.agent.storage", "com.nekkora.agent.diagnostics", "com.nekkora.agent.os", "com.nekkora.agent.ui", "com.nekkora.agent.app"), violations);
        check("storage", List.of("java.awt", "javax.swing", "com.nekkora.ui", "com.nekkora.tokens", "com.formdev",
                "com.nekkora.agent.os", "com.nekkora.agent.ui", "com.nekkora.agent.app", "com.nekkora.agent.diagnostics"), violations);
        check("diagnostics", List.of("java.awt", "javax.swing", "com.nekkora.ui", "com.nekkora.tokens", "com.formdev",
                "com.nekkora.agent.os", "com.nekkora.agent.ui", "com.nekkora.agent.app"), violations);
        check("app", List.of("java.awt", "javax.swing", "com.nekkora.ui", "com.nekkora.tokens", "com.formdev", "com.nekkora.agent.ui"), violations);
        check("os", List.of("javax.swing", "com.nekkora.ui", "com.nekkora.tokens", "com.formdev", "com.nekkora.agent.ui",
                "com.nekkora.agent.app", "com.nekkora.agent.storage", "com.nekkora.agent.diagnostics"), violations);
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    void noNetworkOrShellUsageInProductionCode() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (String banned : List.of("java.net.http", "HttpURLConnection", "java.net.Socket", "URL(", "openConnection",
                        "Runtime.getRuntime().exec", "cmd.exe /c", "\"/c\"", "powershell -", "InetAddress")) {
                    if (text.contains(banned)) violations.add(SRC.relativize(f) + " usa " + banned);
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    private static void check(String pkg, List<String> banned, List<String> violations) throws IOException {
        try (Stream<Path> files = Files.walk(SRC.resolve(pkg))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (!line.startsWith("import ")) continue;
                    for (String b : banned) {
                        if (line.startsWith("import " + b + ".") || line.startsWith("import static " + b + ".")) {
                            violations.add(pkg + "/" + f.getFileName() + ": " + line);
                        }
                    }
                }
            }
        }
    }
}
