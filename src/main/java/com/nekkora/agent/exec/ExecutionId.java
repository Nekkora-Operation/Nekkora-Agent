package com.nekkora.agent.exec;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Identificador imutável de uma execução: {@code ex-} + 128 bits aleatórios em hexadecimal. Nunca é reaproveitado. */
public record ExecutionId(String value) {
    private static final Pattern FORMAT = Pattern.compile("ex-[0-9a-f]{32}");
    private static final SecureRandom RANDOM = new SecureRandom();

    public ExecutionId {
        if (value == null || !FORMAT.matcher(value).matches()) throw new IllegalArgumentException("identificador de execução inválido");
    }

    public static ExecutionId random() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return new ExecutionId("ex-" + HexFormat.of().formatHex(b));
    }

    public static boolean valid(String s) {
        return s != null && FORMAT.matcher(s).matches();
    }

    @Override
    public String toString() {
        return value;
    }
}
