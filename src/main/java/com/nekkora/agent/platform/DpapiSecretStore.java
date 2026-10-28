package com.nekkora.agent.platform;

import com.nekkora.agent.storage.AtomicWriter;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * DPAPI do usuário ({@code CryptProtectData}, sem interface): o arquivo {@code <nome>.dpapi} só é legível por este usuário
 * do Windows nesta máquina. A entropia fixa só separa o uso (não é segredo). Gravação atômica; a falha do sistema vira
 * {@link IOException} sem detalhe do conteúdo.
 */
final class DpapiSecretStore implements SecretStore {
    private static final byte[] ENTROPY = "nekkora-agent/platform-secret/1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_BLOB = 16 * 1024;

    private final Path dir;
    private final AtomicWriter writer;
    private volatile Boolean available;

    DpapiSecretStore(Path dir, AtomicWriter writer) {
        this.dir = dir;
        this.writer = writer;
    }

    @Override
    public boolean available() {
        Boolean a = available;
        if (a == null) {
            // a biblioteca nativa carrega na primeira chamada: um teste de ida e volta diz se o sistema protege de fato
            try {
                byte[] probe = {1, 2, 3};
                a = java.util.Arrays.equals(probe, unprotect(protect(probe)));
            } catch (IOException | LinkageError | RuntimeException e) {
                a = false;
            }
            available = a;
        }
        return a;
    }

    @Override
    public void write(String name, byte[] secret) throws IOException {
        SecretStore.checkName(name);
        Files.createDirectories(dir);
        writer.write(dir.resolve(name + ".dpapi"), protect(secret));
    }

    @Override
    public Optional<byte[]> read(String name) throws IOException {
        SecretStore.checkName(name);
        Path f = dir.resolve(name + ".dpapi");
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            if (Files.size(f) > MAX_BLOB) throw new IOException("material protegido grande demais");
            return Optional.of(unprotect(Files.readAllBytes(f)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String name) throws IOException {
        SecretStore.checkName(name);
        Files.deleteIfExists(dir.resolve(name + ".dpapi"));
    }

    private static byte[] protect(byte[] data) throws IOException {
        try {
            return Crypt32Util.cryptProtectData(data, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "Nekkora Agent", null);
        } catch (RuntimeException | LinkageError e) {
            throw new IOException("proteção do sistema falhou");
        }
    }

    private static byte[] unprotect(byte[] blob) throws IOException {
        try {
            return Crypt32Util.cryptUnprotectData(blob, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null);
        } catch (RuntimeException | LinkageError e) {
            throw new IOException("material protegido ilegível");
        }
    }
}
