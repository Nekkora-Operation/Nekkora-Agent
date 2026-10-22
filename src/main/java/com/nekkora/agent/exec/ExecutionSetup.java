package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Escolhas da pessoa operadora para a próxima execução (só em memória; nada disto é gravado): pacote inspecionado e se
 * está aprovado, Python conferido, entrada e pasta de saída. Diz o que ainda falta para poder iniciar — a interface só
 * habilita "Iniciar" quando nada falta. Textos sem caminhos completos (só nomes de arquivo/pasta).
 */
public record ExecutionSetup(ExecutionPackage pkg, ErrorCode packageProblem, boolean approved, PythonRuntime.Probe python, ErrorCode pythonProblem,
                             Path input, Path output, SessionProbe.Session session) {

    public static ExecutionSetup empty() {
        return new ExecutionSetup(null, null, false, null, null, null, null, null);
    }

    public ExecutionSetup withPackage(ExecutionPackage p, ErrorCode problem, boolean isApproved) {
        // outro pacote: a entrada escolhida para o anterior não vale; o Python é conferido de novo contra o novo pacote
        return new ExecutionSetup(p, problem, isApproved, python, pythonProblem(p, python, pythonProblem), null, output, session);
    }

    public ExecutionSetup withApproved(boolean a) {
        return new ExecutionSetup(pkg, packageProblem, a, python, pythonProblem, input, output, session);
    }

    public ExecutionSetup withPython(PythonRuntime.Probe probe, ErrorCode problem) {
        return new ExecutionSetup(pkg, packageProblem, approved, probe, pythonProblem(pkg, probe, problem), input, output, session);
    }

    public ExecutionSetup withInput(Path in) {
        return new ExecutionSetup(pkg, packageProblem, approved, python, pythonProblem, in, output, session);
    }

    public ExecutionSetup withOutput(Path out) {
        return new ExecutionSetup(pkg, packageProblem, approved, python, pythonProblem, input, out, session);
    }

    public ExecutionSetup withSession(SessionProbe.Session s) {
        return new ExecutionSetup(pkg, packageProblem, approved, python, pythonProblem, input, output, s);
    }

    private static ErrorCode pythonProblem(ExecutionPackage p, PythonRuntime.Probe probe, ErrorCode current) {
        if (probe == null) return current;
        if (p == null) return null;
        try {
            PythonRuntime.requireCompatible(probe, p);
            return null;
        } catch (ExecRefused e) {
            return e.code();
        }
    }

    /** O que falta, em ordem (vazio = pode iniciar). Só códigos do catálogo. */
    public List<ErrorCode> missing() {
        List<ErrorCode> out = new ArrayList<>();
        if (session != null && !session.usable()) out.add(session.problem());
        if (pkg == null) out.add(packageProblem != null ? packageProblem : ErrorCode.EXEC_PACKAGE_NOT_FOUND);
        else if (!approved) out.add(ErrorCode.EXEC_NOT_APPROVED);
        if (python == null) out.add(pythonProblem != null ? pythonProblem : ErrorCode.EXEC_PYTHON_INVALID);
        else if (pythonProblem != null) out.add(pythonProblem);
        if (pkg != null && pkg.needsInput() && input == null) out.add(ErrorCode.EXEC_INPUT_MISSING);
        if (output == null) out.add(ErrorCode.EXEC_OUTPUT_INVALID);
        return out;
    }

    public boolean canStart() {
        return session != null && missing().isEmpty();
    }

    /** O pedido tipado (só quando {@link #canStart()}). */
    public Optional<ExecutionController.Request> request(Duration timeout) {
        if (!canStart()) return Optional.empty();
        return Optional.of(new ExecutionController.Request(pkg.dir(), pkg.fingerprint(), python.python(), input, output, timeout));
    }

    // ----- textos -----

    public static String kindLabel(ExecutionPackage p) {
        return switch (p.kind()) {
            case STUDIO_PYTHON -> "Automação gerada pelo Studio";
            case LAB_GENERIC -> "Processo de laboratório (sem framework)";
        };
    }

    public static String modeLabel(ExecutionPackage p) {
        return switch (p.mode()) {
            case SINGLE -> "Execução única";
            case LIST -> "Lista de entrada → planilha de saída";
            case TABULAR -> "Planilha obtida pela própria automação";
            case LAB -> "Laboratório";
        };
    }

    public static String sessionLabel(SessionProbe.Session s) {
        if (s == null) return "Verificando…";
        if (s.usable()) return "Sessão interativa do usuário (tela desbloqueada)";
        return s.problem() == ErrorCode.EXEC_SESSION_LOCKED ? "Tela bloqueada: a execução não começa" : s.problem().description();
    }

    /** "1 min 05 s" (sem horário: só a duração). */
    public static String duration(long millis) {
        long s = Math.max(0, millis / 1000);
        return s < 60 ? s + " s" : (s / 60) + " min " + String.format("%02d", s % 60) + " s";
    }

    /** Pasta do ambiente Python (a venv: a pasta acima de {@code Scripts}/{@code bin}), só o nome. */
    public static String pythonFolder(Path python) {
        Path dir = python == null ? null : python.getParent();
        if (dir != null && dir.getFileName() != null && List.of("scripts", "bin").contains(dir.getFileName().toString().toLowerCase(java.util.Locale.ROOT))) {
            dir = dir.getParent();
        }
        return shortName(dir);
    }

    /** Nome do arquivo ou da pasta, nunca o caminho completo (a tela pode ser capturada). */
    public static String shortName(Path p) {
        return p == null || p.getFileName() == null ? "—" : p.getFileName().toString();
    }
}
