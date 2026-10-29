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

    /** O FORMATO do pacote. Nunca afirma quem o gerou: o formato não prova procedência (ver {@link #originLabel}). */
    public static String kindLabel(ExecutionPackage p) {
        return switch (p.kind()) {
            case STUDIO_PYTHON -> "Pacote local importado (projeto Python com nekkora-rpa)";
            case LAB_GENERIC -> "Processo de laboratório (sem framework)";
        };
    }

    /**
     * Procedência: o Agent não tem como comprovar quem gerou um pacote local (sem assinatura nem atestado), então diz
     * isso. O gerador que o pacote declara aparece como declaração, não como fato.
     */
    public static String originLabel(ExecutionPackage p) {
        return "Origem não verificada" + p.declaredGenerator().map(g -> " · gerador declarado pelo pacote: " + g).orElse(" · nenhum gerador declarado");
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

    /** Estados de transação do Framework (singular, plural), na ordem em que aparecem no texto do progresso parcial. */
    private static final java.util.Map<String, String[]> TX_LABELS = new java.util.LinkedHashMap<>();

    static {
        TX_LABELS.put("SUCCEEDED", new String[] {"concluída", "concluídas"});
        TX_LABELS.put("BUSINESS_REJECTED", new String[] {"rejeitada pelo negócio", "rejeitadas pelo negócio"});
        TX_LABELS.put("FAILED_TECHNICAL", new String[] {"com falha técnica", "com falha técnica"});
        TX_LABELS.put("SKIPPED", new String[] {"pulada", "puladas"});
        TX_LABELS.put("CANCELLED", new String[] {"cancelada", "canceladas"});
        TX_LABELS.put("IN_PROGRESS", new String[] {"interrompida em andamento (desfecho desconhecido)", "interrompidas em andamento (desfecho desconhecido)"});
        TX_LABELS.put("OUTCOME_UNKNOWN", new String[] {"de desfecho desconhecido", "de desfecho desconhecido"});
        TX_LABELS.put("PENDING", new String[] {"pendente", "pendentes"});
    }

    private static String plural(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /**
     * A03: o progresso parcial em texto. Sempre diz que é parcial e que a planilha não tem confirmação de consolidação;
     * indisponível nunca vira "nada foi feito".
     */
    public static String progressLabel(ExecutionRecord.PartialProgress p) {
        if (!p.available()) {
            String why = switch (String.valueOf(p.problem())) {
                case CheckpointProgress.MISSING -> "o checkpoint do Framework não existe";
                case CheckpointProgress.INTEGRITY -> "o checkpoint do Framework não confere com a integridade gravada";
                case CheckpointProgress.TOO_LARGE -> "o checkpoint do Framework é grande demais para conferir";
                case CheckpointProgress.UNSUPPORTED -> "o checkpoint do Framework é de versão não suportada";
                case CheckpointProgress.NO_OUTPUT -> "a pasta de saída da execução não é conhecida";
                case CheckpointProgress.LOCKED -> "o checkpoint do Framework continuou bloqueado pelo Windows";
                default -> "o checkpoint do Framework não pôde ser lido";
            };
            return "Indisponível: " + why + ". Isto não significa que nada foi feito.";
        }
        if (p.total() == 0) return "Nenhuma transação registrada no checkpoint do Framework.";
        StringBuilder b = new StringBuilder();
        java.util.Set<String> shown = new java.util.HashSet<>();
        TX_LABELS.forEach((k, label) -> {
            Long n = p.transactions().get(k);
            if (n != null && n > 0) {
                b.append(b.isEmpty() ? "" : " · ").append(plural(n, label[0], label[1]));
                shown.add(k);
            }
        });
        p.transactions().forEach((k, n) -> {
            if (!shown.contains(k) && n > 0) b.append(b.isEmpty() ? "" : " · ").append(n).append(' ').append(k);
        });
        b.append(" · ").append(plural(p.delivered(), "entregue", "entregues")).append(" à saída. Planilha sem confirmação de consolidação.");
        return b.toString();
    }

    /** D-SA-04: o pedido de cancelamento e a situação dele (pedir não é confirmar). */
    public static String cancellationLabel(ExecutionRecord.Cancellation c) {
        String who = c.source() == ExecutionRecord.CancelSource.AGENT_SHUTDOWN ? "pelo encerramento do Agent" : "pela pessoa operadora";
        String when = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(c.requested());
        // a situação vem primeiro: numa linha estreita, o que se corta é o detalhe do pedido
        return switch (c.outcome()) {
            case PENDING -> "Aguardando confirmação";
            case CONFIRMED -> "Confirmado";
            case NOT_EFFECTIVE -> "Não efetivado (o processo terminou antes)";
        } + " · pedido " + who + " às " + when;
    }

    /** D-SA-05: quem PEDIU a execução (nunca quem gerou o pacote). */
    public static String originLabel(ExecutionRecord.Origin o) {
        return switch (o) {
            case LOCAL_UI -> "Janela do Agent (pessoa operadora)";
            case STUDIO_LOCAL -> "Studio nesta máquina (canal local)";
            case UNKNOWN -> "Não registrada (execução anterior a esta versão)";
        };
    }

    /** A02: de onde veio o desfecho quando o fim do processo não foi observado. */
    public static final String RECOVERED_LABEL = "Lido do resultado publicado pela automação depois do reinício do Agent (fim do processo não observado)";

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
