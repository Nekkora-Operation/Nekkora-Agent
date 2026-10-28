package com.nekkora.agent.platform;

import com.nekkora.agent.exec.JsonReader;
import com.nekkora.agent.storage.StrictJson;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JSON do canal: escrita compacta (só o que o contrato pede, na ordem dada) e leitura das respostas da API pelo
 * {@link JsonReader} (limites de tamanho e profundidade; chave repetida é erro). Os acessos tipados devolvem
 * {@code null} quando o campo falta ou tem outro tipo: quem chama decide se a resposta é aceitável.
 */
final class Json {
    private Json() {}

    /** Objeto em construção (campos na ordem de inserção). */
    static final class Obj {
        private final StringBuilder b = new StringBuilder("{");

        private Obj key(String k) {
            if (b.length() > 1) b.append(',');
            b.append(StrictJson.quote(k)).append(':');
            return this;
        }

        Obj str(String k, String v) {
            key(k).b.append(v == null ? "null" : StrictJson.quote(v));
            return this;
        }

        Obj num(String k, Number v) {
            key(k).b.append(v == null ? "null" : v.toString());
            return this;
        }

        Obj bool(String k, boolean v) {
            key(k).b.append(v);
            return this;
        }

        /** Valor já em JSON (um envelope gravado no histórico, uma lista montada aqui). */
        Obj raw(String k, String json) {
            key(k).b.append(json);
            return this;
        }

        Obj counts(String k, Map<String, Long> m) {
            StringBuilder c = new StringBuilder("{");
            m.forEach((name, n) -> c.append(c.length() > 1 ? "," : "").append(StrictJson.quote(name)).append(':').append(n));
            return raw(k, c.append('}').toString());
        }

        @Override
        public String toString() {
            return b + "}";
        }
    }

    static Obj obj() {
        return new Obj();
    }

    static String list(List<String> jsonItems) {
        return "[" + String.join(",", jsonItems) + "]";
    }

    static Map<String, Object> parse(String text) throws JsonReader.Invalid {
        return JsonReader.object(text);
    }

    static String text(Map<String, Object> m, String k) {
        return m.get(k) instanceof String s ? s : null;
    }

    static Long number(Map<String, Object> m, String k) {
        return m.get(k) instanceof Long n ? n : null;
    }

    static Boolean bool(Map<String, Object> m, String k) {
        return m.get(k) instanceof Boolean v ? v : null;
    }

    static UUID uuid(Map<String, Object> m, String k) {
        String s = text(m, k);
        if (s == null || !s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return null;
        return UUID.fromString(s);
    }

    /** Instante ISO-8601 com deslocamento ({@code Z} ou {@code +00:00}). */
    static Instant instant(Map<String, Object> m, String k) {
        String s = text(m, k);
        if (s == null || s.length() > 40) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    static List<?> list(Map<String, Object> m, String k) {
        return m.get(k) instanceof List<?> l ? l : null;
    }
}
