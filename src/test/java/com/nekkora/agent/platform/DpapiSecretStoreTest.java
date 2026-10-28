package com.nekkora.agent.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.storage.AtomicWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** A guarda de segredos real: DPAPI do usuário no Windows; nos demais sistemas, indisponível (nada é gravado). */
class DpapiSecretStoreTest {
    @TempDir
    Path dir;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("S1: DPAPI: ida e volta, arquivo sem o texto do segredo, apagar remove")
    void dpapiRoundTrip() throws Exception {
        SecretStore s = SecretStore.system(dir, true, AtomicWriter.DEFAULT);
        assertTrue(s.available());
        String secret = "nka1.7a1c0000-0000-4000-8000-000000000001.0d3a0000-0000-4000-8000-000000000002." + FakeGateway.secret();
        s.write("credencial", secret.getBytes(StandardCharsets.US_ASCII));
        byte[] onDisk = Files.readAllBytes(dir.resolve("credencial.dpapi"));
        assertFalse(new String(onDisk, StandardCharsets.ISO_8859_1).contains(secret.substring(secret.lastIndexOf('.') + 1)), "protegido, não em texto");
        assertArrayEquals(secret.getBytes(StandardCharsets.US_ASCII), s.read("credencial").orElseThrow());
        // conteúdo adulterado não é aceito
        onDisk[onDisk.length / 2] ^= 0x5a;
        Files.write(dir.resolve("credencial.dpapi"), onDisk);
        assertThrows(IOException.class, () -> s.read("credencial"));
        s.delete("credencial");
        assertEquals(Optional.empty(), s.read("credencial"));
    }

    @Test
    @DisplayName("S2: sem proteção do sistema: indisponível e nada é gravado")
    void unavailableElsewhere() {
        SecretStore s = SecretStore.system(dir, false, AtomicWriter.DEFAULT);
        assertFalse(s.available());
        assertThrows(IOException.class, () -> s.write("credencial", new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> SecretStore.checkName("../x"));
    }
}
