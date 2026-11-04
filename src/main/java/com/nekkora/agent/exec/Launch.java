package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Do pacote aprovado ao processo: cópia CONFERIDA para a pasta da execução (o processo roda da cópia — mudar a pasta
 * original depois da conferência não alcança a execução), a linha de comando montada pelo adaptador do tipo de pacote
 * (lista fechada de opções, argumentos separados, sem shell) e a leitura do resultado estruturado que o processo publica.
 */
public final class Launch {
    private Launch() {}

    /** O que lançar: executável conhecido, argumentos em lista, pasta de trabalho controlada. */
    public record Spec(Path executable, List<String> args, Path workDir) {
        public Spec {
            args = List.copyOf(args);
        }

        public List<String> command() {
            List<String> c = new ArrayList<>();
            c.add(executable.toString());
            c.addAll(args);
            return c;
        }
    }

    /** Resultado publicado pelo processo (só estado e contagens). */
    public record Reported(String status, Map<String, Long> counts, String outputStatus) {}

    public static final String LAB_RESULT = "agent-result.json";
    public static final String LAB_RESULT_SCHEMA = "nekkora-agent/lab-result";

    /** Copia só os arquivos listados (e o descritor) e confere a cópia contra a impressão digital aprovada. */
    public static Path snapshot(ExecutionPackage pkg, Path runDir) throws ExecRefused {
        Path copy = runDir.resolve("pacote");
        try {
            Files.createDirectories(copy);
            String descriptor = ExecutionPackage.descriptorOf(pkg.kind());
            List<String> all = new ArrayList<>(pkg.files().keySet());
            all.add(descriptor);
            for (String rel : all) {
                Path from = pkg.dir().resolve(rel).normalize();
                Path to = copy.resolve(rel).normalize();
                if (!from.startsWith(pkg.dir()) || !to.startsWith(copy) || Files.isSymbolicLink(from)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
                Files.createDirectories(to.getParent());
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            }
            ExecutionPackage.verify(copy, pkg.files());
            if (!pkg.fingerprint().equals(ExecutionPackage.fingerprint(pkg.kind(), copy, pkg.files(), descriptor))) {
                throw new ExecRefused(ErrorCode.EXEC_PACKAGE_CHANGED);
            }
            return copy;
        } catch (IOException e) {
            throw new ExecRefused(ErrorCode.EXEC_STORAGE_FAILED);
        }
    }

    /**
     * Linha de comando do tipo de pacote. Studio: {@code python -E -s -B -X utf8 main.py} + as opções DECLARADAS no
     * manifesto ({@code cli.options}) + {@code --channel chrome} (navegador visível na sessão do usuário); laboratório:
     * {@code python -E -s -B <script> --output <pasta>} (sem {@code -I}: o script importa módulos da própria pasta).
     */
    public static Spec spec(ExecutionPackage pkg, Path python, Path copy, Path input, Path output) {
        List<String> a = new ArrayList<>();
        if (pkg.kind() == ExecutionPackage.Kind.STUDIO_PYTHON) {
            a.addAll(List.of("-E", "-s", "-B", "-X", "utf8", "main.py"));
            Map<String, String> o = pkg.options();
            if (o.containsKey("input")) a.addAll(List.of(o.get("input"), input.toString()));
            if (o.containsKey("spreadsheet")) a.addAll(List.of(o.get("spreadsheet"), output.resolve("planilha.xlsx").toString()));
            a.addAll(List.of(o.get("output"), output.toString(), "--channel", "chrome"));
        } else if (pkg.kind() == ExecutionPackage.Kind.EXTERNAL_PYTHON) {
            // AGENT-PYTHON-001: o projeto do cliente roda com o Python que a pessoa preparou, da cópia conferida, sem argumento
            // da Platform (a pasta de saída e o sinal opcional chegam por variável de ambiente)
            a.addAll(List.of("-E", "-s", "-B", pkg.entry()));
        } else {
            a.addAll(List.of("-E", "-s", "-B", pkg.entry(), "--output", output.toString()));
        }
        return new Spec(python, a, copy);
    }

    /** O resultado estruturado que o processo publicou, ou vazio (ausente, ilegível ou fora do contrato). */
    public static java.util.Optional<Reported> reported(ExecutionPackage.Kind kind, Path output) {
        if (kind == ExecutionPackage.Kind.EXTERNAL_PYTHON) {
            // sinal OPCIONAL do script (o mesmo do lançador): só o esquema fechado conta
            ExternalLaunch.Signal s = ExternalLaunch.readSignal(output.resolve(ExternalLaunch.SIGNAL_FILE));
            return s == ExternalLaunch.Signal.NONE ? java.util.Optional.empty() : java.util.Optional.of(new Reported(s.name(), Map.of(), null));
        }
        Path f = kind == ExecutionPackage.Kind.STUDIO_PYTHON ? output.resolve("execucao").resolve("summary.json") : output.resolve(LAB_RESULT);
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > 1024 * 1024) return java.util.Optional.empty();
            Map<String, Object> m = JsonReader.object(Files.readString(f, StandardCharsets.UTF_8));
            if (kind == ExecutionPackage.Kind.LAB_GENERIC && (!LAB_RESULT_SCHEMA.equals(JsonReader.text(m, "schema")) || !"1.0.0".equals(JsonReader.text(m, "version")))) {
                return java.util.Optional.empty();
            }
            String status = JsonReader.text(m, "status");
            if (status == null || !status.matches("[A-Z_]{1,40}")) return java.util.Optional.empty();
            Map<String, Long> counts = new LinkedHashMap<>();
            Map<String, Object> c = JsonReader.obj(m, "counts");
            if (c != null) c.forEach((k, v) -> {
                if (k.matches("[A-Z_]{1,40}") && v instanceof Long n) counts.put(k, n);
            });
            Map<String, Object> out = JsonReader.obj(m, "output");
            String outStatus = out == null ? null : JsonReader.text(out, "status");
            return java.util.Optional.of(new Reported(status, counts, outStatus != null && outStatus.matches("[A-Z_]{1,40}") ? outStatus : null));
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Estado final pelo protocolo: só o resultado ESTRUTURADO confirma sucesso. Studio: {@code COMPLETED} (e, havendo
     * planilha, consolidada: {@code COMMITTED}); laboratório: {@code SUCCEEDED}. Sem protocolo: código ≠ 0 → falha;
     * código 0 → resultado desconhecido (nada é presumido).
     */
    public static Outcome classify(ExecutionPackage pkg, java.util.Optional<Reported> r, int exitCode) {
        if (pkg.kind() == ExecutionPackage.Kind.EXTERNAL_PYTHON) {
            // pacote externo: código 0 sem sinal = término sem erro sinalizado (AGT-EXT-001), nunca sucesso de negócio
            ExternalLaunch.Signal s = r.map(x -> ExternalLaunch.Signal.valueOf(x.status())).orElse(ExternalLaunch.Signal.NONE);
            ExternalLaunch.Outcome o = ExternalLaunch.classify(exitCode, s);
            return new Outcome(o.state(), o.code());
        }
        if (r.isEmpty()) {
            return exitCode == 0 ? new Outcome(ExecutionState.OUTCOME_UNKNOWN, ErrorCode.EXEC_NO_PROTOCOL)
                    : new Outcome(ExecutionState.FAILED, ErrorCode.EXEC_EXIT_NONZERO);
        }
        boolean ok = reportsSuccess(pkg, r.get()) && exitCode == 0;
        return ok ? new Outcome(ExecutionState.SUCCEEDED, null) : new Outcome(ExecutionState.FAILED, ErrorCode.EXEC_REPORTED_FAILURE);
    }

    /**
     * A02: fim do processo NÃO observado (Agent reiniciado; sem código de saída). Só um protocolo já CONFERIDO por
     * {@link ProtocolEvidence#verify} chega aqui; o critério de sucesso é o mesmo de {@link #classify}, sem o código de saída.
     */
    public static Outcome classifyVerified(ExecutionPackage pkg, ProtocolEvidence.Verdict v) {
        if (!v.accepted()) throw new IllegalArgumentException("protocolo não conferido");
        return reportsSuccess(pkg, v.reported()) ? new Outcome(ExecutionState.SUCCEEDED, null) : new Outcome(ExecutionState.FAILED, ErrorCode.EXEC_REPORTED_FAILURE);
    }

    private static boolean reportsSuccess(ExecutionPackage pkg, Reported x) {
        return pkg.kind() == ExecutionPackage.Kind.STUDIO_PYTHON
                ? "COMPLETED".equals(x.status()) && (pkg.mode() != ExecutionPackage.Mode.LIST || "COMMITTED".equals(x.outputStatus()))
                : "SUCCEEDED".equals(x.status());
    }

    public record Outcome(ExecutionState state, ErrorCode code) {}
}
