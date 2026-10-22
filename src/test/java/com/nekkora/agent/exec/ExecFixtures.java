package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Pacotes de teste (laboratório e Studio sintético), Python dos testes e uma sessão controlável. */
final class ExecFixtures {
    private ExecFixtures() {}

    static final Path LAB_SCRIPTS = Path.of("tools", "lab", "scripts").toAbsolutePath().normalize();

    /**
     * Python para os testes que executam processos de laboratório: {@code NEKKORA_TEST_PYTHON} ou a venv do repositório
     * irmão {@code Nekkora-RPA-Framework}. Sem ele os testes são PULADOS (nunca dados por aprovados).
     */
    static Path python() {
        String env = System.getenv("NEKKORA_TEST_PYTHON");
        if (env != null && !env.isBlank() && Files.isRegularFile(Path.of(env))) return Path.of(env).toAbsolutePath();
        Path repo = Path.of("..", "Nekkora-RPA-Framework").toAbsolutePath().normalize();
        for (Path p : List.of(repo.resolve(".venv/Scripts/python.exe"), repo.resolve(".venv/bin/python"))) {
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /** Pacote de laboratório com o script {@code entry} (e o módulo comum), descritor com os hashes. */
    static Path labPackage(Path dir, String name, String entry) throws IOException {
        Files.createDirectories(dir);
        Map<String, String> files = new LinkedHashMap<>();
        for (String f : List.of(entry, "lab_comum.py")) {
            Files.copy(LAB_SCRIPTS.resolve(f), dir.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            files.put(f, ExecutionPackage.hash(Files.readAllBytes(dir.resolve(f))));
        }
        StringBuilder b = new StringBuilder("{\n  \"schema\": \"nekkora-agent/lab-package\",\n  \"version\": \"1.0.0\",\n  \"name\": \"").append(name)
                .append("\",\n  \"entry\": \"").append(entry).append("\",\n  \"files\": {");
        int n = 0;
        for (Map.Entry<String, String> e : files.entrySet()) b.append(n++ == 0 ? "\n" : ",\n").append("    \"").append(e.getKey()).append("\": \"").append(e.getValue()).append('"');
        b.append("\n  }\n}\n");
        Files.writeString(dir.resolve(ExecutionPackage.LAB_DESCRIPTOR), b, StandardCharsets.UTF_8);
        return dir;
    }

    /** Projeto do Studio sintético (só o formato: main.py fictício), com o manifesto dado por {@code extra}. */
    static Path studioPackage(Path dir, String execution, String options, String extra) throws IOException {
        Files.createDirectories(dir.resolve("automation"));
        Map<String, String> content = new LinkedHashMap<>();
        content.put("main.py", "print('main')\n");
        content.put("automation/__init__.py", "\"\"\"pacote\"\"\"\n");
        content.put("automation/process.py", "X = 1\n");
        content.put("config/execution.toml", "[runtime]\nenvironment = \"benchmark\"\nallowed_origins = [\"https://exemplo.invalid\"]\n");
        StringBuilder files = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, String> e : content.entrySet()) {
            Path f = dir.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
            files.append(n++ == 0 ? "" : ", ").append('"').append(e.getKey()).append("\": \"").append(ExecutionPackage.hash(e.getValue().getBytes(StandardCharsets.UTF_8)))
                    .append('"');
        }
        String manifest = "{\"schema\": \"nekkora-studio/codegen-manifest\", \"version\": \"1.5.0\", \"framework\": \"nekkora-rpa[excel]==0.3.0\", "
                + "\"execution\": \"" + execution + "\", \"cli\": {\"options\": {" + options + "}}, " + extra + "\"files\": {" + files + "}}";
        Files.writeString(dir.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);
        return dir;
    }

    /** Sessão controlável pelo teste. */
    static final class FakeSession implements SessionProbe {
        final AtomicBoolean locked = new AtomicBoolean();
        final AtomicReference<ErrorCode> problem = new AtomicReference<>();

        @Override
        public Session probe() {
            ErrorCode p = problem.get();
            if (p != null) return new Session(false, 0, false, p);
            boolean l = locked.get();
            return new Session(true, 1, l, l ? ErrorCode.EXEC_SESSION_LOCKED : null);
        }
    }
}
