package com.nekkora.agent.exec;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A02: o resultado estruturado que um processo publicou, conferido para valer como desfecho quando o fim do processo NÃO
 * foi observado pelo supervisor (o Agent caiu e o processo terminou sozinho, ou foi adotado depois de um reinício e não
 * há código de saída). Só um protocolo COMPLETO e COERENTE decide; qualquer dúvida devolve uma recusa com o motivo, e a
 * execução continua {@code OUTCOME_UNKNOWN}. Nada é relançado.
 * <p>
 * Studio ({@code nekkora-rpa/run-summary} 1.x), todas as condições:
 * <ol>
 *   <li>o {@code summary.json} existe na pasta de saída que o Agent criou para ESTA execução, é legível, do contrato, e o
 *       {@code status} é um estado FINAL do Framework (o Framework grava o resumo uma única vez, atomicamente, no fim);</li>
 *   <li>o arquivo é posterior ao lançamento (não é sobra de outra coisa);</li>
 *   <li>o diário de eventos do Framework termina com {@code ENGINE_COMPLETED} da MESMA execução do Framework e com o MESMO
 *       desfecho (duas evidências independentes gravadas pelo processo, na ordem em que o Framework as grava).</li>
 * </ol>
 * Laboratório ({@code nekkora-agent/lab-result} 1.0.0): o resultado do contrato, posterior ao lançamento.
 */
public final class ProtocolEvidence {
    private ProtocolEvidence() {}

    /** Estados finais do resumo do Framework ({@code RunStatus}). */
    static final Set<String> FRAMEWORK_FINAL = Set.of("COMPLETED", "COMPLETED_WITH_FAILURES", "STOPPED", "INIT_FAILED", "CANCELLED");
    static final String SUMMARY_SCHEMA = "nekkora-rpa/run-summary";
    /** Quanto do fim do diário de eventos é lido para achar a última linha (o {@code ENGINE_COMPLETED} cabe com folga). */
    static final int TAIL_BYTES = 64 * 1024;
    /** Tolerância de relógio entre o lançamento registrado e a gravação do arquivo (mesma máquina). */
    static final Duration CLOCK_TOLERANCE = Duration.ofSeconds(2);

    /** Por que o protocolo encontrado não vale como desfecho (códigos estáveis; vão para o eventos.jsonl). */
    public enum Rejection {
        NO_OUTPUT_FOLDER, NO_PACKAGE_COPY, NO_PROTOCOL, PROTOCOL_INVALID, NOT_FINAL, OLDER_THAN_LAUNCH, NO_ENGINE_COMPLETED, ENGINE_MISMATCH
    }

    /** O protocolo aceito, ou o motivo da recusa. */
    public record Verdict(Launch.Reported reported, Rejection rejection) {
        public boolean accepted() {
            return reported != null;
        }

        static Verdict refuse(Rejection r) {
            return new Verdict(null, r);
        }
    }

    /**
     * Confere o protocolo de uma execução cujo fim não foi observado. {@code pkg}: a cópia conferida da execução;
     * {@code output}: a pasta de saída que o Agent criou para ela; {@code launched}: o instante do lançamento.
     */
    public static Verdict verify(ExecutionPackage pkg, Path output, Instant launched) {
        if (output == null || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) return Verdict.refuse(Rejection.NO_OUTPUT_FOLDER);
        if (pkg == null) return Verdict.refuse(Rejection.NO_PACKAGE_COPY);
        Path file = protocolFile(pkg.kind(), output);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Verdict.refuse(Rejection.NO_PROTOCOL);
        Optional<Launch.Reported> rep = Launch.reported(pkg.kind(), output);
        if (rep.isEmpty()) return Verdict.refuse(Rejection.PROTOCOL_INVALID);
        try {
            if (launched != null && Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(launched.minus(CLOCK_TOLERANCE))) {
                return Verdict.refuse(Rejection.OLDER_THAN_LAUNCH);
            }
        } catch (IOException e) {
            return Verdict.refuse(Rejection.PROTOCOL_INVALID);
        }
        if (pkg.kind() != ExecutionPackage.Kind.STUDIO_PYTHON) return new Verdict(rep.get(), null);
        Map<String, Object> summary;
        try {
            summary = JsonReader.object(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            return Verdict.refuse(Rejection.PROTOCOL_INVALID);
        }
        String version = JsonReader.text(summary, "version");
        String executionId = JsonReader.text(summary, "execution_id");
        if (!SUMMARY_SCHEMA.equals(JsonReader.text(summary, "schema")) || version == null || !version.matches("1\\.[0-9]{1,3}\\.[0-9]{1,3}")
                || executionId == null || !executionId.matches("ex-[0-9a-f]{8,64}")) {
            return Verdict.refuse(Rejection.PROTOCOL_INVALID);
        }
        if (!FRAMEWORK_FINAL.contains(rep.get().status())) return Verdict.refuse(Rejection.NOT_FINAL);
        Optional<Map<String, Object>> last = lastEvent(output.resolve("execucao").resolve("events.jsonl"));
        if (last.isEmpty() || !"ENGINE_COMPLETED".equals(JsonReader.text(last.get(), "event_code"))) return Verdict.refuse(Rejection.NO_ENGINE_COMPLETED);
        if (!executionId.equals(JsonReader.text(last.get(), "execution_id")) || !rep.get().status().equals(JsonReader.text(last.get(), "outcome"))) {
            return Verdict.refuse(Rejection.ENGINE_MISMATCH);
        }
        return new Verdict(rep.get(), null);
    }

    static Path protocolFile(ExecutionPackage.Kind kind, Path output) {
        return kind == ExecutionPackage.Kind.STUDIO_PYTHON ? output.resolve("execucao").resolve("summary.json") : output.resolve(Launch.LAB_RESULT);
    }

    /**
     * A última linha COMPLETA (terminada em quebra de linha) do diário, lida só do fim do arquivo. Linha final sem quebra
     * (gravação interrompida) não conta: a anterior é que é a última completa.
     */
    static Optional<Map<String, Object>> lastEvent(Path events) {
        try {
            if (!Files.isRegularFile(events, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            byte[] tail;
            try (RandomAccessFile f = new RandomAccessFile(events.toFile(), "r")) {
                long len = f.length();
                int n = (int) Math.min(len, TAIL_BYTES);
                tail = new byte[n];
                f.seek(len - n);
                f.readFully(tail);
            }
            String text = new String(tail, StandardCharsets.UTF_8);
            int end = text.lastIndexOf('\n');
            if (end < 0) return Optional.empty();
            List<String> lines = text.substring(0, end).lines().filter(l -> !l.isBlank()).toList();
            if (lines.isEmpty()) return Optional.empty();
            String line = lines.get(lines.size() - 1);
            // a primeira linha do trecho pode estar cortada no meio; só serve se não for ela (ou se o arquivo inteiro coube)
            if (lines.size() == 1 && tail.length == TAIL_BYTES) return Optional.empty();
            return Optional.of(JsonReader.object(line));
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            return Optional.empty();
        }
    }
}
