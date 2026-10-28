package com.nekkora.agent.exec;

import com.nekkora.agent.exec.ExecutionRecord.PartialProgress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A03: progresso parcial de uma execução do Studio sem resultado final (cancelada, prazo esgotado, desfecho
 * desconhecido, falha sem protocolo), lido do checkpoint do Framework ({@code <saída>/execucao/checkpoint.json},
 * {@code nekkora-rpa/checkpoint} 1.0.0/1.1.0) e CONFERIDO pela integridade que o próprio Framework grava (SHA-256 do corpo
 * em JSON canônico). O Framework grava o checkpoint atomicamente a cada transição, então ele é a fonte durável do que já
 * aconteceu.
 * <p>
 * Só sai daqui: quantas transações em cada estado do Framework e quantas foram entregues à saída. Nenhum identificador de
 * transação, valor, detalhe ou caminho. É PARCIAL: não diz que a execução terminou; uma transação {@code IN_PROGRESS}
 * continua de desfecho desconhecido; "entregue" ({@code delivery = COMMITTED}) é a entrega da transação ao destino, não a
 * consolidação da planilha — essa só o resultado final do Framework confirma, e aqui ele não existe.
 */
public final class CheckpointProgress {
    private CheckpointProgress() {}

    static final String SCHEMA = "nekkora-rpa/checkpoint";
    static final Set<String> READABLE = Set.of("1.0.0", "1.1.0");

    /** Por que não houve progresso verificável (vai para o registro; nunca quer dizer "nada foi feito"). */
    public static final String MISSING = "CHECKPOINT_MISSING";
    public static final String TOO_LARGE = "CHECKPOINT_TOO_LARGE";
    public static final String INVALID = "CHECKPOINT_INVALID";
    public static final String INTEGRITY = "CHECKPOINT_INTEGRITY";
    public static final String UNSUPPORTED = "CHECKPOINT_UNSUPPORTED";
    public static final String NO_OUTPUT = "OUTPUT_UNKNOWN";

    static Path file(Path output) {
        return output.resolve("execucao").resolve("checkpoint.json");
    }

    /** O progresso verificado, ou indisponível com o motivo. Nunca lança. */
    public static PartialProgress read(Path output) {
        if (output == null) return PartialProgress.unavailable(NO_OUTPUT);
        Path f = file(output);
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return PartialProgress.unavailable(MISSING);
            if (Files.size(f) > JsonReader.MAX_CHARS) return PartialProgress.unavailable(TOO_LARGE);
            Map<String, Object> doc = JsonReader.object(Files.readString(f, StandardCharsets.UTF_8));
            if (!doc.keySet().equals(Set.of("body", "integrity")) || !(doc.get("body") instanceof Map<?, ?> rawBody)
                    || !(doc.get("integrity") instanceof String integrity)) {
                return PartialProgress.unavailable(INVALID);
            }
            String expected = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(rawBody).getBytes(StandardCharsets.US_ASCII)));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), integrity.getBytes(StandardCharsets.US_ASCII))) {
                return PartialProgress.unavailable(INTEGRITY);
            }
            if (!SCHEMA.equals(rawBody.get("schema")) || !READABLE.contains(rawBody.get("version"))) return PartialProgress.unavailable(UNSUPPORTED);
            if (!(rawBody.get("transactions") instanceof Map<?, ?> txs)) return PartialProgress.unavailable(INVALID);
            Map<String, Long> byStatus = new TreeMap<>();
            long delivered = 0;
            for (Object v : txs.values()) {
                if (!(v instanceof Map<?, ?> t) || !(t.get("status") instanceof String st) || !st.matches("[A-Z_]{1,40}")) return PartialProgress.unavailable(INVALID);
                byStatus.merge(st, 1L, Long::sum);
                if ("COMMITTED".equals(t.get("delivery"))) delivered++;
            }
            return new PartialProgress(true, byStatus, delivered, null);
        } catch (IOException | JsonReader.Invalid | NoSuchAlgorithmException | RuntimeException e) {
            return PartialProgress.unavailable(INVALID);
        }
    }

    /**
     * O mesmo texto que {@code json.dumps(body, sort_keys=True, ensure_ascii=True, separators=(",", ":"))} do Python (a
     * forma sobre a qual o Framework calcula a integridade). Número fracionário não aparece no checkpoint: se aparecer, o
     * documento não é conferível e é recusado.
     */
    static String canonical(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == JsonReader.NULL) {
            b.append("null");
        } else if (v instanceof Boolean x) {
            b.append(x ? "true" : "false");
        } else if (v instanceof Long n) {
            b.append(n);
        } else if (v instanceof String s) {
            quote(b, s);
        } else if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            m.forEach((k, x) -> sorted.put((String) k, x));
            b.append('{');
            int i = 0;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (i++ > 0) b.append(',');
                quote(b, e.getKey());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) b.append(',');
                write(b, l.get(i));
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("valor não canônico");
        }
    }

    /** Aspas como o {@code ensure_ascii} do Python: escapes curtos conhecidos, controle e não ASCII em {@code \}{@code uXXXX}. */
    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    // como o ESCAPE_ASCII do Python: só o intervalo espaço..'~' sai literal (o DEL 0x7f também é escapado)
                    if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
