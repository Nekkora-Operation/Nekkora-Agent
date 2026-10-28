package com.nekkora.agent.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O cliente HTTPS de produção contra um servidor HTTPS REAL local (só em teste; o código de produção nunca escuta). O
 * certificado de teste é gerado aqui pelo {@code keytool} do JDK. Prova que a validação TLS não foi relaxada: sem confiar
 * no certificado, ou com o nome errado, NÃO há conexão.
 */
class JdkHttpTransportTest {
    @TempDir
    static Path dir;
    static HttpsServer server;
    static SSLContext trusting;
    static int port;
    static final AtomicInteger hits = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        Path ks = dir.resolve("servidor.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "s", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-validity", "2", "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", "teste-local",
                "-keypass", "teste-local").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "keytool");
        KeyStore k = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            k.load(in, "teste-local".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(k, "teste-local".toCharArray());
        SSLContext serverSsl = SSLContext.getInstance("TLS");
        serverSsl.init(kmf.getKeyManagers(), null, null);
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("s", k.getCertificate("s"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        trusting = SSLContext.getInstance("TLS");
        trusting.init(null, tmf.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverSsl));
        server.createContext("/ok", ex -> {
            hits.incrementAndGet();
            boolean contract = "1.0".equals(ex.getRequestHeaders().getFirst("Nekkora-Agent-Contract"));
            byte[] b = ("{\"contract\":" + contract + "}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Retry-After", "12");
            ex.sendResponseHeaders(429, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.createContext("/redirect", ex -> {
            hits.incrementAndGet();
            ex.getResponseHeaders().add("Location", "https://localhost:" + port + "/ok");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/big", ex -> {
            hits.incrementAndGet();
            byte[] b = new byte[JdkHttpTransport.MAX_RESPONSE + 10];
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop(0);
    }

    private static HttpTransport.Request get(String url) {
        return new HttpTransport.Request("POST", URI.create(url), Map.of("Nekkora-Agent-Contract", "1.0", "Content-Type", "application/json"),
                "{}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("H1: com o certificado confiável: resposta, status e Retry-After lidos; cabeçalho do contrato enviado")
    void trustedCertificate() throws Exception {
        HttpTransport.Response r = new JdkHttpTransport(trusting).send(get("https://localhost:" + port + "/ok"));
        assertEquals(429, r.status());
        assertEquals(Optional.of(12L), r.retryAfterSeconds());
        assertArrayEquals("{\"contract\":true}".getBytes(StandardCharsets.UTF_8), r.body());
    }

    @Test
    @DisplayName("H2: certificado não confiável (contexto padrão) e nome divergente (127.0.0.1): sem conexão — nada é relaxado")
    void validationIsNotRelaxed() {
        int before = hits.get();
        assertThrows(IOException.class, () -> new JdkHttpTransport().send(get("https://localhost:" + port + "/ok")));
        assertThrows(IOException.class, () -> new JdkHttpTransport(trusting).send(get("https://127.0.0.1:" + port + "/ok")));
        assertEquals(before, hits.get(), "nenhum pedido chegou ao servidor");
    }

    @Test
    @DisplayName("H3: redirecionamento nunca é seguido; http é recusado; resposta acima do limite é falha de transporte")
    void redirectsHttpAndLimits() throws Exception {
        int before = hits.get();
        HttpTransport.Response r = new JdkHttpTransport(trusting).send(get("https://localhost:" + port + "/redirect"));
        assertEquals(302, r.status());
        assertEquals(before + 1, hits.get(), "o destino do redirecionamento não foi chamado");
        assertThrows(IOException.class, () -> new JdkHttpTransport(trusting).send(get("http://localhost:" + port + "/ok")));
        assertThrows(IOException.class, () -> new JdkHttpTransport(trusting).send(get("https://localhost:" + port + "/big")));
        assertTrue(List.of(302).contains(r.status()));
    }
}
