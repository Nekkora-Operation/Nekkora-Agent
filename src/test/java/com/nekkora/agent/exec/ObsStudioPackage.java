package com.nekkora.agent.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OBS-001: pacote no FORMATO do Studio (manifesto 1.5.0, {@code tabular}, {@code --output}) cujo {@code main.py}
 * ({@code tools/lab/scripts/obs_studio_main.py}) publica eventos REAIS do Framework pelo {@code EventLogger} do
 * {@code nekkora_rpa} 0.3.0. Dois passos do manifesto têm {@code traceId} → {@code stepId} (correspondência G7).
 */
public final class ObsStudioPackage {
    private ObsStudioPackage() {}

    public static final String FILL_STEP_ID = "f29666e3-4350-4d3b-9936-d90c84f44515";
    public static final Path SCRIPTS = Path.of("tools", "lab", "scripts").toAbsolutePath().normalize();

    public static Path create(Path dir, String config) throws IOException {
        Files.createDirectories(dir);
        Map<String, String> content = new LinkedHashMap<>();
        content.put("main.py", Files.readString(SCRIPTS.resolve("obs_studio_main.py"), StandardCharsets.UTF_8));
        content.put("obs_config.json", config);
        content.put("automation/__init__.py", "\"\"\"pacote\"\"\"\n");
        content.put("config/execution.toml", "[runtime]\nenvironment = \"benchmark\"\n");
        StringBuilder files = new StringBuilder();
        for (Map.Entry<String, String> e : content.entrySet()) {
            Path f = dir.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
            files.append(files.isEmpty() ? "" : ", ").append('"').append(e.getKey()).append("\": \"")
                    .append(ExecutionPackage.hash(e.getValue().getBytes(StandardCharsets.UTF_8))).append('"');
        }
        String steps = "[{\"stepId\": \"" + FILL_STEP_ID + "\", \"traceId\": \"search.fill_search_term\", \"credential\": false}, "
                + "{\"stepId\": \"cc751e65-4d87-4ade-8f71-bcae4d223868\", \"traceId\": \"search.press_enter\", \"credential\": false}]";
        Files.writeString(dir.resolve("manifest.json"), "{\"schema\": \"nekkora-studio/codegen-manifest\", \"version\": \"1.5.0\", "
                + "\"framework\": \"nekkora-rpa[excel]==0.3.0\", \"execution\": \"tabular\", \"cli\": {\"options\": {\"output\": \"--output\"}}, "
                + "\"steps\": " + steps + ", \"files\": {" + files + "}}", StandardCharsets.UTF_8);
        return dir;
    }
}
