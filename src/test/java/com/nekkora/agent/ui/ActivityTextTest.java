package com.nekkora.agent.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.obs.ActivityView;
import com.nekkora.agent.obs.ActivityView.Completeness;
import com.nekkora.agent.obs.ActivityView.Gap;
import com.nekkora.agent.obs.ActivityView.Integrity;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A prévia da exportação e o resumo nunca chamam de completa uma linha do tempo parcial. */
class ActivityTextTest {

    @Test
    void exportPreviewNamesEveryGapWithItsCode() {
        String t = ActivityText.exportIntegrity(new Completeness(Integrity.INCOMPLETE, List.of(Gap.REJECTED, Gap.SUPERVISOR_LIMIT)));
        assertTrue(t.startsWith("ATENÇÃO: linha do tempo INCOMPLETA"), t);
        assertTrue(t.contains("(AGT-OBS-002)") && t.contains("(AGT-OBS-006)"), t);
        assertEquals("Linha do tempo completa.", ActivityText.exportIntegrity(new Completeness(Integrity.COMPLETE, List.of())));
        assertTrue(ActivityText.exportIntegrity(new Completeness(Integrity.IN_PROGRESS, List.of())).contains("em andamento"));
        assertTrue(ActivityText.exportIntegrity(new Completeness(Integrity.PENDING, List.of())).contains("não foi encerrada"));
    }

    @Test
    void everyGapHasATextAndACode() {
        for (Gap g : Gap.values()) assertTrue(ActivityText.gap(g).endsWith("(" + g.code() + ")"), g.name());
        for (Integrity i : Integrity.values()) {
            String s = ActivityText.integrity(new Completeness(i, i == Integrity.INCOMPLETE ? List.of(Gap.PURGED) : List.of()), 3);
            assertTrue(!s.isBlank() && (i != Integrity.COMPLETE || s.startsWith("Completa")), s);
            assertTrue(i == Integrity.COMPLETE || !s.startsWith("Completa"), "só COMPLETE diz 'Completa': " + s);
        }
        assertEquals(ActivityView.Integrity.PENDING, ActivityView.completeness(null, List.of()).integrity());
    }
}
