package com.nekkora.agent.platform;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * Uma chamada HTTPS da máquina. {@link IOException} = nenhuma resposta (conexão recusada ou interrompida, nome, TLS,
 * tempo esgotado, resposta acima do limite): o resultado no servidor é DESCONHECIDO e o pedido pode ser repetido com o
 * mesmo conteúdo. Qualquer status HTTP volta como {@link Response} (inclusive 3xx: redirecionamentos nunca são seguidos).
 */
public interface HttpTransport {

    record Request(String method, URI uri, Map<String, String> headers, byte[] body) {
        @Override
        public String toString() {
            return method + " " + uri.getPath(); // nunca cabeçalhos nem corpo
        }
    }

    /** {@code retryAfterSeconds}: o {@code Retry-After} em segundos, quando veio e é válido. */
    record Response(int status, Optional<Long> retryAfterSeconds, byte[] body) {}

    Response send(Request request) throws IOException;
}
