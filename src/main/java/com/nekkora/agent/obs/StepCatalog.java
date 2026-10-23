package com.nekkora.agent.obs;

import com.nekkora.agent.exec.ExecRefused;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.JsonReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Correspondência {@code step_id} do Framework → {@code studioStepId}, lida do manifesto da CÓPIA CONFERIDA do pacote
 * ({@code <execução>/pacote/manifest.json}): no manifesto do Studio, {@code steps[].traceId} é o {@code step_id} que o
 * código gerado informa, e {@code steps[].stepId} é o identificador do passo no Studio. Só vale quando é confiável: a
 * cópia confere com a impressão digital registrada da execução, o {@code traceId} é único e os dois têm formato válido.
 * Sem manifesto, de versão anterior ou com divergência: catálogo vazio — o {@code step_id} aparece como está, a
 * execução não é afetada.
 */
final class StepCatalog {
    static final StepCatalog EMPTY = new StepCatalog(Map.of(), false);

    private final Map<String, String> byTrace;
    private final boolean trusted;

    private StepCatalog(Map<String, String> byTrace, boolean trusted) {
        this.byTrace = Map.copyOf(byTrace);
        this.trusted = trusted;
    }

    Optional<String> studioStepId(String stepId) {
        return Optional.ofNullable(byTrace.get(stepId));
    }

    boolean trusted() {
        return trusted;
    }

    int size() {
        return byTrace.size();
    }

    static StepCatalog load(Path copy, String expectedFingerprint) {
        try {
            Path manifest = copy.resolve(ExecutionPackage.STUDIO_MANIFEST);
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return EMPTY;
            ExecutionPackage pkg = ExecutionPackage.inspect(copy);
            if (pkg.kind() != ExecutionPackage.Kind.STUDIO_PYTHON || !pkg.fingerprint().equals(expectedFingerprint)) return EMPTY;
            Map<String, Object> m = JsonReader.object(Files.readString(manifest, StandardCharsets.UTF_8));
            List<?> steps = JsonReader.list(m, "steps");
            if (steps == null) return EMPTY;
            Map<String, String> out = new HashMap<>();
            Set<String> repeated = new HashSet<>();
            for (Object o : steps) {
                if (!(o instanceof Map<?, ?> s) || !(s.get("traceId") instanceof String trace) || !(s.get("stepId") instanceof String id)) continue;
                if (!ActivityEvent.STEP.matcher(trace).matches() || !ActivityEvent.UUID.matcher(id).matches()) continue;
                if (out.putIfAbsent(trace, id) != null) repeated.add(trace);
            }
            repeated.forEach(out::remove); // ambíguo: nenhuma correspondência é melhor que uma falsa
            return new StepCatalog(out, true);
        } catch (ExecRefused | IOException | JsonReader.Invalid | RuntimeException e) {
            return EMPTY;
        }
    }
}
