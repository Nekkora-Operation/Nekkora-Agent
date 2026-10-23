package com.nekkora.agent.ui;

import com.nekkora.agent.exec.ExecutionController;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.os.TrayController;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * DESKTOP-001 — homologação na JANELA REAL do Agent (fora do JUnit; processo Java próprio, sessão interativa do Windows).
 * Os seletores de arquivo do sistema são o único passo substituído: as escolhas entram pelos mesmos métodos que eles
 * chamam; Iniciar é o clique no botão. Nenhum Studio aberto, nenhuma Platform.
 *
 * <pre>
 * studio  &lt;evidências&gt; &lt;pacote do Studio&gt; &lt;python do ambiente&gt; &lt;lista de entrada&gt; &lt;pasta de saída&gt;
 *         executa o pacote gerado pelo Studio até o fim e confere o resultado estruturado e a planilha (só tipos)
 * sessao  &lt;evidências&gt; &lt;pacote de laboratório (efeito)&gt; &lt;python&gt; &lt;pasta de saída&gt;
 *         execução longa; a pessoa BLOQUEIA e DESBLOQUEIA a tela e SUSPENDE e RETOMA o Windows; confere os registros
 *         e a aba "Atividade" (OBS-G6)
 * obs-studio &lt;evidências&gt; &lt;pacote do Studio&gt; &lt;python&gt; &lt;lista&gt; &lt;pasta de saída&gt;
 *         OBS-G1/G7: aba "Atividade" capturada DURANTE a execução real, correlação com os eventos originais do
 *         Framework, histórico depois de reiniciar o Agent
 * semear-obs &lt;evidências&gt; &lt;dados&gt; &lt;pacote formato Studio (obs_studio_main)&gt; &lt;python&gt; &lt;saída&gt;
 *         queda simulada com um processo que publica eventos do Framework (o Agent empacotado adota e mostra a atividade)
 * </pre>
 */
public final class DesktopHomologation {
    private final List<String> log = new ArrayList<>();
    private int checks;
    private int failures;
    private final Path evidence;

    private DesktopHomologation(Path evidence) {
        this.evidence = evidence;
    }

