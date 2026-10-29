package com.nekkora.agent.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Leitor e escritor JSON mínimo e estrito para a configuração: objetos, cadeias, inteiros, booleanos
 * e null. Rejeita listas, números fracionários, chaves repetidas, conteúdo após o objeto,
 * profundidade maior que {@link #MAX_DEPTH} e caracteres de controle não escapados.
 */
public final class StrictJson {
    public static final int MAX_DEPTH = 4;

    /** Valor JSON {@code null} (o mapa não aceita {@code null} como "ausente"). */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** Erro de sintaxe; a mensagem é genérica e não repete o conteúdo do arquivo. */
    public static final class SyntaxException extends Exception {
        private static final long serialVersionUID = 1L;

        SyntaxException(String what, int position) {
            super(what + " (posição " + position + ")");
        }
    }

    private final String s;
    private int i;

    private StrictJson(String s) {
        this.s = s;
    }

    public static Map<String, Object> parseObject(String text) throws SyntaxException {
        StrictJson p = new StrictJson(text);
        p.skipWs();
        Map<String, Object> m = p.object(1);
        p.skipWs();
        if (p.i != p.s.length()) throw new SyntaxException("conteúdo após o objeto", p.i);
        return m;
    }

    private Map<String, Object> object(int depth) throws SyntaxException {
        if (depth > MAX_DEPTH) throw new SyntaxException("profundidade excessiva", i);
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            i++;
            return Collections.unmodifiableMap(m);
        }
        while (true) {
            skipWs();
            String key = string();
            skipWs();
            expect(':');
            skipWs();
            Object v = value(depth);
            if (m.putIfAbsent(key, v) != null) throw new SyntaxException("chave repetida", i);
            skipWs();
            char c = next();
            if (c == '}') return Collections.unmodifiableMap(m);
            if (c != ',') throw new SyntaxException("esperado ',' ou '}'", i - 1);
        }
    }

    private Object value(int depth) throws SyntaxException {
        char c = peek();
        if (c == '{') return object(depth + 1);
        if (c == '"') return string();
        if (c == '-' || (c >= '0' && c <= '9')) return integer();
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return NULL;
        }
        throw new SyntaxException("valor não suportado", i);
    }

    private Long integer() throws SyntaxException {
        int start = i;
        if (peek() == '-') i++;
        int digits = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i)) && s.charAt(i) < 128) {
            i++;
            digits++;
        }
        if (digits == 0 || digits > 18) throw new SyntaxException("número inválido", start);
        if (i < s.length() && (s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            throw new SyntaxException("somente inteiros são aceitos", start);
        }
        return Long.parseLong(s.substring(start, i));
    }

    private String string() throws SyntaxException {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw new SyntaxException("texto não terminado", i);
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw new SyntaxException("caractere de controle no texto", i - 1);
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
                    if (i + 4 > s.length()) throw new SyntaxException("escape incompleto", i);
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw new SyntaxException("escape inválido", i);
                    }
                    i += 4;
                }
                default -> throw new SyntaxException("escape inválido", i - 1);
            }
        }
    }

    private void expect(char c) throws SyntaxException {
        if (i >= s.length() || s.charAt(i) != c) throw new SyntaxException("esperado '" + c + "'", i);
        i++;
    }

    private char peek() throws SyntaxException {
        if (i >= s.length()) throw new SyntaxException("fim inesperado", i);
        return s.charAt(i);
    }

    private char next() throws SyntaxException {
        char c = peek();
        i++;
        return c;
    }

    private void skipWs() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            i++;
        }
    }

    /** Cadeia JSON com aspas e escapes (controles, aspas, barra invertida e não-ASCII como {@code \\uXXXX}). */
    public static String quote(String v) {
        StringBuilder b = new StringBuilder(v.length() + 2).append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
