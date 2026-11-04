package com.nekkora.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.nekkora.agent.core.FaultPoints;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * AGENT-PYTHON-001: a distribuição normal não provoca falha. O código que derruba o Agent ou descarta uma resposta mora só em
 * {@code tools/lab-faults} (artefato separado); {@code src/main} tem apenas os pontos nomeados, sem efeito.
 */
class LabFaultsAbsentTest {
    @Test
    void codigoDistribuidoNaoTemQuedaNemPropriedadeDeFalhaNemImplementacao() throws Exception {
        List<String> offenders;
        try (Stream<Path> s = Files.walk(Path.of("src/main"))) {
            offenders = s.filter(Files::isRegularFile).filter(p -> {
                try {
                    String t = Files.readString(p, StandardCharsets.UTF_8);
                    return t.contains(".halt(") || t.contains("nekkora.agent.lab.falha") || t.contains("nekkora.agent.lab.queda")
                            || t.contains("nekkora.agent.lab.perder") || t.contains("implements FaultPoints.Hook") || t.contains("labfaults");
                } catch (java.io.IOException e) {
                    return true;
                }
            }).map(Path::toString).toList();
        }
        assertEquals(List.of(), offenders);
        assertFalse(Files.exists(Path.of("src/main/resources/META-INF/services/com.nekkora.agent.core.FaultPoints$Hook")));
    }

    @Test
    void semArtefatoDeLaboratorioOsPontosNaoFazemNada() {
        FaultPoints.replaceForTests(null);
        for (FaultPoints.Point p : FaultPoints.Point.values()) assertFalse(FaultPoints.reached(p), p.name());
    }
}
