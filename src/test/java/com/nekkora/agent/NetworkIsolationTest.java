package com.nekkora.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * STUDIO-AGENT-LOCAL-001, testes de isolamento ANTES do canal local (D-SA-01). Enquanto a titular não aprovar o contrato,
 * a análise de ameaças e a matriz de autorização, o Agent não tem nenhuma forma de ESCUTAR nem de se conectar: nem rede,
 * nem loopback, nem Unix Domain Socket, nem pipe nomeado, nem depuração/JMX/RMI. O guarda antigo
 * ({@link ArchitectureTest#noNetworkOrShellUsageInProductionCode}) procurava o texto {@code java.net.Socket} e deixava
 * passar {@code ServerSocket}, {@code ServerSocketChannel}, {@code SocketChannel}, {@code DatagramSocket} e
 * {@code UnixDomainSocketAddress} (ISO-02 prova isso); este fecha as lacunas.
 */
class NetworkIsolationTest {
    static final Path BASE = Path.of(System.getProperty("agent.basedir", "."));
    static final Path SRC = BASE.resolve(Path.of("src", "main", "java", "com", "nekkora", "agent"));

    /** Formas proibidas no código de produção (import, nome qualificado ou uso pelo nome simples). */
    static final Map<String, String> FORBIDDEN = Map.ofEntries(
            Map.entry("ServerSocket", "escuta TCP (java.net.ServerSocket)"),
            Map.entry("ServerSocketChannel", "escuta TCP ou Unix Domain Socket (NIO)"),
            Map.entry("AsynchronousServerSocketChannel", "escuta assíncrona"),
            Map.entry("SocketChannel", "conexão TCP ou Unix Domain Socket (NIO)"),
            Map.entry("AsynchronousSocketChannel", "conexão assíncrona"),
            Map.entry("java.net.Socket", "conexão TCP"),
            Map.entry("new Socket(", "conexão TCP"),
            Map.entry("DatagramSocket", "UDP"),
            Map.entry("DatagramChannel", "UDP (NIO)"),
            Map.entry("MulticastSocket", "UDP multicast"),
            Map.entry("UnixDomainSocketAddress", "Unix Domain Socket"),
            Map.entry("StandardProtocolFamily", "família de protocolo de socket"),
            Map.entry("InetSocketAddress", "endereço de rede"),
            Map.entry("InetAddress", "endereço de rede"),
            Map.entry("\\\\.\\pipe", "pipe nomeado do Windows"),
            Map.entry("\\\\\\\\.\\\\pipe", "pipe nomeado do Windows (escapado)"),
            Map.entry("java.rmi", "RMI"),
            Map.entry("javax.management.remote", "JMX remoto"),
            Map.entry("com.sun.net.httpserver", "servidor HTTP"),
            Map.entry("java.net.http", "cliente HTTP"),
            Map.entry("HttpURLConnection", "cliente HTTP"),
            Map.entry("openConnection", "conexão por URL"),
            Map.entry("import java.net.*", "importação curinga de rede"),
            Map.entry("import java.nio.channels.*", "importação curinga de canais"));

    /** Módulos que dariam porta de depuração, JMX, RMI, HTTP ou JNDI à imagem empacotada. */
    static final Set<String> FORBIDDEN_MODULES = Set.of("jdk.jdwp.agent", "jdk.management.agent", "java.management.rmi", "java.rmi", "jdk.httpserver",
            "java.net.http", "java.naming", "jdk.naming.rmi", "jdk.net", "jdk.sctp");

    static List<String> scan(String name, String text) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : FORBIDDEN.entrySet()) {
            if (text.contains(e.getKey())) out.add(name + ": " + e.getValue() + " (" + e.getKey() + ")");
        }
        return out;
    }

    @Test
    @DisplayName("ISO-01: nenhum código de produção escuta ou se conecta (TCP, UDP, loopback, Unix Domain Socket, pipe, RMI, JMX, HTTP)")
    void productionCodeHasNoChannel() throws IOException {
        List<String> violations = new ArrayList<>();
        int files = 0;
        try (Stream<Path> s = Files.walk(SRC)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                files++;
                violations.addAll(scan(SRC.relativize(f).toString(), Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        assertTrue(files > 50, "o guarda leu o código de produção (" + files + " arquivos)");
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    @DisplayName("ISO-02: o guarda pega cada forma proibida; o guarda antigo deixava passar escuta por canal NIO e Unix Domain Socket")
    void guardCatchesEveryForm() {
        Map<String, String> samples = Map.of(
                "tcp-escuta", "var s = ServerSocketChannel.open(); s.bind(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));",
                "uds-escuta", "var s = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX); s.bind(UnixDomainSocketAddress.of(p));",
                "uds-conexao", "var c = SocketChannel.open(UnixDomainSocketAddress.of(p));",
                "tcp-classico", "try (var s = new java.net.ServerSocket(0)) {}",
                "udp", "var d = new DatagramSocket(9999);",
                "pipe", "Files.newByteChannel(Path.of(\"\\\\\\\\.\\\\pipe\\\\nekkora\"));",
                "curinga", "import java.net.*;\nclass X {}",
                "jmx", "import javax.management.remote.JMXConnectorServer;",
                "http", "import com.sun.net.httpserver.HttpServer;");
        List<String> oldList = List.of("java.net.http", "HttpURLConnection", "java.net.Socket", "URL(", "openConnection", "InetAddress");
        int missedByOld = 0;
        for (Map.Entry<String, String> e : samples.entrySet()) {
            assertFalse(scan(e.getKey(), e.getValue()).isEmpty(), "o guarda novo pega: " + e.getKey());
            if (oldList.stream().noneMatch(e.getValue()::contains)) missedByOld++;
        }
        // só a escuta TCP com InetAddress era pega; UDS (escuta e conexão), ServerSocket clássico, UDP, pipe, curinga, JMX e servidor HTTP passavam
        assertEquals(8, missedByOld, "lacunas do guarda antigo documentadas");
        assertTrue(scan("ok", "Files.readString(path); ProcessHandle.current().pid();").isEmpty(), "sem falso positivo no código comum");
    }

    @Test
    @DisplayName("ISO-03: o pacote do canal local ainda não existe (só depois da aprovação do contrato, das ameaças e da matriz)")
    void noLocalChannelYet() {
        assertFalse(Files.exists(SRC.resolve("local")), "com.nekkora.agent.local só depois da revisão (D-SA-01)");
    }

    @Test
    @DisplayName("ISO-04: o empacotamento não liga depuração, JMX nem agentes; a imagem montada não tem módulos de rede remota")
    void packagingOpensNothing() throws IOException {
        String script = Files.readString(BASE.resolve("tools").resolve("package-windows.ps1"), StandardCharsets.UTF_8);
        for (String flag : List.of("jdwp", "-agentlib", "-javaagent", "jmxremote", "-Xdebug", "-Xrunjdwp", "rmi.server")) {
            assertFalse(script.contains(flag), "opção de JVM proibida no empacotamento: " + flag);
        }
        Path release = BASE.resolve(Path.of("target", "dist", "Nekkora Agent", "runtime", "release"));
        Assumptions.assumeTrue(Files.isRegularFile(release), "imagem empacotada ainda não montada neste checkout");
        String modules = Files.readAllLines(release, StandardCharsets.UTF_8).stream().filter(l -> l.startsWith("MODULES=")).findFirst().orElse("");
        assertFalse(modules.isEmpty());
        for (String m : FORBIDDEN_MODULES) assertFalse(List.of(modules.replace("MODULES=", "").replace("\"", "").split(" ")).contains(m), m);
    }

    @Test
    @DisplayName("ISO-05: o roteiro de verificação em execução real existe e cobre TCP, UDP, Unix Domain Socket e pipe nomeado do processo")
    void runtimeScriptCoversEveryChannel() throws IOException {
        String ps = Files.readString(BASE.resolve(Path.of("tools", "isolation", "verificar-isolamento.ps1")), StandardCharsets.UTF_8);
        for (String must : List.of("Get-NetTCPConnection", "Get-NetUDPEndpoint", "\\\\.\\pipe\\", "ReparsePoint", "OwningProcess")) {
            assertTrue(ps.contains(must), "o roteiro confere " + must);
        }
    }
}
