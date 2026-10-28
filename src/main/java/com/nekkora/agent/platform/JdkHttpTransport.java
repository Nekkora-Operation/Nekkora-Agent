package com.nekkora.agent.platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Cliente HTTPS do canal ({@code java.net.http}, o ÚNICO uso de rede do Agent; ver {@code ArchitectureTest}). Regras:
 * <ul>
 *   <li>só {@code https} (o {@link Destination} garante), com a validação normal do certificado e do nome do servidor
 *       pelo {@code SSLContext} padrão da JVM — nenhuma confiança é relaxada aqui; um laboratório confia no próprio
 *       certificado pelo armazenamento de confiança da JVM, nunca desligando a validação;</li>
 *   <li>redirecionamentos NUNCA são seguidos (a credencial não vai para outro destino);</li>
 *   <li>sem proxy implícito; HTTP/1.1; prazo de conexão de {@link #CONNECT_TIMEOUT} e de resposta de {@link #REQUEST_TIMEOUT};</li>
 *   <li>corpo da resposta limitado a {@link #MAX_RESPONSE} bytes (acima disso, a resposta é tratada como falha de transporte).</li>
 * </ul>
 */
final class JdkHttpTransport implements HttpTransport {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_RESPONSE = 1024 * 1024;

    private final HttpClient client;

    JdkHttpTransport() {
        this(null);
    }

    /** {@code ssl} nulo = o contexto padrão da JVM (produção). Os testes passam um contexto que confia num certificado de teste. */
    JdkHttpTransport(javax.net.ssl.SSLContext ssl) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(HttpClient.Builder.NO_PROXY);
        if (ssl != null) b.sslContext(ssl);
        this.client = b.build();
    }

    @Override
    public Response send(Request r) throws IOException {
        if (!"https".equals(r.uri().getScheme())) throw new IOException("só https");
        HttpRequest.Builder b = HttpRequest.newBuilder(r.uri()).timeout(REQUEST_TIMEOUT);
        r.headers().forEach(b::header);
        b.method(r.method(), r.body() == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(r.body()));
        HttpResponse<InputStream> res;
        try {
            res = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrompido");
        } catch (IllegalArgumentException | SecurityException e) {
            throw new IOException("pedido recusado pelo cliente");
        }
        byte[] body;
        try (InputStream in = res.body()) {
            body = in.readNBytes(MAX_RESPONSE + 1);
            if (body.length > MAX_RESPONSE) throw new IOException("resposta acima do limite");
        }
        Optional<Long> retry = res.headers().firstValue("Retry-After").filter(v -> v.matches("[0-9]{1,6}")).map(Long::parseLong);
        return new Response(res.statusCode(), retry, body);
    }
}
