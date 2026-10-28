import java.nio.file.*;
public class Reader {
    public static void main(String[] a) throws Exception {
        Path p = Path.of(a[0]); long end = System.currentTimeMillis() + Long.parseLong(a[1]); int n = 0, err = 0;
        while (System.currentTimeMillis() < end) {
            try { if (a[2].equals("nio")) Files.readString(p); else try (var in = new java.io.FileInputStream(p.toFile())) { in.readAllBytes(); } n++; }
            catch (Exception e) { err++; }
        }
        System.out.println("leituras=" + n + " erros=" + err);
    }
}
