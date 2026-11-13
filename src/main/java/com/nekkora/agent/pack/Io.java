package com.nekkora.agent.pack;

import com.nekkora.agent.pack.ImportProblem.Code;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Classificação das falhas de disco (sem texto do sistema na tela: só o motivo do vocabulário). */
final class Io {
    private Io() {}

    /** Falha na pasta de trabalho do Agent. */
    static ImportProblem work(IOException e, Path dir) {
        return noSpace(e, dir) ? new ImportProblem(Code.NO_SPACE) : new ImportProblem(Code.WORK_FAILED);
    }

    /** Falha na pasta escolhida pela pessoa. */
    static ImportProblem target(IOException e, Path dir) {
        return noSpace(e, dir) ? new ImportProblem(Code.NO_SPACE) : new ImportProblem(Code.WRITE_FAILED);
    }

    static boolean noSpace(IOException e, Path dir) {
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        if (m.contains("not enough space") || m.contains("no space") || m.contains("disk full") || m.contains("espaço")) return true;
        try {
            return dir != null && Files.getFileStore(dir).getUsableSpace() < 1024 * 1024;
        } catch (IOException | RuntimeException x) {
            return false;
        }
    }

    /** Apaga uma árvore da operação (melhor esforço; nunca segue ligação). */
    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var s = Files.walk(root)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // melhor esforço: a próxima operação limpa as sobras
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // idem
        }
    }
}