    public static void main(String[] a) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.setOut(out);
        DesktopHomologation h = new DesktopHomologation(Path.of(a[1]).toAbsolutePath());
        Files.createDirectories(h.evidence);
        int code = 1;
        try {
            switch (a[0]) {
                case "studio" -> h.studio(Path.of(a[2]), Path.of(a[3]), Path.of(a[4]), Path.of(a[5]));
                case "sessao" -> h.sessao(Path.of(a[2]), Path.of(a[3]), Path.of(a[4]));
                case "semear" -> h.semear(Path.of(a[2]), Path.of(a[3]), Path.of(a[4]), Path.of(a[5]));
                case "obs-studio" -> h.obsStudio(Path.of(a[2]), Path.of(a[3]), Path.of(a[4]), Path.of(a[5]));
                case "semear-obs" -> h.semearObs(Path.of(a[2]), Path.of(a[3]), Path.of(a[4]), Path.of(a[5]));
                default -> throw new IllegalArgumentException("modo");
            }
            code = h.failures == 0 ? 0 : 2;
        } catch (Throwable t) {
            h.log("ERRO " + t);
            for (StackTraceElement s : t.getStackTrace()) h.log("    at " + s);
            h.failures++;
        } finally {
            h.log(String.format("RESULTADO: %d verificações, %d falha(s)", h.checks, h.failures));
            Files.write(h.evidence.resolve("homologacao-log.txt"), h.log, StandardCharsets.UTF_8);
        }
        System.exit(code);
    }

    private void log(String s) {
        System.out.println(s);
        log.add(s);
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) failures++;
        log((ok ? "OK    " : "FALHA ") + what);
    }

    private DesktopHarness open(Path data) throws Exception {
        DesktopHarness h = DesktopHarness.start(data, Map.of("COMPUTERNAME", "LAB-AGENT-01"), new String[0], TrayController.SYSTEM);
        MainWindow w = h.window();
        h.later(() -> w.executionButton().doClick());
        h.waitFor(() -> h.app.executionDialog() != null && h.app.executionDialog().isShowing(), "janela Execução local");
        h.waitFor(() -> h.app.executionSetup().session() != null, "sessão verificada");
        return h;
    }

    private static void choose(DesktopHarness h, Runnable r) throws Exception {
        h.run(r);
        h.waitFor(() -> !h.app.executionBusy(), "escolha conferida");
    }

    private ExecutionRecord startAndWait(DesktopHarness h, long maxMillis, String shot) throws Exception {
        ExecutionDialog d = h.edt(h.app::executionDialog);
        check("Iniciar habilitado só com todas as escolhas", h.edt(() -> d.startButton().isEnabled()));
        DesktopHarness.capture(d, shot + "-antes");
        h.run(() -> d.startButton().doClick());
        h.waitFor(() -> h.app.context().execution().running(), "em andamento");
        long t0 = System.currentTimeMillis();
        while (h.app.context().execution().running() && System.currentTimeMillis() - t0 < maxMillis) Thread.sleep(500);
        h.settle();
        DesktopHarness.capture(d, shot + "-fim");
        DesktopHarness.capture(h.window(), shot + "-janela-principal");
        return h.app.context().execution().current().orElseThrow().record();
    }

    // ----------------------------------------------------------------------------------------- automação do Studio

    void studio(Path pkg, Path python, Path input, Path output) throws Exception {
        boolean studioOpen = ProcessHandle.allProcesses().anyMatch(p -> p.info().command().map(c -> c.toLowerCase().contains("nekkora studio")).orElse(false));
        check("nenhum Studio aberto nesta máquina", !studioOpen);
        Path data = evidence.resolve("dados-agent");
        try (DesktopHarness h = open(data)) {
            choose(h, () -> h.app.selectPackage(pkg));
            ExecutionPackage p = h.app.executionSetup().pkg();
            check("pacote do Studio reconhecido (manifesto " + (p == null ? "?" : p.mode()) + ", nekkora-rpa " + (p == null ? "?" : p.requiredFramework().orElse("?")) + ")",
                    p != null && p.kind() == ExecutionPackage.Kind.STUDIO_PYTHON && p.mode() == ExecutionPackage.Mode.LIST);
            check("pacote ainda não aprovado nesta máquina (abrir/escolher não autoriza)", !h.app.executionSetup().approved());
            choose(h, h.app::approveConfirmed);
            choose(h, () -> h.app.selectPython(python));
            check("Python do ambiente do projeto compatível (" + h.app.executionSetup().python().pythonVersion() + " / nekkora-rpa "
                    + h.app.executionSetup().python().framework().orElse("—") + ")", h.app.executionSetup().pythonProblem() == null);
            choose(h, () -> h.app.selectInput(input));
            choose(h, () -> h.app.selectOutput(output));
            ExecutionRecord r = startAndWait(h, 15 * 60_000L, "G1-studio");
            log("  estado " + r.state() + " · código " + (r.code() == null ? "—" : r.code().code()) + " · saída " + r.exitCode() + " · contagens " + r.counts()
                    + " · bytes da saída do processo " + r.outputBytes());
            check("execução do pacote do Studio CONCLUÍDA com sucesso pelo protocolo (summary.json COMPLETED + planilha COMMITTED)",
                    r.state() == ExecutionState.SUCCEEDED);
            Path runOut = output.resolve(r.id().value());
            check("planilha gravada na pasta de saída escolhida (fora do Agent)", Files.isRegularFile(runOut.resolve("planilha.xlsx")));
            check("eventos do framework na pasta de saída da execução", Files.isRegularFile(runOut.resolve("execucao").resolve("events.jsonl")));
            String agentFiles = agentText(data);
            check("registros do Agent sem o nome da pasta do pacote nem caminhos de entrada/saída",
                    !agentFiles.contains(pkg.getFileName().toString()) && !agentFiles.contains(input.getFileName().toString()) && !agentFiles.contains(output.toString()));
            Files.writeString(evidence.resolve("estado-execucao.json"), Files.readString(data.resolve("execucoes").resolve(r.id().value()).resolve("estado.json")));
            Files.write(evidence.resolve("eventos-execucao.jsonl"), h.app.context().execution().store().events(r.id()), StandardCharsets.UTF_8);
        }
    }

    // ----------------------------------------------------------------------------------------- sessão do Windows

    void sessao(Path labPkg, Path python, Path output) throws Exception {
        Path data = evidence.resolve("dados-agent");
        try (DesktopHarness h = open(data)) {
            choose(h, () -> h.app.selectPackage(labPkg));
            choose(h, h.app::approveConfirmed);
            choose(h, () -> h.app.selectPython(python));
            choose(h, () -> h.app.selectOutput(output));
            ExecutionDialog d = h.edt(h.app::executionDialog);
            h.run(() -> d.tabs().setSelectedIndex(1)); // OBS-G6: a aba Atividade acompanha bloqueio/suspensão
            h.run(() -> d.startButton().doClick());
            h.waitFor(() -> h.app.context().execution().running(), "em andamento");
            log("");
            log(">>> AGORA (a execução leva ~2 minutos depois do envio; o Agent espera até 15 minutos):");
            log(">>>   1. Bloqueie a tela (Windows+L), espere ~10 s e desbloqueie.");
            log(">>>   2. Suspenda o Windows (Iniciar > Ligar/Desligar > Suspender), espere pelo menos 1 minuto e retome.");
            log(">>> Não precisa fazer mais nada: o Agent registra e a execução termina sozinha (nada é repetido).");
            log("");
            Instant t0 = Instant.now();
            java.util.Set<String> shot = new java.util.HashSet<>();
            while (h.app.context().execution().running() && Instant.now().isBefore(t0.plusSeconds(15 * 60))) {
                Thread.sleep(1000);
                // uma captura da aba quando cada fato da sessão aparece na linha do tempo (depois de desbloquear/retomar)
                for (com.nekkora.agent.obs.ActivityEvent e : h.edt(() -> d.activityPanel().events())) {
                    if (List.of("SESSION_LOCKED", "SESSION_UNLOCKED", "SUSPENSION_DETECTED").contains(e.code()) && shot.add(e.code())) {
                        log("  atividade: " + e.code() + " (seq " + e.sequence() + ", ocorrido " + e.occurred() + ", observado " + e.observed() + ")");
                        h.run(() -> d.activityPanel().revealTimeline());
                        DesktopHarness.capture(d, "G6-atividade-" + e.code().toLowerCase());
                    }
                }
            }
            h.settle();
            ExecutionRecord r = h.app.context().execution().current().orElseThrow().record();
            List<String> ev = h.app.context().execution().store().events(r.id());
            Files.write(evidence.resolve("eventos-execucao.jsonl"), ev, StandardCharsets.UTF_8);
            Files.writeString(evidence.resolve("estado-execucao.json"), Files.readString(data.resolve("execucoes").resolve(r.id().value()).resolve("estado.json")));
            DesktopHarness.capture(h.edt(h.app::executionDialog), "G2-sessao-fim");
            log("  estado " + r.state() + " · código " + (r.code() == null ? "—" : r.code().code()) + " · bloqueios " + r.locks() + " · suspensões " + r.suspensions());
            check("tela bloqueada e desbloqueada registradas durante a execução", ev.stream().anyMatch(l -> l.contains("SESSION_LOCKED"))
                    && ev.stream().anyMatch(l -> l.contains("SESSION_UNLOCKED")));
            check("suspensão do Windows detectada e registrada", r.suspensions() >= 1 && ev.stream().anyMatch(l -> l.contains("SUSPENSION_DETECTED")));
            check("execução terminou sem repetição: resultado pelo protocolo ou desconhecido (nunca relançada)", r.state().terminal()
                    && (r.state() == ExecutionState.SUCCEEDED || r.state() == ExecutionState.OUTCOME_UNKNOWN || r.state() == ExecutionState.FAILED));
            long envios;
            try (Stream<String> s = Files.lines(output.resolve("efeitos.txt"))) {
                envios = s.count();
            }
            check("efeito não idempotente aconteceu UMA vez", envios == 1);
            check("o Agent continuou o mesmo processo (sem novo LIFE_START depois da suspensão)", agentLog(data).split("LIFE_START", -1).length == 2);
            // OBS-G6: a linha do tempo mostra os mesmos fatos, sem duplicar, e fecha pelo supervisor
            h.waitFor(() -> d.activityPanel().health() != null && d.activityPanel().health().completed(), "coleta encerrada");
            List<com.nekkora.agent.obs.ActivityEvent> act = h.edt(() -> d.activityPanel().events());
            boolean contiguous = true;
            for (int i = 0; i < act.size(); i++) contiguous &= act.get(i).sequence() == i + 1;
            check("OBS-G6: linha do tempo com SESSION_LOCKED, SESSION_UNLOCKED e SUSPENSION_DETECTED", List.of("SESSION_LOCKED", "SESSION_UNLOCKED",
                    "SUSPENSION_DETECTED").stream().allMatch(c -> act.stream().anyMatch(e -> c.equals(e.code()))));
            check("OBS-G6: sequência contínua 1.." + act.size() + ", desfecho do supervisor (" + r.state() + ") e coleta encerrada", contiguous
                    && act.stream().anyMatch(e -> e.source() == com.nekkora.agent.obs.ActivityEvent.Source.AGENT && r.state().name().equals(e.code())));
            check("OBS-G6: nenhum sucesso fictício (estado = protocolo ou desconhecido)", r.state() != ExecutionState.SUCCEEDED
                    || Files.isRegularFile(output.resolve(r.id().value()).resolve("agent-result.json")));
            h.run(() -> d.activityPanel().revealSummary());
            DesktopHarness.capture(d, "G6-atividade-fim");
            Files.write(evidence.resolve("atividade-exportada.jsonl"), com.nekkora.agent.obs.ActivityExport.jsonl(act));
        }
    }

    // ----------------------------------------------------------------------------------------- queda simulada

    /**
     * Simula o Agent que CAIU no meio de uma execução: lança o processo de laboratório lento (que continua vivo depois
     * deste programa terminar) e grava o registro RUNNING dele em {@code <dados>/execucoes/}, como o Agent teria
     * gravado. O Agent empacotado, aberto em seguida sobre o mesmo diretório, precisa adotá-lo (sem relançar nada).
     */
    void semear(Path data, Path labPkg, Path python, Path output) throws Exception {
        ExecutionPackage p = ExecutionPackage.inspect(labPkg);
        com.nekkora.agent.exec.ExecutionId id = com.nekkora.agent.exec.ExecutionId.random();
        Path out = Files.createDirectories(output.resolve(id.value()));
        Process orphan = new ProcessBuilder(python.toString(), "-E", "-s", "-B", p.entry(), "--output", out.toString()).directory(labPkg.toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        com.nekkora.agent.exec.RunStore store = new com.nekkora.agent.exec.RunStore(data.resolve("execucoes"), com.nekkora.agent.storage.AtomicWriter.DEFAULT);
        com.nekkora.agent.exec.ProcessOwner owner = com.nekkora.agent.exec.ProcessOwner.of(orphan, python.toString());
        store.save(new ExecutionRecord(id, p.kind(), "pacote " + p.shortFingerprint(), p.fingerprint(), ExecutionState.RUNNING, null, Instant.now(),
                Instant.now(), null, owner, null, Map.of(), 0, 0, false, 0));
        Files.writeString(store.dir(id).resolve("saida.ref"), out.toString(), StandardCharsets.UTF_8);
        log("SEMEADO " + id.value() + " pid=" + owner.pid());
        check("processo órfão vivo e registro RUNNING gravado (o Agent \"caiu\")", owner.alive());
    }

    // ----------------------------------------------------------------------------------------- OBS-001

    /** OBS-G1/G7: pacote REAL do Studio, aba "Atividade" capturada durante; correlação; histórico após reiniciar. */
    void obsStudio(Path pkg, Path python, Path input, Path output) throws Exception {
        boolean studioOpen = ProcessHandle.allProcesses().anyMatch(p -> p.info().command().map(c -> c.toLowerCase().contains("nekkora studio")).orElse(false));
        check("nenhum Studio aberto nesta máquina", !studioOpen);
        Path data = evidence.resolve("dados-agent");
        List<String> rows = Files.readAllLines(input, StandardCharsets.UTF_8);
        List<String> inputValues = new ArrayList<>();
        for (String line : rows.subList(1, rows.size())) {
            for (String v : line.split(",")) if (!v.isBlank()) inputValues.add(v.strip());
        }
        int items = rows.size() - 1;
        com.nekkora.agent.exec.ExecutionId id;
        int count;
        try (DesktopHarness h = open(data)) {
            choose(h, () -> h.app.selectPackage(pkg));
            ExecutionPackage p = h.app.executionSetup().pkg();
            check("pacote do Studio reconhecido (" + (p == null ? "?" : p.mode()) + ", nekkora-rpa " + (p == null ? "?" : p.requiredFramework().orElse("?")) + ")",
                    p != null && p.kind() == ExecutionPackage.Kind.STUDIO_PYTHON);
            choose(h, h.app::approveConfirmed);
            choose(h, () -> h.app.selectPython(python));
            choose(h, () -> h.app.selectInput(input));
            choose(h, () -> h.app.selectOutput(output));
            ExecutionDialog d = h.edt(h.app::executionDialog);
            h.run(() -> d.tabs().setSelectedIndex(1));
            check("Iniciar habilitado só com todas as escolhas", h.edt(() -> d.startButton().isEnabled()));
            h.run(() -> d.startButton().doClick());
            h.waitFor(() -> h.app.context().execution().running(), "em andamento");
            id = h.app.context().execution().current().orElseThrow().record().id();
            int frame = 0;
            boolean stepsWhileRunning = false;
            long t0 = System.currentTimeMillis();
            while (h.app.context().execution().running() && System.currentTimeMillis() - t0 < 15 * 60_000L) {
                Thread.sleep(600);
                List<com.nekkora.agent.obs.ActivityEvent> ev = h.edt(() -> d.activityPanel().events());
                boolean running = h.app.context().execution().running();
                com.nekkora.agent.obs.ActivityView.Progress pr = com.nekkora.agent.obs.ActivityView.progress(ev);
                if (running && pr.currentStep() != null && frame < 8) {
                    stepsWhileRunning = true;
                    String tag = String.format("G1-obs-durante-%02d", ++frame);
                    h.run(() -> d.activityPanel().revealSummary());
                    DesktopHarness.capture(d, tag);
                    h.run(() -> d.activityPanel().revealTimeline());
                    DesktopHarness.capture(d, tag + "-linha-do-tempo");
                    log("  durante (" + tag + "): estado RUNNING · " + ev.size() + " eventos · fase " + pr.phase() + " · etapa " + pr.currentStep().stepId()
                            + " · transações iniciadas " + pr.transactionsStarted());
                }
            }
            check("etapas do Framework na tela ENQUANTO a execução estava em andamento (" + frame + " capturas)", stepsWhileRunning);
            h.waitFor(() -> d.activityPanel().health() != null && d.activityPanel().health().completed(), "coleta encerrada");
            h.settle();
            ExecutionRecord r = h.app.context().execution().current().orElseThrow().record();
            List<com.nekkora.agent.obs.ActivityEvent> ev = h.edt(() -> d.activityPanel().events());
            count = ev.size();
            log("  estado " + r.state() + " · código " + (r.code() == null ? "—" : r.code().code()) + " · contagens " + r.counts() + " · " + ev.size()
                    + " eventos de atividade");
            check("execução CONCLUÍDA com sucesso pelo protocolo do supervisor", r.state() == ExecutionState.SUCCEEDED);
            List<com.nekkora.agent.obs.ActivityEvent> fw = ev.stream().filter(e -> e.source() == com.nekkora.agent.obs.ActivityEvent.Source.FRAMEWORK).toList();
            int init = indexOf(fw, "INIT");
            int process = indexOf(fw, "PROCESS");
            int end = indexOf(fw, "END");
            check("fases em sequência INIT → PROCESS → END na linha do tempo", init >= 0 && init < process && process < end);
            check("ações reais na linha do tempo (search.press_enter, profile.read_birth_date)", fw.stream().anyMatch(e -> "search.press_enter".equals(e.stepId()))
                    && fw.stream().anyMatch(e -> "profile.read_birth_date".equals(e.stepId())));
            com.nekkora.agent.obs.ActivityView.Progress pr = com.nekkora.agent.obs.ActivityView.progress(ev);
            check("transações: " + pr.transactionsStarted() + " iniciadas, " + pr.transactionsSucceeded() + " concluídas (linhas da lista: " + items + ")",
                    pr.transactionsStarted() == items && pr.transactionsSucceeded() == items);
            check("encerramento pelo supervisor (SUCCEEDED do Agent) seguido do fechamento da coleta", ev.stream().anyMatch(e -> e.source()
                    == com.nekkora.agent.obs.ActivityEvent.Source.AGENT && "SUCCEEDED".equals(e.code())) && "COLLECTION_COMPLETED".equals(ev.get(ev.size() - 1).code()));
            h.run(() -> d.activityPanel().revealSummary());
            DesktopHarness.capture(d, "G1-obs-concluida");
            h.run(() -> d.activityPanel().revealTimeline());
            DesktopHarness.capture(d, "G1-obs-concluida-linha-do-tempo");

            // OBS-G7: correlação com os eventos ORIGINAIS do Framework (mesma execução)
            Path raw = output.resolve(id.value()).resolve("execucao").resolve("events.jsonl");
            byte[] rawBytes = Files.readAllBytes(raw);
            int matched = 0;
            boolean consistent = true;
            String fwId = h.edt(() -> d.activityPanel().health()).frameworkExecutionId();
            for (com.nekkora.agent.obs.ActivityEvent e : fw) {
                int endPos = Math.toIntExact(e.sourcePosition());
                int start = endPos - 1;
                while (start > 0 && rawBytes[start - 1] != 0x0A) start--;
                Map<String, Object> m = com.nekkora.agent.exec.JsonReader.object(new String(rawBytes, start, endPos - start, StandardCharsets.UTF_8).strip());
                boolean same = e.code().equals(m.get("event_code")) && fwId.equals(m.get("execution_id")) && java.util.Objects.equals(e.stepId(), m.get("step_id"))
                        && java.util.Objects.equals(e.phase(), m.get("phase"));
                consistent &= same;
                if (same) matched++;
            }
            long rawLines = new String(rawBytes, StandardCharsets.UTF_8).lines().count();
            check("G7: cada evento da linha do tempo corresponde à linha original na mesma posição (" + matched + "/" + fw.size() + "; linhas brutas " + rawLines
                    + ")", consistent && matched == fw.size() && rawLines == fw.size());
            check("G7: Agent " + id.value() + " ↔ Framework " + fwId + " (identidades distintas, correlação registrada)", fwId != null && !fwId.equals(id.value()));
            Map<String, Object> manifest = com.nekkora.agent.exec.JsonReader.object(Files.readString(pkg.resolve("manifest.json"), StandardCharsets.UTF_8));
            Map<String, String> trace = new java.util.HashMap<>();
            for (Object o : com.nekkora.agent.exec.JsonReader.list(manifest, "steps")) {
                Map<?, ?> st = (Map<?, ?>) o;
                trace.put((String) st.get("traceId"), (String) st.get("stepId"));
            }
            boolean mapped = fw.stream().filter(e -> e.stepId() != null).allMatch(e -> java.util.Objects.equals(trace.get(e.stepId()), e.studioStepId()));
            check("G7: step_id → passo do Studio pelo manifesto (sem correspondência inventada para passos fora dele)", mapped
                    && fw.stream().anyMatch(e -> e.studioStepId() != null));

            // privacidade com dados REAIS da lista: nenhum valor de entrada na atividade, na tela nem na exportação
            byte[] export = com.nekkora.agent.obs.ActivityExport.jsonl(ev);
            String activityFile = Files.readString(data.resolve("execucoes").resolve(id.value()).resolve("atividade.jsonl"), StandardCharsets.UTF_8);
            List<String> screen = h.edt(() -> {
                List<String> out = new ArrayList<>(DesktopHarness.texts(d.getRootPane()));
                for (int i = 0; i < d.activityPanel().model().getRowCount(); i++) {
                    for (int c = 0; c < d.activityPanel().model().getColumnCount(); c++) out.add(String.valueOf(d.activityPanel().model().getValueAt(i, c)));
                }
                return out;
            });
            String all = activityFile + new String(export, StandardCharsets.UTF_8) + String.join("\n", screen);
            check("nenhum valor da lista de entrada (" + inputValues.size() + " valores) na atividade, na tela ou na exportação",
                    inputValues.stream().noneMatch(all::contains));
            Files.write(evidence.resolve("atividade-exportada.jsonl"), export);
            String table = h.edt(() -> {
                var mdl = d.activityPanel().model();
                StringBuilder b = new StringBuilder();
                for (int c = 0; c < mdl.getColumnCount(); c++) b.append(c == 0 ? "" : " | ").append(mdl.getColumnName(c));
                b.append('\n');
                for (int i = 0; i < mdl.getRowCount(); i++) {
                    for (int c = 0; c < mdl.getColumnCount(); c++) b.append(c == 0 ? "" : " | ").append(mdl.getValueAt(i, c));
                    b.append('\n');
                }
                return b.toString();
            });
            Files.writeString(evidence.resolve("linha-do-tempo.txt"), table, StandardCharsets.UTF_8);
            Files.write(evidence.resolve("eventos-execucao.jsonl"), h.app.context().execution().store().events(id), StandardCharsets.UTF_8);
        }
        // reinício do Agent: o histórico continua consultável, sem duplicar
        try (DesktopHarness h = open(data)) {
            ExecutionDialog d = h.edt(h.app::executionDialog);
            h.run(() -> d.tabs().setSelectedIndex(1));
            final com.nekkora.agent.exec.ExecutionId runId = id;
            final int expected = count;
            h.waitFor(() -> runId.equals(d.activityPanel().selectedRun()) && d.activityPanel().events().size() == expected, "histórico recarregado");
            h.settle();
            check("histórico após reiniciar o Agent: " + expected + " eventos, mesma sequência, sem duplicar",
                    h.edt(() -> d.activityPanel().events()).size() == expected && !h.edt(() -> d.activityPanel().health()).live());
            h.run(() -> d.activityPanel().revealTimeline());
            DesktopHarness.capture(d, "G1-obs-historico-apos-reiniciar");
        }
    }

    private static int indexOf(List<com.nekkora.agent.obs.ActivityEvent> fw, String phase) {
        for (int i = 0; i < fw.size(); i++) if (phase.equals(fw.get(i).phase()) && "PHASE_STARTED".equals(fw.get(i).code())) return i;
        return -1;
    }

    /**
     * Queda simulada com um processo no formato do Studio que publica eventos do Framework ({@code obs_studio_main}):
     * cópia conferida, {@code saida.ref}, eventos STARTING/RUNNING e o registro RUNNING — como o Agent teria deixado.
     */
    void semearObs(Path data, Path pkgDir, Path python, Path output) throws Exception {
        if (!Files.exists(pkgDir.resolve("manifest.json"))) {
            com.nekkora.agent.exec.ObsStudioPackage.create(pkgDir, "{\"mode\": \"slow\", \"items\": 12, \"delay_ms\": 600}");
        }
        ExecutionPackage p = ExecutionPackage.inspect(pkgDir);
        com.nekkora.agent.exec.RunStore store = new com.nekkora.agent.exec.RunStore(data.resolve("execucoes"), com.nekkora.agent.storage.AtomicWriter.DEFAULT);
        com.nekkora.agent.exec.ApprovalStore approvals = new com.nekkora.agent.exec.ApprovalStore(data.resolve("execucao").resolve("aprovacoes.json"),
                com.nekkora.agent.storage.AtomicWriter.DEFAULT);
        approvals.approve(p, Instant.now());
        com.nekkora.agent.exec.ExecutionId id = com.nekkora.agent.exec.ExecutionId.random();
        Instant now = Instant.now();
        ExecutionRecord r = new ExecutionRecord(id, p.kind(), "pacote " + p.shortFingerprint(), p.fingerprint(), ExecutionState.READY, null, now, null, null, null,
                null, Map.of(), 0, 0, false, 0).moveTo(ExecutionState.STARTING, null, now);
        store.save(r);
        store.event(id, now, "STARTING", Map.of("kind", p.kind().name(), "mode", p.mode().name(), "session", 1));
        Path copy = com.nekkora.agent.exec.Launch.snapshot(p, store.dir(id));
        Path out = Files.createDirectories(output.resolve(id.value()));
        Files.writeString(store.dir(id).resolve("saida.ref"), out.toString(), StandardCharsets.UTF_8);
        com.nekkora.agent.exec.Launch.Spec spec = com.nekkora.agent.exec.Launch.spec(p, python, copy, null, out);
        ProcessBuilder pb = new ProcessBuilder(spec.command()).directory(spec.workDir().toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        com.nekkora.agent.exec.PythonRuntime.minimalEnvironment(pb.environment());
        Process orphan = pb.start();
        com.nekkora.agent.exec.ProcessOwner owner = com.nekkora.agent.exec.ProcessOwner.of(orphan, python.toString());
        store.save(r.withOwner(owner).moveTo(ExecutionState.RUNNING, null, Instant.now()));
        store.event(id, Instant.now(), "RUNNING", Map.of("pid", owner.pid()));
        log("SEMEADO " + id.value() + " pid=" + owner.pid());
        check("processo (formato Studio, eventos do Framework) vivo e registro RUNNING gravado (o Agent \"caiu\")", owner.alive());
    }

    private static String agentText(Path data) throws Exception {
        StringBuilder b = new StringBuilder();
        try (Stream<Path> s = Files.walk(data)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String rel = data.relativize(f).toString();
                if (rel.contains("pacote") || rel.endsWith("saida.ref") || rel.endsWith(".py") || rel.equals("agent.lock")) continue;
                b.append(Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        return b.toString();
    }

    private static String agentLog(Path data) throws Exception {
        StringBuilder b = new StringBuilder();
        try (Stream<Path> s = Files.list(data.resolve("logs"))) {
            for (Path f : s.toList()) b.append(Files.readString(f, StandardCharsets.UTF_8));
        }
        return b.toString();
    }
}
