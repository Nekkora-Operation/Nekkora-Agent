package com.nekkora.agent.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.BuildInfo;
import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.core.ErrorJournal;
import com.nekkora.agent.core.ShortcutState;
import com.nekkora.agent.core.TrayState;
import com.nekkora.agent.storage.AgentDataDir;
import com.nekkora.agent.storage.ConfigStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** A11 (exportação): lista fechada de campos; nome da máquina só com a opção marcada. */
class DiagnosticReportTest {
    static final List<String> CLOSED_KEYS = List.of(
            "formato", "versaoFormato", "geradoEm",
            "aplicativo", "versao", "commit", "alteracoesLocais", "dataCommit", "designSystemCommit", "designSystemVersao", "flatlaf",
            "ambiente", "java", "fornecedorJava", "sistema", "versaoSistema", "arquitetura",
            "maquina", "nomeIncluido", "nome", "nomeDisponivel",
            "estado", "bandeja", "configuracao", "logs", "diretorioDados", "instanciaUnica",
            "integracao", "vinculo", "agentWorkerOrganizacao", "conexao", "licenca", "execucao",
            "atalhos", "studio", "platform",
            "erros", "codigo", "ocorrencias", "ultima",
            "sessao", "inicio", "duracaoSegundos", "inicializacaoMs");

    static DiagnosticReport.Facts facts(Optional<String> machine, List<ErrorJournal.Entry> errors) {
        Properties p = new Properties();
        p.setProperty("version", "0.1.0");
        p.setProperty("commit", "0123456789abcdef0123456789abcdef01234567");
        return new DiagnosticReport.Facts(BuildInfo.from(p), "21.0.12", "Microsoft", "Windows 11", "10.0", "amd64",
                machine, TrayState.ACTIVE, ConfigStore.State.LOADED, AgentLog.State.ACTIVE, AgentDataDir.Kind.DEFAULT, true,
                ShortcutState.VALID, ShortcutState.INVALID, errors, Instant.parse("2026-10-16T20:00:00Z"), 950L);
    }

    @Test
    void containsExactlyTheClosedFieldList() {
        ErrorJournal j = new ErrorJournal(Clock.fixed(Instant.parse("2026-10-16T20:01:00Z"), ZoneOffset.UTC));
        j.record(ErrorCode.CONFIG_WRITE_FAILED);
        j.record(ErrorCode.CONFIG_WRITE_FAILED);
        j.record(ErrorCode.TRAY_FAILED);
        String json = DiagnosticReport.toJson(facts(Optional.of("LAB-01"), j.snapshot()), true, Instant.parse("2026-10-16T20:05:00Z"));
        List<String> keys = new ArrayList<>();
        Matcher m = Pattern.compile("\"([A-Za-z]+)\":").matcher(json);
        while (m.find()) if (!keys.contains(m.group(1))) keys.add(m.group(1));
        assertEquals(CLOSED_KEYS, keys);
        assertTrue(json.contains("{ \"codigo\": \"AGT-CFG-005\", \"ocorrencias\": 2, \"ultima\": \"2026-10-16T20:01:00Z\" }"), json);
        assertTrue(json.contains("\"duracaoSegundos\": 300"));
        assertTrue(json.contains("\"inicializacaoMs\": 950"));
        assertTrue(json.contains("\"execucao\": \"INDISPONIVEL\""));
    }

    @Test
    void machineNameOnlyWhenExplicitlyIncluded() {
        DiagnosticReport.Facts f = facts(Optional.of("MAQUINA-SINTETICA-42"), List.of());
        String omitted = DiagnosticReport.toJson(f, false, Instant.parse("2026-10-16T20:05:00Z"));
        assertFalse(omitted.contains("MAQUINA-SINTETICA-42"));
        assertTrue(omitted.contains("\"nomeIncluido\": false"));
        assertTrue(omitted.contains("\"nome\": null"));
        String included = DiagnosticReport.toJson(f, true, Instant.parse("2026-10-16T20:05:00Z"));
        assertTrue(included.contains("\"nome\": \"MAQUINA-SINTETICA-42\""), "controle positivo da opção");

        String absent = DiagnosticReport.toJson(facts(Optional.empty(), List.of()), true, Instant.parse("2026-10-16T20:05:00Z"));
        assertTrue(absent.contains("\"nomeIncluido\": false"));
        assertTrue(absent.contains("\"nomeDisponivel\": false"));
    }

    @Test
    void freeTextFactsAreFiltered() {
        DiagnosticReport.Facts f = new DiagnosticReport.Facts(BuildInfo.from(new Properties()), "21\"}, \"x\": {", "Vendor\nInjetado",
                "Windows \"11\"", "10.0", "amd64\r\n", Optional.empty(), TrayState.ACTIVE, ConfigStore.State.MISSING, AgentLog.State.ACTIVE,
                AgentDataDir.Kind.OVERRIDE, true, ShortcutState.NOT_CONFIGURED, ShortcutState.NOT_CONFIGURED, List.of(),
                Instant.parse("2026-10-16T20:00:00Z"), null);
        String json = DiagnosticReport.toJson(f, false, Instant.parse("2026-10-16T20:00:00Z"));
        assertFalse(json.contains("\"x\""));
        assertFalse(json.contains("Vendor\n"));
        assertTrue(json.contains("\"inicializacaoMs\": null"));
        assertTrue(json.contains("\"commit\": \"desconhecido\""));
    }
}
