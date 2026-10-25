package com.nekkora.agent.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Processo auxiliar dos testes de bloqueio REAL de arquivo no Windows (F-AGT-01): OUTRO processo mantém o arquivo aberto
 * para leitura, como fazem antivírus, indexador, backup ou uma pessoa abrindo o {@code estado.json}. Protocolo pela
 * entrada/saída padrão, sem sleeps: imprime {@code READY}; a linha {@code OPEN} abre o arquivo e imprime {@code HELD} (ou
 * {@code FAILED:<tipo>}); {@code CLOSE} fecha e imprime {@code RELEASED}; o fim da entrada encerra.
 */
public final class FileHolder {
    private FileHolder() {}

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.println("READY");
        FileChannel ch = null;
        for (String line; (line = in.readLine()) != null; ) {
            if (line.equals("OPEN") && ch == null) {
                try {
                    ch = FileChannel.open(file, StandardOpenOption.READ);
                    out.println("HELD");
                } catch (IOException e) {
                    out.println("FAILED:" + e.getClass().getSimpleName());
                }
            } else if (line.equals("CLOSE") && ch != null) {
                ch.close();
                ch = null;
                out.println("RELEASED");
            }
        }
        if (ch != null) ch.close();
    }

    /** Controle do processo auxiliar, do lado do teste. */
    public static final class Handle implements AutoCloseable {
        private final Process process;
        private final BufferedReader out;
        private final PrintStream in;

        private Handle(Process p) {
            this.process = p;
            this.out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            this.in = new PrintStream(p.getOutputStream(), true, StandardCharsets.UTF_8);
        }

        /** Lança o processo auxiliar (outra JVM) para o arquivo dado; espera o {@code READY}. */
        public static Handle start(Path file) {
            try {
                String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), FileHolder.class.getName(), file.toString())
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                Handle h = new Handle(p);
                h.expect("READY");
                return h;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Abre o arquivo no outro processo; volta quando ele está de fato aberto. */
        public void hold() {
            in.println("OPEN");
            expect("HELD");
        }

        /** Fecha o arquivo no outro processo; volta quando ele está de fato fechado. */
        public void release() {
            in.println("CLOSE");
            expect("RELEASED");
        }

        /** Fecha depois de {@code delay}, numa linha à parte (o teste segue). */
        public Thread releaseAfter(Duration delay) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(delay.toMillis());
                    release();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "file-holder-release");
            t.setDaemon(true);
            t.start();
            return t;
        }

        private synchronized void expect(String token) {
            try {
                String line = out.readLine();
                if (!token.equals(line)) throw new IllegalStateException("processo auxiliar respondeu " + line + " em vez de " + token);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            in.close();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
