package com.nekkora.agent.core;

/**
 * Normalização de valores que vão para logs e diagnóstico. Lista positiva de caracteres: o que não
 * está nela vira {@code _}; o tamanho é limitado. Não substitui a regra principal — nunca registrar
 * caminhos, endereços, nomes ou mensagens de exceção —, apenas impede quebra de linha e injeção.
 */
public final class SafeText {
    private SafeText() {}

    /** Identificadores e versões: letras e dígitos ASCII e {@code . _ - + :}. */
    public static String token(String value, int maxLength) {
        return filter(value, maxLength, false);
    }

    /** Texto descritivo curto (nome e versão do SO, fornecedor do Java): como {@link #token} mais espaço e parênteses. */
    public static String label(String value, int maxLength) {
        return filter(value, maxLength, true);
    }

    private static String filter(String value, int maxLength, boolean spaces) {
        if (value == null) return "";
        StringBuilder b = new StringBuilder(Math.min(value.length(), maxLength));
        for (int i = 0; i < value.length() && b.length() < maxLength; i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '+' || c == ':'
                    || (spaces && (c == ' ' || c == '(' || c == ')'));
            b.append(ok ? c : '_');
        }
        return b.toString();
    }
}
