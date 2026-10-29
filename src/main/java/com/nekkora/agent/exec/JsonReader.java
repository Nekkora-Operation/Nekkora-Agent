package com.nekkora.agent.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor JSON completo (objetos, listas, texto, números, booleanos, nulo) para documentos de FORA do Agent: o manifesto
 * gerado pelo Studio, o descritor de pacote de laboratório e o resumo publicado pelo processo. Limites fixos de tamanho
 * e profundidade; chave repetida é erro. A configuração do próprio Agent continua no {@code StrictJson}.
 */
public final class JsonReader {
    public static final int MAX_CHARS = 4 * 1024 * 1024;
    public static final int MAX_DEPTH = 32;
    /** Marcador do {@code null} JSON. */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** Documento recusado (sem trecho do conteúdo na mensagem). */
    public static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String reason) {
            super(reason);
        }
    }

    private final String s;
    private int i;

    private JsonReader(String s) {
        this.s = s;
    }

    public static Map<String, Object> object(String text) throws Invalid {
        if (text == null || text.length() > MAX_CHARS) throw new Invalid("tamanho");
        JsonReader r = new JsonReader(text.startsWith("﻿") ? text.substring(1) : text);
        r.ws();
        Object v = r.value(0);
        r.ws();
        if (r.i != r.s.length() || !(v instanceof Map<?, ?>)) throw new Invalid("documento");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    private Object value(int depth) throws Invalid {
        if (depth > MAX_DEPTH) throw new Invalid("profundidade");
        char c = peek();
        if (c == '{') {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') {
                i++;
                return Collections.unmodifiableMap(m);
            }
            while (true) {
                ws();
                if (peek() != '"') throw new Invalid("chave");
                String k = string();
                ws();
                expect(':');
                ws();
                if (m.putIfAbsent(k, value(depth + 1)) != null) throw new Invalid("chave repetida");
                ws();
                char n = next();
                if (n == '}') return Collections.unmodifiableMap(m);
                if (n != ',') throw new Invalid("objeto");
            }
        }
        if (c == '[') {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            if (peek() == ']') {
                i++;
                return Collections.unmodifiableList(l);
            }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                char n = next();
                if (n == ']') return Collections.unmodifiableList(l);
                if (n != ',') throw new Invalid("lista");
            }
        }
        if (c == '"') return string();
        if (c == '-' || c >= '0' && c <= '9') return number();
        for (String w : new String[] {"true", "false", "null"}) {
            if (s.startsWith(w, i)) {
                i += w.length();
                return w.equals("null") ? NULL : Boolean.valueOf(w);
            }
        }
        throw new Invalid("valor");
    }

    private Object number() throws Invalid {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(start, i);
        try {
            if (t.matches("-?(0|[1-9][0-9]{0,17})")) return Long.parseLong(t);
            if (t.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            // cai no erro abaixo
        }
        throw new Invalid("número");
    }

    private String string() throws Invalid {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw new Invalid("texto");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw new Invalid("controle");
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw new Invalid("escape");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw new Invalid("escape");
                    }
                    i += 4;
                }
                default -> throw new Invalid("escape");
            }
        }
    }

    private void expect(char c) throws Invalid {
        if (i >= s.length() || s.charAt(i) != c) throw new Invalid("esperado " + c);
        i++;
    }

    private char peek() throws Invalid {
        if (i >= s.length()) throw new Invalid("fim");
        return s.charAt(i);
    }

    private char next() throws Invalid {
        char c = peek();
        i++;
        return c;
    }

    private void ws() {
        while (i < s.length() && " \t\n\r".indexOf(s.charAt(i)) >= 0) i++;
    }

    // ----- acesso tipado (null quando ausente ou de outro tipo) -----

    public static String text(Map<String, Object> m, String key) {
        return m.get(key) instanceof String v ? v : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        return m.get(key) instanceof Map<?, ?> v ? (Map<String, Object>) v : null;
    }

    public static List<?> list(Map<String, Object> m, String key) {
        return m.get(key) instanceof List<?> v ? v : null;
    }
}
