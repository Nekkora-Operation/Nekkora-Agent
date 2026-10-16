package com.nekkora.agent.storage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Processo auxiliar dos testes de trava. Protocolo pela entrada/saída padrão, sem sleeps:
 * imprime {@code READY}; espera a linha {@code GO}; tenta a trava e imprime {@code ACQUIRED},
 * {@code HELD} ou {@code FAILED:<tipo>}; mantém a trava até a entrada fechar (ou ser morto).
 */
public final class LockProbe {
    private LockProbe() {}

    public static void main(String[] args) throws Exception {
        Path lockFile = Path.of(args[0]);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println("READY");
        System.out.flush();
        if (!"GO".equals(in.readLine())) return;
        InstanceLock.Outcome o = InstanceLock.tryAcquire(lockFile);
        String result = switch (o) {
            case InstanceLock.Acquired a -> "ACQUIRED";
            case InstanceLock.HeldElsewhere h -> "HELD";
            case InstanceLock.Failed f -> "FAILED:" + f.exceptionType();
        };
        System.out.println(result);
        System.out.flush();
        while (in.readLine() != null) {
            // mantém o processo (e a trava) vivo até o pai fechar a entrada
        }
        if (o instanceof InstanceLock.Acquired a) a.lock().close();
    }
}
