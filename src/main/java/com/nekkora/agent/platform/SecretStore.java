package com.nekkora.agent.platform;

import com.nekkora.agent.storage.AtomicWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Guarda do material secreto da máquina (credencial e resgate em andamento). Só o sistema operacional protege o
 * conteúdo: no Windows, DPAPI do usuário ({@link DpapiSecretStore}). Onde não houver proteção do sistema, a guarda se
 * declara indisponível e o pareamento é recusado (o segredo nunca vai para um arquivo comum).
 * <p>
 * Nomes fixos e curtos ({@code [a-z]{1,20}}). O conteúdo nunca aparece em log, registro, evidência nem tela.
 */
public interface SecretStore {

    /** O sistema oferece proteção (sem ela, nada é gravado). */
    boolean available();

    void write(String name, byte[] secret) throws IOException;

    Optional<byte[]> read(String name) throws IOException;

    void delete(String name) throws IOException;

    /** DPAPI no Windows; nos demais sistemas, indisponível. */
    static SecretStore system(Path dir, boolean windows, AtomicWriter writer) {
        return windows ? new DpapiSecretStore(dir, writer) : Unavailable.INSTANCE;
    }

    static void checkName(String name) {
        if (name == null || !name.matches("[a-z]{1,20}")) throw new IllegalArgumentException("nome");
    }

    /** Sem proteção do sistema: nada é aceito. */
    enum Unavailable implements SecretStore {
        INSTANCE;

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public void write(String name, byte[] secret) throws IOException {
            throw new IOException("armazenamento protegido indisponível");
        }

        @Override
        public Optional<byte[]> read(String name) {
            return Optional.empty();
        }

        @Override
        public void delete(String name) {
            // nada foi gravado
        }
    }
}
