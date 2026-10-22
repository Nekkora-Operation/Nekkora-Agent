package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Sessão do usuário em que o Agent roda: a execução local só começa numa sessão INTERATIVA (nunca na Session 0 dos
 * serviços), com área de trabalho e com a tela desbloqueada. No Windows a informação vem do próprio sistema, sem código
 * nativo novo: {@code %SystemRoot%\System32\tasklist.exe} com argumentos fixos lê a sessão do processo do Agent e se o
 * {@code LogonUI.exe} (tela de bloqueio) está na mesma sessão.
 */
public interface SessionProbe {

    /** Estado observado; {@code problem} vazio = pode executar. */
    record Session(boolean interactive, int id, boolean locked, ErrorCode problem) {
        public boolean usable() {
            return problem == null;
        }
    }

    Session probe();

    /** Para o sistema em que o Agent roda (Windows: tasklist; outros: execução local não suportada nesta versão). */
    static SessionProbe system(boolean windows, Map<String, String> env) {
        if (!windows) return () -> new Session(false, -1, false, ErrorCode.EXEC_UNSUPPORTED_OS);
        return new Windows(Path.of(env.getOrDefault("SystemRoot", env.getOrDefault("SYSTEMROOT", "C:\\Windows")), "System32", "tasklist.exe"),
                ProcessHandle.current().pid());
    }

    /** Windows real. */
    final class Windows implements SessionProbe {
        private final Path tasklist;
        private final long pid;

        Windows(Path tasklist, long pid) {
            this.tasklist = tasklist;
            this.pid = pid;
        }

        @Override
        public Session probe() {
            if (GraphicsEnvironment.isHeadless() || !Files.isRegularFile(tasklist)) return new Session(false, -1, false, ErrorCode.EXEC_SESSION_UNAVAILABLE);
            List<String[]> own = rows(List.of("/FI", "PID eq " + pid, "/FO", "CSV", "/NH"));
            if (own.size() != 1 || own.get(0).length < 4 || !own.get(0)[3].matches("[0-9]{1,5}")) {
                return new Session(false, -1, false, ErrorCode.EXEC_SESSION_UNAVAILABLE);
            }
            int session = Integer.parseInt(own.get(0)[3]);
            // Session 0 é a dos serviços: sem área de trabalho do usuário, nunca executa robô ali
            if (session == 0) return new Session(false, 0, false, ErrorCode.EXEC_SESSION_UNAVAILABLE);
            boolean locked = !rows(List.of("/FI", "IMAGENAME eq LogonUI.exe", "/FI", "SESSION eq " + session, "/FO", "CSV", "/NH")).isEmpty();
            return new Session(true, session, locked, locked ? ErrorCode.EXEC_SESSION_LOCKED : null);
        }

        /** Linhas CSV do tasklist (a mensagem "nenhuma tarefa" não é CSV e fica de fora). */
        private List<String[]> rows(List<String> args) {
            List<String> cmd = new ArrayList<>();
            cmd.add(tasklist.toString());
            cmd.addAll(args);
            List<String[]> out = new ArrayList<>();
            Process p = null;
            try {
                p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                p.getOutputStream().close();
                byte[] data;
                try (InputStream in = p.getInputStream()) {
                    data = in.readNBytes(256 * 1024);
                }
                if (!p.waitFor(10, TimeUnit.SECONDS)) return List.of();
                for (String line : new String(data, StandardCharsets.ISO_8859_1).split("\\R")) {
                    String l = line.strip();
                    if (!l.startsWith("\"")) continue;
                    out.add(l.substring(1, l.length() - 1).split("\",\""));
                }
            } catch (IOException | RuntimeException e) {
                return List.of();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } finally {
                if (p != null && p.isAlive()) p.destroyForcibly();
            }
            return out;
        }
    }
}
