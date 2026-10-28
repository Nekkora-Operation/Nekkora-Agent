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
 * <p>
 * AGENT-PLATFORM-001: o transporte para a Platform ({@code com.nekkora.agent.platform}) é o ÚNICO lugar com rede, e só
 * pelo cliente HTTPS do JDK ({@code java.net.http}). Ele não chama o executor (nem processo) e nada abaixo dele (executor,
 * observabilidade, armazenamento, diagnóstico, núcleo, SO) depende dele.
 */
class ArchitectureTest {
    private static final Path SRC = Path.of(System.getProperty("agent.basedir", "."), "src", "main", "java", "com", "nekkora", "agent");

    @Test
    void layersRespectTheirBoundaries() throws IOException {
        List<String> violations = new ArrayList<>();
        check("core", List.of("java.awt", "javax.swing", "com.nekkora.ui", "com.nekkora.tokens", "com.formdev",
                "com.nekkora.agent.storage", "com.nekkora.agent.diagnostics", "com.nekkora.agent.os", "com.nekkora.agent.ui", "com.nekkora.agent.app",
                "com.nekkora.agent.platform"), violations);
        check("storage", List.of("java.awt", "javax.swing", "com.nekkora.ui", "com.nekkora.tokens", "com.formdev",
                "com.nekkora.agent.os", "com.nekkora.agent.ui", "com.nekkora.agent.app", "com.nekkora.agent.diagnostics", "com.nekkora.agent.platform"), violations);
        check("diagnostics", List.of("java.awt", "javax.swing", "com.nekkora.ui", "com.nekkora.tokens", "com.formdev",
                "com.nekkora.agent.os", "com.nekkora.agent.ui", "com.nekkora.agent.app", "com.nekkora.agent.platform"), violations);
        check("app", List.of("java.awt", "javax.swing", "com.nekkora.ui", "com.nekkora.tokens", "com.formdev", "com.nekkora.agent.ui"), violations);
        check("os", List.of("javax.swing", "com.nekkora.ui", "com.nekkora.tokens", "com.formdev", "com.nekkora.agent.ui",
                "com.nekkora.agent.app", "com.nekkora.agent.storage", "com.nekkora.agent.diagnostics", "com.nekkora.agent.platform"), violations);
        // OBS-001: a observabilidade é domínio (sem Swing nem interface); o supervisor não depende dela
        check("obs", List.of("java.awt", "javax.swing", "com.nekkora.ui", "com.nekkora.tokens", "com.formdev", "com.nekkora.agent.ui",
                "com.nekkora.agent.app", "com.nekkora.agent.os", "com.nekkora.agent.diagnostics", "com.nekkora.agent.platform"), violations);
        // (exec consulta GraphicsEnvironment.isHeadless para saber se há sessão gráfica: java.awt fica de fora da regra)
        check("exec", List.of("javax.swing", "com.nekkora.ui", "com.nekkora.tokens", "com.formdev", "com.nekkora.agent.ui",
                "com.nekkora.agent.app", "com.nekkora.agent.obs", "com.nekkora.agent.platform"), violations);
        // AGENT-PLATFORM-001: o transporte é domínio sem tela, sem SO e sem diagnóstico (este é ligado de fora, pelo app)
        check("platform", List.of("java.awt", "javax.swing", "com.nekkora.ui", "com.nekkora.tokens", "com.formdev", "com.nekkora.agent.ui",
                "com.nekkora.agent.app", "com.nekkora.agent.os", "com.nekkora.agent.diagnostics"), violations);
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /** O que nenhum código de produção usa; {@code java.net.http} só é aceito no transporte ({@link #networkViolations}). */
    static final List<String> BANNED = List.of("java.net.http", "HttpURLConnection", "java.net.Socket", "URL(", "openConnection",
            "Runtime.getRuntime().exec", "cmd.exe /c", "\"/c\"", "powershell -", "InetAddress");
    /** O transporte não lança processo, não toca no executor e não usa a biblioteca nativa fora da guarda de segredos. */
    static final List<String> BANNED_IN_TRANSPORT = List.of("ProcessBuilder", "ProcessHandle", "ExecutionController", ".prune(", "runs.save(", "Runtime.getRuntime");

    static final String TRANSPORT = "platform";

    static List<String> networkViolations(String relativePath, String text) {
        List<String> out = new ArrayList<>();
        boolean transport = relativePath.replace('\\', '/').startsWith(TRANSPORT + "/");
        for (String banned : BANNED) {
            if (transport && banned.equals("java.net.http")) continue; // o ÚNICO lugar com o cliente HTTPS
            if (text.contains(banned)) out.add(relativePath + " usa " + banned);
        }
        if (transport) for (String b : BANNED_IN_TRANSPORT) if (text.contains(b)) out.add(relativePath + " (transporte) usa " + b);
        if (!transport && text.contains("com.sun.jna")) out.add(relativePath + " usa a biblioteca nativa (só a guarda de segredos do transporte)");
        return out;
    }

    @Test
    void noNetworkOrShellUsageInProductionCode() throws IOException {
        List<String> violations = new ArrayList<>();
        int transportFiles = 0;
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = SRC.relativize(f).toString();
                if (rel.replace('\\', '/').startsWith(TRANSPORT + "/")) transportFiles++;
                violations.addAll(networkViolations(rel, Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        assertTrue(transportFiles > 5, "o guarda leu o transporte (" + transportFiles + " arquivos)");
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    void networkIsRefusedOutsideTheTransport() {
        // contraprova: a MESMA linha que o transporte usa é recusada em qualquer outro pacote (inclusive no executor)
        String client = "import java.net.http.HttpClient;\nclass X { java.net.http.HttpClient c; }";
        for (String pkg : List.of("exec/ExecutionController.java", "obs/ActivityService.java", "app/AgentBootstrap.java", "ui/AgentApp.java",
                "core/X.java", "storage/X.java", "diagnostics/X.java", "os/X.java", "local/X.java", "platformx/X.java")) {
            assertTrue(networkViolations(pkg, client).stream().anyMatch(v -> v.contains("java.net.http")), "rede recusada em " + pkg);
        }
        assertTrue(networkViolations("platform/JdkHttpTransport.java", client).isEmpty(), "aceita só no transporte");
        // e o transporte continua sem socket bruto, sem processo e sem o executor
        assertTrue(networkViolations("platform/X.java", "new java.net.Socket(h, 443)").stream().anyMatch(v -> v.contains("java.net.Socket")));
        assertTrue(networkViolations("platform/X.java", "new ProcessBuilder(cmd).start()").stream().anyMatch(v -> v.contains("ProcessBuilder")));
        assertTrue(networkViolations("platform/X.java", "controller.start(req); ExecutionController c;").stream().anyMatch(v -> v.contains("ExecutionController")));
        assertTrue(networkViolations("exec/X.java", "import com.sun.jna.Native;").stream().anyMatch(v -> v.contains("biblioteca nativa")));
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
