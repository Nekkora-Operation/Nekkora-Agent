package com.nekkora.agent.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * {@link PreferencesFactory} em memória para o processo do Agent.
 *
 * <p>O {@code ThemeManager} da biblioteca visual pode ler e gravar o tema em
 * {@code Preferences.userNodeForPackage(ThemeManager.class)} — no Windows, um nó do registro
 * compartilhado com o Nekkora Studio. O Agent guarda o tema na própria configuração e instala esta
 * fábrica antes de qualquer uso de {@code Preferences}: nenhuma preferência do Studio é lida ou
 * alterada por acidente (decisão D-05).
 */
public final class IsolatedPreferencesFactory implements PreferencesFactory {
    public static final String PROPERTY = "java.util.prefs.PreferencesFactory";

    private static final Preferences USER = new Node(null, "");
    private static final Preferences SYSTEM = new Node(null, "");

    /** Deve ser chamado antes de qualquer acesso a {@link Preferences}. */
    public static void install() {
        System.setProperty(PROPERTY, IsolatedPreferencesFactory.class.getName());
    }

    @Override
    public Preferences userRoot() {
        return USER;
    }

    @Override
    public Preferences systemRoot() {
        return SYSTEM;
    }

    private static final class Node extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Node> children = new HashMap<>();

        Node(Node parent, String name) {
            super(parent, name);
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
            values.clear();
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return children.keySet().toArray(String[]::new);
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, n -> new Node(this, n));
        }

        @Override
        protected void syncSpi() {
            // só memória
        }

        @Override
        protected void flushSpi() {
            // só memória
        }
    }
}
