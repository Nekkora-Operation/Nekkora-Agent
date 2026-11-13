package com.nekkora.agent.pack;

import com.nekkora.agent.exec.ExecRefused;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.pack.ImportProblem.Code;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;

/**
 * AGENT-PACOTES-001 T03: prepara um pacote em ZIP para a janela "Execução local" sem a pessoa extrair nada. O ZIP é lido pelo
 * leitor seguro ({@link SafeZip}) numa pasta do Agent e conferido pelo validador real ({@link ExecutionPackage#inspect}); a
 * pasta definitiva é {@code <dados>/pacotes-zip/<impressão>}, de modo que o mesmo conteúdo cai sempre no mesmo lugar e um
 * conteúdo diferente nunca se mistura com outro.
 * <p>
 * Isto NÃO é a estante ({@code PackageShelf}): um pacote preparado aqui não fica alcançável por pedidos de execução da
 * Platform. Também NÃO aprova nada: a aprovação continua sendo o ato da pessoa na janela, pela impressão, e o início confere a
 * impressão de novo. Nada é executado aqui.
 */
public final class ZipStager {
    /** Pastas preparadas mais velhas que isto são apagadas ao preparar outra (a impressão é conferida de novo no início). */
    static final Duration KEEP = Duration.ofDays(7);

    private final Path root;

    public ZipStager(Path root) {
        this.root = root;
    }

    public static Path defaultRoot(Path agentDataRoot) {
        return agentDataRoot.resolve("pacotes-zip");
    }

    /** Lê, confere e fixa o pacote. Recusa do ZIP → {@link ImportProblem}; recusa do pacote → {@link ExecRefused} do validador. */
    public ExecutionPackage stage(Path zip, SafeZip.Cancel cancel) throws ImportProblem, ExecRefused {
        Path tmp;
        try {
            Files.createDirectories(root);
            tmp = Files.createTempDirectory(root, "preparo-");
        } catch (IOException e) {
            throw Io.work(e, root);
        }
        try {
            Path into = Files.createDirectories(tmp.resolve("x"));
            SafeZip.Content c = SafeZip.extract(zip, into, ZipLimits.DEFAULT, cancel);
            String prefix = ProjectImporter.rootPrefix(c);
            Path pkgRoot = prefix.isEmpty() ? into : into.resolve(prefix);
            ExecutionPackage staged = ExecutionPackage.inspect(pkgRoot);
            if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
            Path target = root.resolve(staged.fingerprint().substring("sha256:".length()));
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    ExecutionPackage existing = ExecutionPackage.inspect(target);
                    if (existing.fingerprint().equals(staged.fingerprint())) {
                        touch(target);
                        return existing; // o mesmo conteúdo já está preparado
                    }
                } catch (ExecRefused e) {
                    // pasta alterada ou incompleta: refeita abaixo
                }
                Io.deleteTree(target);
            }
            try {
                Files.move(pkgRoot, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                copy(pkgRoot, target);
            }
            ExecutionPackage done = ExecutionPackage.inspect(target);
            if (!done.fingerprint().equals(staged.fingerprint())) throw new ExecRefused(com.nekkora.agent.core.ErrorCode.EXEC_PACKAGE_CHANGED);
            touch(target);
            cleanOld(target);
            return done;
        } catch (IOException e) {
            throw Io.work(e, root);
        } finally {
            Io.deleteTree(tmp);
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (var s = Files.walk(from)) {
            for (Path p : s.toList()) {
                if (Files.isSymbolicLink(p)) throw new IOException("ligação");
                Path t = to.resolve(from.relativize(p).toString()).normalize();
                if (!t.startsWith(to)) throw new IOException("fora da pasta");
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
    }

    private static void touch(Path dir) {
        try {
            Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // só para a limpeza por idade
        }
    }

    private void cleanOld(Path keep) {
        try (var s = Files.list(root)) {
            Instant limit = Instant.now().minus(KEEP);
            for (Path p : s.toList()) {
                if (p.equals(keep) || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(limit)) Io.deleteTree(p);
            }
        } catch (IOException | RuntimeException ignored) {
            // melhor esforço
        }
    }
}
