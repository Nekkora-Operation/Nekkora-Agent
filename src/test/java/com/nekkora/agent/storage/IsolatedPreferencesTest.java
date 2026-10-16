package com.nekkora.agent.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

/**
 * D-05: no processo do Agent, {@link Preferences} é a fábrica em memória — o nó de tema que o
 * ThemeManager compartilharia com o Studio nunca chega ao registro do Windows.
 * (Surefire define a mesma propriedade que o {@code AgentMain} define ao iniciar.)
 */
class IsolatedPreferencesTest {

    @Test
    void preferencesAreInMemoryInsideTheAgentProcess() throws Exception {
        assertEquals(IsolatedPreferencesFactory.class.getName(), System.getProperty(IsolatedPreferencesFactory.PROPERTY));
        Preferences node = Preferences.userRoot().node("com/nekkora/ui/theme");
        assertTrue(node.getClass().getName().startsWith(IsolatedPreferencesFactory.class.getName()), node.getClass().getName());
        node.put("nekkora.theme", "teste-isolado");
        node.flush();
        assertEquals("teste-isolado", node.get("nekkora.theme", null));
    }
}
