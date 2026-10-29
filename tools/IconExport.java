import com.nekkora.ui.brand.NekkoraLogo;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Gera o ícone .ico do executável a partir do ícone de app da biblioteca visual (NekkoraLogo), sem
 * arquivos de imagem copiados do Studio. Uso (pelo tools/package-windows.ps1):
 * {@code java -Djava.awt.headless=true -cp "target/lib/*" tools/IconExport.java saida.ico}
 */
public final class IconExport {
    private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    public static void main(String[] args) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (int s : SIZES) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(NekkoraLogo.appIcon(s), "png", b);
            pngs.add(b.toByteArray());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        writeShort(d, 0);
        writeShort(d, 1);
        writeShort(d, SIZES.length);
        int offset = 6 + 16 * SIZES.length;
        for (int i = 0; i < SIZES.length; i++) {
            int s = SIZES[i];
            d.writeByte(s >= 256 ? 0 : s);
            d.writeByte(s >= 256 ? 0 : s);
            d.writeByte(0);
            d.writeByte(0);
            writeShort(d, 1);
            writeShort(d, 32);
            writeInt(d, pngs.get(i).length);
            writeInt(d, offset);
            offset += pngs.get(i).length;
        }
        for (byte[] p : pngs) d.write(p);
        d.flush();
        Path target = Path.of(args[0]);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.write(target, out.toByteArray());
        System.out.println("ícone gravado: " + target + " (" + SIZES.length + " tamanhos)");
    }

    private static void writeShort(DataOutputStream d, int v) throws IOException {
        d.writeByte(v & 0xff);
        d.writeByte((v >> 8) & 0xff);
    }

    private static void writeInt(DataOutputStream d, int v) throws IOException {
        writeShort(d, v & 0xffff);
        writeShort(d, (v >> 16) & 0xffff);
    }
}
