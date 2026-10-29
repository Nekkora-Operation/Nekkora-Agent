package com.nekkora.agent.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Trava de instância única: bloqueio exclusivo do sistema operacional sobre {@code agent.lock},
 * mantido durante toda a vida do processo.
 *
 * <ul>
 *   <li>A exclusão vem do bloqueio, não da existência do arquivo nem de PID: o arquivo nunca é
 *       apagado, nem ao sair, nem para "resolver" conflito.</li>
 *   <li>Se o processo cai, o Windows libera o bloqueio junto com o handle; o próximo início adquire
 *       a trava normalmente (sem trava presa).</li>
 *   <li>No Windows o arquivo é aberto sem compartilhamento de exclusão ({@code NOSHARE_DELETE}):
 *       enquanto a trava está ativa, o arquivo não pode ser apagado nem substituído por outro.</li>
 * </ul>
 */
public final class InstanceLock implements AutoCloseable {

    /** Resultado da tentativa. */
    public sealed interface Outcome permits Acquired, HeldElsewhere, Failed {}

    public record Acquired(InstanceLock lock) implements Outcome {}

    /** Outro processo (ou outra parte deste) já é o proprietário. */
    public record HeldElsewhere() implements Outcome {}

    /** Erro de E/S; {@code exceptionType} é só o nome simples da classe. */
    public record Failed(String exceptionType) implements Outcome {}

    private final FileChannel channel;
    private final FileLock lock;
    private boolean released;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static Outcome tryAcquire(Path lockFile) {
        FileChannel ch = null;
        try {
            ch = FileChannel.open(lockFile, openOptions());
            FileLock l = ch.tryLock();
            if (l == null) {
                ch.close();
                return new HeldElsewhere();
            }
            return new Acquired(new InstanceLock(ch, l));
        } catch (OverlappingFileLockException e) {
            closeQuietly(ch);
            return new HeldElsewhere();
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            closeQuietly(ch);
            return new Failed(e.getClass().getSimpleName());
        }
    }

    private static OpenOption[] openOptions() {
        List<OpenOption> options = new ArrayList<>(List.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE));
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            options.add(com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
        }
        return options.toArray(OpenOption[]::new);
    }

    public synchronized boolean isHeld() {
        return !released && lock.isValid();
    }

    /** Libera o bloqueio e fecha o arquivo (que permanece no disco). Idempotente. */
    @Override
    public synchronized void close() {
        if (released) return;
        released = true;
        try {
            lock.release();
        } catch (IOException ignored) {
            // o fechamento do canal libera de qualquer forma
        }
        closeQuietly(channel);
    }

    private static void closeQuietly(FileChannel ch) {
        if (ch == null) return;
        try {
            ch.close();
        } catch (IOException ignored) {
            // nada a fazer
        }
    }
}
