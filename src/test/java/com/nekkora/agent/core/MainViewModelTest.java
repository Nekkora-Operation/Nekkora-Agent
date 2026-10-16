package com.nekkora.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** A09 (nome ausente) e A15 (nenhum texto sugere pareamento, licença ativa ou execução disponível). */
class MainViewModelTest {

    private static final BuildInfo BUILD = BuildInfo.from(props("version", "0.1.0", "commitShort", "abc123def456", "dirty", "false"));
    private static final MainViewModel.ShortcutView NONE = new MainViewModel.ShortcutView(ShortcutState.NOT_CONFIGURED, "destino não configurado", null);

    @Test
    void machineNameComesOnlyFromTheEnvironment() {
        assertEquals(Optional.of("LAB-01"), MachineName.resolve(Map.of("COMPUTERNAME", " LAB-01 ")));
        assertEquals(Optional.of("host-linux"), MachineName.resolve(Map.of("HOSTNAME", "host-linux")));
        assertEquals(Optional.empty(), MachineName.resolve(Map.of()));
        assertEquals(Optional.empty(), MachineName.resolve(Map.of("COMPUTERNAME", "   ")));
        assertEquals(Optional.empty(), MachineName.resolve(Map.of("COMPUTERNAME", "LAB\n01")));
    }

    @Test
    void missingMachineNameIsShownAsNotAvailable() {
        MainViewModel vm = model(Optional.empty(), TrayState.ACTIVE, null);
        assertEquals("Não disponível", vm.machine());
    }

    @Test
    void integrationStatesAreTheFixedNotAvailableTexts() {
        MainViewModel vm = model(Optional.of("LAB-01"), TrayState.ACTIVE, null);
        assertEquals(List.of("Não pareado", "Não vinculado", "Sem conexão com a Platform", "Não verificada", "Execução indisponível nesta versão"),
                vm.integration().stream().map(IntegrationStatus::value).toList());
        assertTrue(IntegrationStatus.EXPLANATION.contains("ainda não está disponível nesta versão"));
    }

    @Test
    void noVisibleTextSuggestsWorkingIntegration() {
        for (TrayState tray : TrayState.values()) {
            for (ErrorCode cfg : new ErrorCode[] {null, ErrorCode.CONFIG_CORRUPTED}) {
                MainViewModel vm = model(Optional.of("LAB-01"), tray, cfg);
                for (String text : texts(vm)) {
                    String t = text.toLowerCase(Locale.ROOT);
                    assertFalse(t.matches(".*\\bpronto\\b.*"), text);
                    assertFalse(t.contains("pareado") && !t.contains("não pareado"), text);
                    assertFalse(t.contains("conectado") && !t.contains("não conectado"), text);
                    assertFalse(t.contains("licença ativa") || t.contains("licenciado"), text);
                    assertFalse(t.matches(".*execução (disponível|habilitada|ativa).*"), text);
                    assertFalse(t.matches(".*(agent|worker)_?id.*"), text);
                }
            }
        }
    }

    @Test
    void trayProblemsAreExplainedAndCloseHintFollowsTheMode() {
        MainViewModel active = model(Optional.of("X"), TrayState.ACTIVE, null);
        assertTrue(active.notices().isEmpty());
        assertTrue(active.closeHint().contains("bandeja"));
        for (TrayState s : List.of(TrayState.UNSUPPORTED, TrayState.FAILED, TrayState.DISABLED)) {
            MainViewModel vm = model(Optional.of("X"), s, null);
            assertEquals(1, vm.notices().size());
            assertTrue(vm.notices().get(0).message().contains(s.code().code()));
            assertTrue(vm.notices().get(0).message().contains("Sair"), "modo sem bandeja oferece saída explícita");
            assertTrue(vm.closeHint().contains("barra de tarefas"));
        }
    }

    @Test
    void configProblemNoticeSaysTheFileWasPreserved() {
        MainViewModel vm = model(Optional.of("X"), TrayState.ACTIVE, ErrorCode.CONFIG_UNSUPPORTED);
        MainViewModel.Notice n = vm.notices().get(0);
        assertTrue(n.message().contains("AGT-CFG-003"));
        assertTrue(n.message().contains("preservado"));
    }

    @Test
    void unavailableShortcutsShowTheReasonAsText() {
        MainViewModel vm = model(Optional.of("X"), TrayState.ACTIVE, null);
        assertFalse(vm.studio().enabled());
        assertEquals("Indisponível: destino não configurado", vm.studio().detail());
        MainViewModel valid = MainViewModel.from(new MainViewModel.Inputs(Optional.of("X"), BUILD, TrayState.ACTIVE, null, false,
                new MainViewModel.ShortcutView(ShortcutState.VALID, null, "Nekkora Studio.exe"), NONE));
        assertTrue(valid.studio().enabled());
        assertEquals("Abre Nekkora Studio.exe", valid.studio().detail());
    }

    @Test
    void unknownBuildValuesAreNeverInvented() {
        BuildInfo b = BuildInfo.from(props("version", "0.1.0", "commit", "${git.commit.id.full}"));
        assertEquals(BuildInfo.UNKNOWN, b.commit());
        assertEquals("build sem identificação de commit", b.buildLabel());
        assertEquals("abc123def456", BUILD.buildLabel());
    }

    private static MainViewModel model(Optional<String> machine, TrayState tray, ErrorCode config) {
        return MainViewModel.from(new MainViewModel.Inputs(machine, BUILD, tray, config, false, NONE, NONE));
    }

    private static List<String> texts(MainViewModel vm) {
        List<String> all = new ArrayList<>(List.of(vm.machine(), vm.version(), vm.build(), vm.closeHint(),
                vm.studio().label(), vm.studio().detail(), vm.platform().label(), vm.platform().detail(), IntegrationStatus.EXPLANATION));
        for (IntegrationStatus s : vm.integration()) {
            all.add(s.label());
            all.add(s.value());
        }
        for (MainViewModel.Notice n : vm.notices()) {
            all.add(n.title());
            all.add(n.message());
        }
        return all;
    }

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        return p;
    }
}
