package com.nekkora.agent.labfaults;

import com.nekkora.agent.core.FaultPoints;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EXCLUSIVO DE LABORATÓRIO (AGENT-PYTHON-001). Fica fora de {@code src/main}, vira {@code nekkora-agent-lab-falhas.jar} e
 * nunca entra na distribuição. Só age com o JAR no classpath da cópia de laboratório E com a propriedade
 * {@code nekkora.agent.lab.falha} definida:
 * <ul>
 *   <li>{@code quedaAposStarting}: em {@link FaultPoints.Point#STARTING_SAVED_BEFORE_PROCESS}, grava o registro e derruba a JVM
 *       ({@code halt(86)}): STARTING gravado, processo Python ainda NÃO criado;</li>
 *   <li>{@code perderConfirmacaoDecisao}: em {@link FaultPoints.Point#DECISION_RESPONSE_RECEIVED}, descarta UMA vez a resposta
 *       de uma decisão que a Platform já gravou.</li>
 * </ul>
 * O registro ({@code nekkora.agent.lab.registro}, uma linha por falha) diz qual ponto foi atingido e quando.
 */
public final class LabFaults implements FaultPoints.Hook {
    private final String fault = System.getProperty("nekkora.agent.lab.falha", "");
    private final AtomicBoolean lostOnce = new AtomicBoolean();

    @Override
    public boolean reached(FaultPoints.Point point) {
        if ("quedaAposStarting".equals(fault) && point == FaultPoints.Point.STARTING_SAVED_BEFORE_PROCESS) {
            record(point, "halt(86)");
            Runtime.getRuntime().halt(86);
        }
        if ("perderConfirmacaoDecisao".equals(fault) && point == FaultPoints.Point.DECISION_RESPONSE_RECEIVED && lostOnce.compareAndSet(false, true)) {
            record(point, "resposta descartada");
            return true;
        }
        return false;
    }

    private void record(FaultPoints.Point point, String action) {
        String file = System.getProperty("nekkora.agent.lab.registro");
        if (file == null || file.isBlank()) return;
        String line = Instant.now() + "\t" + fault + "\t" + point + "\t" + action + System.lineSeparator();
        try {
            Files.writeString(Path.of(file), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                    StandardOpenOption.SYNC);
        } catch (IOException e) {
            // laboratório: sem registro, a falha ainda acontece
        }
    }
}
