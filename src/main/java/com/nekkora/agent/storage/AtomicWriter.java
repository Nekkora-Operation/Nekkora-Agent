package com.nekkora.agent.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Gravação sem arquivo parcial: temporário no mesmo diretório, gravado e sincronizado no disco, e
 * então movido sobre o destino. Em falha o temporário é removido e o destino continua como estava.
 * <p>
 * F-AGT-01: no Windows a troca é negada enquanto OUTRO processo tem o destino aberto (antivírus, indexador, backup, uma
 * pessoa lendo o arquivo), mesmo com compartilhamento total. A troca é então tentada de novo algumas vezes, com espera
 * crescente ({@link #RETRY_MILLIS}, ~0,6 s no total); o temporário já está completo e sincronizado, então repetir a troca
 * não muda o que é gravado. Esgotadas as tentativas, a última falha é lançada (nada é dado por gravado).
 */
@FunctionalInterface
public interface AtomicWriter {

    void write(Path target, byte[] data) throws IOException;

    /** Esperas entre as tentativas da troca, em milissegundos (7 tentativas). */
    java.util.List<Long> RETRY_MILLIS = java.util.List.of(10L, 20L, 40L, 80L, 160L, 320L);

    AtomicWriter DEFAULT = (target, data) -> {
        Path dir = target.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(data);
                while (buf.hasRemaining()) ch.write(buf);
                ch.force(true);
            }
            replace(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    };

    private static void replace(Path tmp, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (FileSystemException e) {
                // só a negação por arquivo em uso é transitória; o resto (sem espaço, caminho inválido...) falha já
                boolean busy = e instanceof AccessDeniedException || e.getClass() == FileSystemException.class;
                if (!busy || attempt >= RETRY_MILLIS.size()) throw e;
                try {
                    Thread.sleep(RETRY_MILLIS.get(attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
