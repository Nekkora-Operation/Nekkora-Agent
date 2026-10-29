import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
/** Controle positivo do ISO-R: escuta TCP em loopback, UDP e Unix Domain Socket na pasta dada, por N segundos. */
public class Escuta {
    public static void main(String[] a) throws Exception {
        try (ServerSocketChannel tcp = ServerSocketChannel.open(); DatagramChannel udp = DatagramChannel.open();
             ServerSocketChannel uds = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            tcp.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            udp.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Path sock = Path.of(a[0], "controle.sock");
            Files.deleteIfExists(sock);
            uds.bind(UnixDomainSocketAddress.of(sock));
            System.out.println("pid=" + ProcessHandle.current().pid());
            System.out.flush();
            Thread.sleep(Long.parseLong(a[1]) * 1000);
        }
    }
}
