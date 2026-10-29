package com.nekkora.agent.storage;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Processo auxiliar do teste de QUEDA durante a gravação: regrava o alvo sem parar com {@link AtomicWriter#DEFAULT},
 * versões de {@link #SIZE} bytes, cada uma de um único caractere repetido. O teste mata este processo num instante
 * qualquer e confere que o alvo é sempre uma versão INTEIRA (nunca uma mistura ou um pedaço).
 */
public final class WriterLoop {
    private WriterLoop() {}

    public static final int SIZE = 4 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        Path target = Path.of(args[0]);
        System.out.println("READY");
        System.out.flush();
        byte[] data = new byte[SIZE];
        for (int n = 0; ; n++) {
            Arrays.fill(data, (byte) ('a' + n % 26));
            AtomicWriter.DEFAULT.write(target, data);
        }
    }
}
