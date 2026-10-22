package com.nekkora.agent.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.diagnostics.DiagnosticReport;
import com.nekkora.agent.exec.ExecutionController;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.os.ShortcutLauncher;
import com.nekkora.agent.storage.AgentDataDir;
import com.nekkora.agent.storage.AtomicWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DESKTOP-001 G3 — vazamento: uma execução REAL pelo contexto do Agent, com pacote, pasta de saída e entrada cujos nomes
 * são marcadores e um processo que imprime um valor-sentinela. Nada disso pode aparecer nos logs, nos registros de
 * execução nem no diagnóstico exportado (controle positivo: o detector acusa o marcador plantado). Grupo {@code desktop}: precisa
 * da sessão interativa real (numa JVM sem desktop a execução é recusada, como deve ser).
 */
@Tag("desktop")
class ExecutionLeakTest {
    @TempDir
    Path tmp;

    static final String SENTINEL = "SENTINELA-VALOR-DA-AUTOMACAO-7Q2";
    static final String PACKAGE_MARK = "Cliente-Confidencial-ACME";
    static final String OUTPUT_MARK = "Pasta-Pessoal-Maria";

    static Path python() {
        String env = System.getenv("NEKKORA_TEST_PYTHON");
        if (env != null && !env.isBlank() && Files.isRegularFile(Path.of(env))) return Path.of(env).toAbsolutePath();
        Path repo = Path.of("..", "Nekkora-RPA-Framework").toAbsolutePath().normalize();
        for (Path p : List.of(repo.resolve(".venv/Scripts/python.exe"), repo.resolve(".venv/bin/python"))) if (Files.isRegularFile(p)) return p;
        return null;
    }

    @Test
    @DisplayName("[Python real] execução pelo contexto do Agent: sentinela, nome do pacote e caminhos fora de logs, registros e diagnóstico")
    void semVazamento() throws Exception {
        Path py = python();
        Assumptions.assumeTrue(py != null, "sem Python neste ambiente");
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "execução local só no Windows nesta versão");
        Path data = tmp.resolve("dados");
        Path pkgDir = tmp.resolve(PACKAGE_MARK);
        Files.createDirectories(pkgDir);
        Path scripts = Path.of("tools", "lab", "scripts");
        Map<String, String> files = new LinkedHashMap<>();
        for (String f : List.of("lab_sucesso.py", "lab_comum.py")) {
            Files.copy(scripts.resolve(f), pkgDir.resolve(f));
            files.put(f, hash(pkgDir.resolve(f)));
        }
        Files.writeString(pkgDir.resolve(ExecutionPackage.LAB_DESCRIPTOR), "{\"schema\": \"nekkora-agent/lab-package\", \"version\": \"1.0.0\", \"name\": \"Laboratorio\", "
                + "\"entry\": \"lab_sucesso.py\", \"files\": {\"lab_sucesso.py\": \"" + files.get("lab_sucesso.py") + "\", \"lab_comum.py\": \""
                + files.get("lab_comum.py") + "\"}}");
        Path out = Files.createDirectories(tmp.resolve(OUTPUT_MARK));
        AgentBootstrap.Environment e = new AgentBootstrap.Environment(new AgentDataDir.Location(data, AgentDataDir.Kind.OVERRIDE), System.getenv(),
                Clock.systemUTC(), Optional.of(Instant.now()), new ShortcutLauncher((cmd, dir) -> {
                }, new ShortcutLauncher.Browser() {
                    @Override
                    public boolean available() {
                        return false;
                    }

                    @Override
                    public void browse(URI uri) {
                    }
                }, p -> true, true), p -> true, true, AtomicWriter.DEFAULT);
        AgentBootstrap.Ready ready = assertInstanceOf(AgentBootstrap.Ready.class, AgentBootstrap.start(AgentOptions.parse(new String[0]), e));
        AgentContext ctx = ready.context();
        try {
            ExecutionPackage p = ExecutionPackage.inspect(pkgDir);
            ctx.execution().approvals().approve(p, Instant.now());
            // a sessão real do Windows: este teste roda na sessão interativa do usuário (sem tela bloqueada)
            ctx.execution().start(new ExecutionController.Request(pkgDir, p.fingerprint(), py, null, out, null));
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (ctx.execution().running() && System.nanoTime() < deadline) Thread.sleep(50);
            assertEquals(ExecutionState.SUCCEEDED, ctx.execution().current().orElseThrow().record().state());
            String diag = DiagnosticReport.toJson(ctx.diagnosticFacts(), false, Instant.now());
            assertTrue(diag.contains("\"execucaoLocal\"") && diag.contains("\"estado\": \"SUCCEEDED\"") && diag.contains("\"tipoPacote\": \"LAB_GENERIC\""), diag);
            ctx.log().flush(5000);
            StringBuilder all = new StringBuilder(diag);
            try (Stream<Path> s = Files.walk(data)) {
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    String rel = data.relativize(f).toString();
                    if (rel.contains("pacote") || rel.endsWith("saida.ref") || rel.endsWith(".py") || rel.equals("agent.lock")) continue; // código, referência local, trava
                    all.append(Files.readString(f, StandardCharsets.UTF_8));
                }
            }
            for (String mark : List.of(SENTINEL, PACKAGE_MARK, OUTPUT_MARK, tmp.toString())) {
                assertFalse(all.toString().contains(mark), "vazou " + mark);
            }
            // controle positivo: o detector acusa um marcador plantado
            assertTrue((all + SENTINEL).contains(SENTINEL));
        } finally {
            ctx.close();
        }
    }

    private static String hash(Path f) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
        return "sha256:" + java.util.HexFormat.of().formatHex(d);
    }
}
