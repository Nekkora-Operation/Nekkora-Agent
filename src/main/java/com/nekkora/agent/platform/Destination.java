package com.nekkora.agent.platform;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Destino do canal da máquina: só a ORIGEM {@code https} (host e porta) do endereço da Platform. O canal fica sob
 * {@code /agent/api/v1} da mesma origem (contrato, sec. 2). A origem é gravada no vínculo no momento do pareamento e a
 * credencial só é enviada para ela: se a configuração mudar depois, o transporte para e pede uma decisão da pessoa
 * (nenhuma credencial segue uma mudança silenciosa de endereço; redirecionamentos nunca são seguidos).
 */
public record Destination(String origin) {

    public Destination {
        if (origin == null || !origin.startsWith("https://")) throw new IllegalArgumentException("origem");
    }

    /** Endereço digitado → origem {@code https}; outro esquema, usuário embutido ou host ausente: vazio. */
    public static Optional<Destination> parse(String url) {
        if (url == null || url.isBlank() || url.length() > 2048) return Optional.empty();
        try {
            URI u = new URI(url.strip());
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getRawUserInfo() != null || u.getHost() == null || u.getHost().isBlank()) {
                return Optional.empty();
            }
            String host = u.getHost().toLowerCase(Locale.ROOT);
            int port = u.getPort();
            if (port == 0 || port > 65535) return Optional.empty();
            return Optional.of(new Destination("https://" + host + (port == -1 || port == 443 ? "" : ":" + port)));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    URI resolve(String path) {
        return URI.create(origin + path);
    }

    /** Host (para a tela e o diagnóstico; nunca caminho nem consulta). */
    public String host() {
        return URI.create(origin).getHost();
    }
}
