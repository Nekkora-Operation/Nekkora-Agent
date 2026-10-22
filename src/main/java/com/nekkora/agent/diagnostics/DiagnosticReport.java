package com.nekkora.agent.diagnostics;

import com.nekkora.agent.core.BuildInfo;
import com.nekkora.agent.core.ErrorJournal;
import com.nekkora.agent.core.IntegrationStatus;
import com.nekkora.agent.core.SafeText;
import com.nekkora.agent.core.ShortcutState;
import com.nekkora.agent.core.TrayState;
import com.nekkora.agent.storage.AgentDataDir;
import com.nekkora.agent.storage.ConfigStore;
import com.nekkora.agent.storage.StrictJson;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Diagnóstico exportável com lista FECHADA de campos (decisão D-08). Tudo o que entra aqui é
 * enumerado, número, versão filtrada ou código de erro; o nome da máquina só entra quando a pessoa
 * marca explicitamente a opção. Não há caminhos, endereços, conteúdo de configuração nem mensagens
 * de exceção. Nada é enviado: o arquivo é gravado onde a pessoa escolher.
 */
public final class DiagnosticReport {

    public static final String FORMAT = "nekkora-agent/diagnostico";
    public static final int FORMAT_VERSION = 1;

    /** Descrição, para a interface, do que o arquivo contém. */
    public static final List<String> CONTENTS = List.of(
            "Versão do Agent, commit do build e origem das bibliotecas visuais",
            "Versão do Java, nome, versão e arquitetura do sistema operacional",
            "Situação da bandeja, da configuração, dos logs, da trava e do diretório de dados (somente estados)",
            "Estados de integração desta versão e situação dos atalhos (sem os destinos)",
            "Códigos de erro desta sessão, com contagem e horário",
            "Início da sessão, duração e tempo de inicialização",
            "Execução local: estado e código da última, tipo do pacote, tempos e contagens (sem nome do pacote, caminhos, entradas "
                    + "ou saída do processo)",
            "Nome da máquina somente se a opção abaixo estiver marcada");

    /** Fatos coletados pela aplicação. Valores textuais livres são filtrados aqui. */
    public record Facts(
            BuildInfo build,
            String javaVersion,
            String javaVendor,
            String osName,
            String osVersion,
            String osArch,
            Optional<String> machineName,
            TrayState tray,
            ConfigStore.State config,
            AgentLog.State logs,
            AgentDataDir.Kind dataDir,
            boolean lockHeld,
            ShortcutState studio,
            ShortcutState platform,
            List<ErrorJournal.Entry> errors,
            Instant sessionStart,
            Long startupMillis,
            ExecFacts execution) {

        public Facts {
            Objects.requireNonNull(build);
            Objects.requireNonNull(machineName);
            errors = List.copyOf(errors);
            execution = execution == null ? ExecFacts.NONE : execution;
        }

        /** Sem dados de execução local (compatível com a AGENT-001). */
        public Facts(BuildInfo build, String javaVersion, String javaVendor, String osName, String osVersion, String osArch, Optional<String> machineName,
                TrayState tray, ConfigStore.State config, AgentLog.State logs, AgentDataDir.Kind dataDir, boolean lockHeld, ShortcutState studio,
                ShortcutState platform, List<ErrorJournal.Entry> errors, Instant sessionStart, Long startupMillis) {
            this(build, javaVersion, javaVendor, osName, osVersion, osArch, machineName, tray, config, logs, dataDir, lockHeld, studio, platform, errors,
                    sessionStart, startupMillis, ExecFacts.NONE);
        }
    }

    /**
     * DESKTOP-001 — execução local, só valores enumerados e números: estado e código da última (ou da ativa), tipo do pacote,
     * duração, suspensões, bloqueios de tela, se foi retomada depois de reinício, quantos registros estão guardados e o estado
     * da sessão. Nunca o nome do pacote, caminhos, entradas, PID ou saída do processo.
     */
    public record ExecFacts(String state, String code, String packageKind, boolean running, Long elapsedMillis, int suspensions, int locks,
                            boolean adopted, int records, String session) {
        public static final ExecFacts NONE = new ExecFacts("NENHUMA", null, null, false, null, 0, 0, false, 0, "NAO_VERIFICADA");
    }

    private DiagnosticReport() {}

