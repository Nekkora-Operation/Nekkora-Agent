package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * O Python escolhido pela pessoa (o ambiente do projeto). Nada é instalado ou baixado: o Agent só confere, por uma sonda
 * com argumentos fixos ({@code python -I -B <sonda>}), a versão do Python e a do {@code nekkora_rpa}, se houver.
 */
public final class PythonRuntime {
    private PythonRuntime() {}

    /** Resultado da sonda (só versões). */
    public record Probe(Path python, String pythonVersion, Optional<String> framework) {}

    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(25);
    static final Set<String> NAMES = Set.of("python.exe", "python", "python3", "python3.12", "python3.13", "python3.14");
    /** Variáveis que o subprocesso herda; o resto (chaves, segredos, PYTHONPATH…) fica de fora. */
    public static final Set<String> ENV_KEEP = Set.of("SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "PATH", "PATHEXT", "TEMP", "TMP", "TMPDIR",
            "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "HOME", "LOCALAPPDATA", "APPDATA", "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)",
            "COMMONPROGRAMFILES", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "OS", "LANG", "LC_ALL", "DISPLAY", "XAUTHORITY",
            "PLAYWRIGHT_BROWSERS_PATH");
    static final String PROBE = String.join("\n",
            "import json, sys",
            "info = {'python': '%d.%d.%d' % sys.version_info[:3], 'nekkora_rpa': None}",
            "try:",
            "    import nekkora_rpa",
            "    info['nekkora_rpa'] = str(getattr(nekkora_rpa, '__version__', ''))",
            "except Exception:",
            "    pass",
            "print('NEKKORA-AGENT-PROBE ' + json.dumps(info))",
            "");

    /** Caminho aceitável: absoluto, arquivo comum, nome de interpretador Python. */
    public static Path validate(Path python) throws ExecRefused {
        try {
            if (python == null || !python.isAbsolute()) throw new ExecRefused(ErrorCode.EXEC_PYTHON_INVALID);
            Path p = python.normalize();
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || !NAMES.contains(String.valueOf(p.getFileName()).toLowerCase(Locale.ROOT))) {
                throw new ExecRefused(ErrorCode.EXEC_PYTHON_INVALID);
            }
            return p;
        } catch (RuntimeException e) {
            throw new ExecRefused(ErrorCode.EXEC_PYTHON_INVALID);
        }
    }

    /** Roda a sonda (fora da EDT). {@code workDir}: pasta do Agent onde a sonda é gravada. */
    public static Probe probe(Path python, Path workDir) throws ExecRefused {
        Path exe = validate(python);
        Process p = null;
        try {
            Files.createDirectories(workDir);
            Path script = workDir.resolve("sonda-python.py");
            Files.writeString(script, PROBE, StandardCharsets.UTF_8);
            Path output = workDir.resolve("sonda-python.saida");
            Files.deleteIfExists(output);
            // saída em arquivo: a espera com prazo não depende de o processo fechar o fluxo
            ProcessBuilder pb = new ProcessBuilder(List.of(exe.toString(), "-I", "-B", script.toString())).directory(workDir.toFile())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).redirectInput(ProcessBuilder.Redirect.PIPE);
            minimalEnvironment(pb.environment());
            p = pb.start();
            p.getOutputStream().close();
            if (!p.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) throw new ExecRefused(ErrorCode.EXEC_PYTHON_INVALID);
            String text;
            try (InputStream in = Files.newInputStream(output)) {
                text = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
            }
            Files.deleteIfExists(output);
            int at = text.lastIndexOf("NEKKORA-AGENT-PROBE ");
            if (p.exitValue() != 0 || at < 0) throw new ExecRefused(ErrorCode.EXEC_PYTHON_INVALID);
            String line = text.substring(at + "NEKKORA-AGENT-PROBE ".length()).lines().findFirst().orElse("");
            Map<String, Object> m = JsonReader.object(line);
            String version = JsonReader.text(m, "python");
            if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new ExecRefused(ErrorCode.EXEC_PYTHON_INVALID);
            String fw = JsonReader.text(m, "nekkora_rpa");
            return new Probe(exe, version, Optional.ofNullable(fw == null || fw.isBlank() ? null : fw));
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            throw new ExecRefused(ErrorCode.EXEC_PYTHON_INVALID);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecRefused(ErrorCode.EXEC_PYTHON_INVALID);
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    /** O Python serve para o pacote? (3.12+; para o Studio, o {@code nekkora_rpa} na versão exata do manifesto). */
    public static void requireCompatible(Probe probe, ExecutionPackage pkg) throws ExecRefused {
        String[] v = probe.pythonVersion().split("\\.");
        if (Integer.parseInt(v[0]) < 3 || Integer.parseInt(v[0]) == 3 && Integer.parseInt(v[1]) < 12) {
            throw new ExecRefused(ErrorCode.EXEC_PYTHON_INCOMPATIBLE);
        }
        Optional<String> need = pkg.requiredFramework();
        if (need.isPresent() && !need.equals(probe.framework())) throw new ExecRefused(ErrorCode.EXEC_PYTHON_INCOMPATIBLE);
    }

    /** Deixa só as variáveis da lista fechada (nada de chaves de API, segredos, PYTHONPATH…). */
    public static void minimalEnvironment(Map<String, String> env) {
        env.keySet().removeIf(k -> !ENV_KEEP.contains(k.toUpperCase(Locale.ROOT)));
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");
    }
}
