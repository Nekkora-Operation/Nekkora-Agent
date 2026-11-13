package com.nekkora.agent.pack;

import com.nekkora.agent.exec.ExecutionPackage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O que o projeto DECLARA sobre Python e dependências, lido como texto — nunca executando {@code setup.py}, ganchos de build
 * nem importando módulos. Declarar não é validar: o ambiente só é conferido na preparação da execução, com o Python
 * escolhido. Falta de declaração nunca vira "nenhuma dependência".
 *
 * @param requirementsFile o projeto tem {@code requirements.txt} na raiz
 * @param exact dependências com versão exata lidas do {@code requirements.txt}, na ordem
 * @param unsupported linhas do {@code requirements.txt} que o formato do pacote não aceita (faixas, opções, URLs…)
 * @param otherFiles outros arquivos de declaração que o Agent NÃO lê (pyproject, setup.py, Pipfile…)
 * @param python versão de Python declarada no formato do pacote, se houver
 * @param pythonSource de onde veio {@code python} (para a tela)
 * @param pythonUnsupported texto declarado que não cabe no formato do pacote, se houver
 */
public record Declarations(boolean requirementsFile, List<String> exact, List<String> unsupported, List<String> otherFiles, Optional<String> python,
                           String pythonSource, Optional<String> pythonUnsupported) {

    public Declarations {
        exact = List.copyOf(exact);
        unsupported = List.copyOf(unsupported);
        otherFiles = List.copyOf(otherFiles);
    }

    /** Situação das dependências. */
    public enum DependencyStatus {
        /** {@code requirements.txt} com todas as linhas exatas e nenhuma outra declaração: a lista vem dele. */
        DECLARED,
        /** Há linhas não aceitas pelo formato ou outras declarações: a pessoa confere e completa a lista. */
        PARTIAL,
        /** Nada declarado: a pessoa informa (ou confirma que não há dependência externa). */
        NOT_DECLARED
    }

    public DependencyStatus dependencyStatus() {
        if (requirementsFile && unsupported.isEmpty() && otherFiles.isEmpty()) return DependencyStatus.DECLARED;
        return requirementsFile || !otherFiles.isEmpty() ? DependencyStatus.PARTIAL : DependencyStatus.NOT_DECLARED;
    }

    /** A pessoa precisa confirmar a lista antes de gerar. */
    public boolean dependenciesNeedConfirmation() {
        return dependencyStatus() != DependencyStatus.DECLARED;
    }

    private static final List<String> OTHER = List.of("pyproject.toml", "setup.py", "setup.cfg", "Pipfile", "Pipfile.lock", "poetry.lock", "environment.yml",
            "environment.yaml", "conda.yml", "uv.lock");
    private static final Pattern REQUIRES_PYTHON = Pattern.compile("(?m)^\\s*requires-python\\s*=\\s*[\"']([^\"'\\r\\n]{1,60})[\"']\\s*$");
    /** pyproject só conta como declaração de dependências se declarar alguma (um pyproject só com requires-python não conta). */
    private static final Pattern DECLARES_DEPENDENCIES = Pattern.compile("(?m)^\\s*(dependencies|requires)\\s*=|^\\[tool\\.poetry\\.(dev-)?dependencies]|^\\[project\\.optional-dependencies]");
    private static final Pattern PY_VERSION = Pattern.compile("(?:python-)?([0-9]{1,2})\\.([0-9]{1,2})(?:\\.[0-9]{1,3})?");

    /** Lê as declarações da raiz. {@code files}: caminho relativo → bytes (só os da raiz e de {@code requirements/} são lidos). */
    public static Declarations read(Map<String, byte[]> rootFiles, List<String> allPaths) {
        boolean req = rootFiles.containsKey("requirements.txt");
        List<String> exact = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        if (req) {
            Set<String> seen = new HashSet<>();
            for (String raw : text(rootFiles.get("requirements.txt")).split("\\r?\\n")) {
                String line = raw.split("#", 2)[0].strip();
                if (line.isEmpty()) continue;
                Matcher m = DEPENDENCY.matcher(line);
                if (!ExecutionPackage.validDependency(line) || !m.matches()) {
                    unsupported.add(ZipNames.display(line));
                } else if (!seen.add(ExecutionPackage.normalized(m.group(1)))) {
                    unsupported.add(ZipNames.display(line) + " (repetida)");
                } else {
                    exact.add(line);
                }
            }
        }
        List<String> other = new ArrayList<>();
        for (String p : allPaths) {
            String low = p.toLowerCase(java.util.Locale.ROOT);
            if (p.equals("pyproject.toml") && !DECLARES_DEPENDENCIES.matcher(text(rootFiles.getOrDefault(p, new byte[0]))).find()) continue;
            if (OTHER.contains(p) || !p.contains("/") && low.startsWith("requirements") && low.endsWith(".txt") && !p.equals("requirements.txt")
                    || low.startsWith("requirements/") && low.endsWith(".txt")) {
                other.add(p);
            }
        }
        Optional<String> python = Optional.empty();
        String source = null;
        Optional<String> pyUnsupported = Optional.empty();
        if (rootFiles.containsKey(".python-version")) {
            String v = text(rootFiles.get(".python-version")).strip().split("\\s+", 2)[0];
            Matcher m = PY_VERSION.matcher(v);
            if (m.matches()) {
                python = Optional.of("==" + m.group(1) + "." + m.group(2) + ".*");
                source = ".python-version (" + ZipNames.display(v) + ")";
            } else if (!v.isEmpty()) {
                pyUnsupported = Optional.of(".python-version: " + ZipNames.display(v));
            }
        }
        if (python.isEmpty() && rootFiles.containsKey("pyproject.toml")) {
            Matcher m = REQUIRES_PYTHON.matcher(text(rootFiles.get("pyproject.toml")));
            if (m.find()) {
                String v = m.group(1).replace(" ", "");
                if (ExecutionPackage.validPythonRequirement(v)) {
                    python = Optional.of(v);
                    source = "pyproject.toml (requires-python)";
                    pyUnsupported = Optional.empty();
                } else {
                    pyUnsupported = Optional.of("pyproject.toml requires-python: " + ZipNames.display(m.group(1)));
                }
            }
        }
        return new Declarations(req, exact, unsupported, other, python, source, pyUnsupported);
    }

    private static final Pattern DEPENDENCY = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]{0,79})(\\[[A-Za-z0-9,._-]{1,80}])?==([0-9][0-9A-Za-z.+!-]{0,40})");

    private static String text(byte[] b) {
        String s = new String(b, StandardCharsets.UTF_8);
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }
}