    public static String toJson(Facts f, boolean includeMachineName, Instant generatedAt) {
        BuildInfo b = f.build();
        StringBuilder j = new StringBuilder(1024);
        j.append("{\n");
        kv(j, 1, "formato", q(FORMAT), true);
        kv(j, 1, "versaoFormato", String.valueOf(FORMAT_VERSION), true);
        kv(j, 1, "geradoEm", q(generatedAt.toString()), true);
        open(j, 1, "aplicativo");
        kv(j, 2, "versao", q(b.version()), true);
        kv(j, 2, "commit", q(b.commit()), true);
        kv(j, 2, "alteracoesLocais", q(b.dirty()), true);
        kv(j, 2, "dataCommit", q(b.commitTime()), true);
        kv(j, 2, "designSystemCommit", q(b.designSystemCommit()), true);
        kv(j, 2, "designSystemVersao", q(b.designSystemVersion()), true);
        kv(j, 2, "flatlaf", q(b.flatlafVersion()), false);
        close(j, 1, true);
        open(j, 1, "ambiente");
        kv(j, 2, "java", q(SafeText.token(f.javaVersion(), 40)), true);
        kv(j, 2, "fornecedorJava", q(SafeText.label(f.javaVendor(), 60)), true);
        kv(j, 2, "sistema", q(SafeText.label(f.osName(), 60)), true);
        kv(j, 2, "versaoSistema", q(SafeText.label(f.osVersion(), 40)), true);
        kv(j, 2, "arquitetura", q(SafeText.token(f.osArch(), 20)), false);
        close(j, 1, true);
        open(j, 1, "maquina");
        boolean include = includeMachineName && f.machineName().isPresent();
        kv(j, 2, "nomeIncluido", String.valueOf(include), true);
        kv(j, 2, "nome", include ? q(f.machineName().get()) : "null", true);
        kv(j, 2, "nomeDisponivel", String.valueOf(f.machineName().isPresent()), false);
        close(j, 1, true);
        open(j, 1, "estado");
        kv(j, 2, "bandeja", q(f.tray().diagnosticValue()), true);
        kv(j, 2, "configuracao", q(f.config().diagnosticValue()), true);
        kv(j, 2, "logs", q(f.logs().diagnosticValue()), true);
        kv(j, 2, "diretorioDados", q(f.dataDir().diagnosticValue()), true);
        kv(j, 2, "instanciaUnica", q(f.lockHeld() ? "PROPRIETARIA" : "SEM_TRAVA"), false);
        close(j, 1, true);
        open(j, 1, "integracao");
        IntegrationStatus[] all = IntegrationStatus.values();
        for (int i = 0; i < all.length; i++) {
            kv(j, 2, key(all[i]), q(all[i].diagnosticValue()), i < all.length - 1);
        }
        close(j, 1, true);
        open(j, 1, "atalhos");
        kv(j, 2, "studio", q(f.studio().diagnosticValue()), true);
        kv(j, 2, "platform", q(f.platform().diagnosticValue()), false);
        close(j, 1, true);
        indent(j, 1).append("\"erros\": [");
        List<ErrorJournal.Entry> errors = f.errors();
        for (int i = 0; i < errors.size(); i++) {
            ErrorJournal.Entry e = errors.get(i);
            j.append(i == 0 ? "\n" : ",\n");
            indent(j, 2).append("{ \"codigo\": ").append(q(e.code().code()))
                    .append(", \"ocorrencias\": ").append(e.count())
                    .append(", \"ultima\": ").append(q(e.last().toString())).append(" }");
        }
        j.append(errors.isEmpty() ? "],\n" : "\n" + "  ".repeat(1) + "],\n");
        open(j, 1, "sessao");
        kv(j, 2, "inicio", q(f.sessionStart().toString()), true);
        kv(j, 2, "duracaoSegundos", String.valueOf(Math.max(0, Duration.between(f.sessionStart(), generatedAt).toSeconds())), true);
        kv(j, 2, "inicializacaoMs", f.startupMillis() == null ? "null" : String.valueOf(f.startupMillis()), false);
        close(j, 1, true);
        ExecFacts x = f.execution();
        open(j, 1, "execucaoLocal");
        kv(j, 2, "estado", q(SafeText.token(x.state(), 30)), true);
        kv(j, 2, "codigo", x.code() == null ? "null" : q(SafeText.token(x.code(), 20)), true);
        kv(j, 2, "tipoPacote", x.packageKind() == null ? "null" : q(SafeText.token(x.packageKind(), 30)), true);
        kv(j, 2, "emAndamento", String.valueOf(x.running()), true);
        kv(j, 2, "duracaoMs", x.elapsedMillis() == null ? "null" : String.valueOf(x.elapsedMillis()), true);
        kv(j, 2, "suspensoes", String.valueOf(x.suspensions()), true);
        kv(j, 2, "bloqueiosTela", String.valueOf(x.locks()), true);
        kv(j, 2, "retomadaAposReinicio", String.valueOf(x.adopted()), true);
        kv(j, 2, "registrosGuardados", String.valueOf(x.records()), true);
        kv(j, 2, "sessao", q(SafeText.token(x.session(), 30)), false);
        close(j, 1, false);
        j.append("}\n");
        return j.toString();
    }

    private static String key(IntegrationStatus s) {
        return switch (s) {
            case PAIRING -> "vinculo";
            case LINK -> "agentWorkerOrganizacao";
            case CONNECTION -> "conexao";
            case LICENSE -> "licenca";
            case EXECUTION -> "execucao";
        };
    }

    private static String q(String v) {
        return StrictJson.quote(v == null ? "" : v);
    }

    private static StringBuilder indent(StringBuilder j, int level) {
        return j.append("  ".repeat(level));
    }

    private static void kv(StringBuilder j, int level, String key, String jsonValue, boolean comma) {
        indent(j, level).append('"').append(key).append("\": ").append(jsonValue).append(comma ? ",\n" : "\n");
    }

    private static void open(StringBuilder j, int level, String key) {
        indent(j, level).append('"').append(key).append("\": {\n");
    }

    private static void close(StringBuilder j, int level, boolean comma) {
        indent(j, level).append(comma ? "},\n" : "}\n");
    }
}
