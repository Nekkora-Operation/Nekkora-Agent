import com.nekkora.agent.exec.ExecutionPackage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AGENT-PLATFORM-001 (laboratório): monta o pacote de LABORATÓRIO no formato do Studio (manifesto 1.5.0, {@code tabular},
 * {@code --output}) cujo {@code main.py} ({@code tools/lab/scripts/obs_studio_main.py}, OBS-001) publica eventos REAIS do
 * {@code nekkora_rpa} 0.3.0, com dados fictícios, sem navegador, sem rede e sem segredo. Imprime a impressão do pacote
 * calculada pela FUNÇÃO DO PRÓPRIO AGENT ({@code ExecutionPackage.inspect}), sem reimplementação.
 * Uso: java -cp target/classes PacoteLab.java <pasta-nova> <mode> <items> <delay_ms>
 */
public class PacoteLab {
    public static void main(String[] a) throws Exception {
        Path dir = Path.of(a[0]);
        if (Files.exists(dir)) throw new IllegalStateException("pasta já existe");
        String config = "{\"mode\": \"" + a[1] + "\", \"items\": " + Integer.parseInt(a[2]) + ", \"delay_ms\": " + Integer.parseInt(a[3]) + "}\n";
        Path scripts = Path.of("tools", "lab", "scripts").toAbsolutePath();
        Map<String, String> content = new LinkedHashMap<>();
        content.put("main.py", Files.readString(scripts.resolve("obs_studio_main.py"), StandardCharsets.UTF_8));
        content.put("obs_config.json", config);
        content.put("automation/__init__.py", "\"\"\"pacote\"\"\"\n");
        content.put("config/execution.toml", "[runtime]\nenvironment = \"benchmark\"\n");
        StringBuilder files = new StringBuilder();
        for (Map.Entry<String, String> e : content.entrySet()) {
            Path f = dir.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
            byte[] norm = e.getValue().replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
            files.append(files.isEmpty() ? "" : ", ").append('"').append(e.getKey()).append("\": \"sha256:")
                    .append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(norm))).append('"');
        }
        String steps = "[{\"stepId\": \"f29666e3-4350-4d3b-9936-d90c84f44515\", \"traceId\": \"search.fill_search_term\", \"credential\": false}, "
                + "{\"stepId\": \"cc751e65-4d87-4ade-8f71-bcae4d223868\", \"traceId\": \"search.press_enter\", \"credential\": false}]";
        Files.writeString(dir.resolve("manifest.json"), "{\"schema\": \"nekkora-studio/codegen-manifest\", \"version\": \"1.5.0\", "
                + "\"framework\": \"nekkora-rpa[excel]==0.3.0\", \"execution\": \"tabular\", \"cli\": {\"options\": {\"output\": \"--output\"}}, "
                + "\"steps\": " + steps + ", \"files\": {" + files + "}}", StandardCharsets.UTF_8);
        ExecutionPackage pkg = ExecutionPackage.inspect(dir);
        System.out.println(pkg.fingerprint());
    }
}
