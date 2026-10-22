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
            h.run(() -> d.startButton().doClick());
            h.waitFor(() -> h.app.context().execution().running(), "em andamento");
            log("");
            log(">>> AGORA (a execução leva ~2 minutos depois do envio; o Agent espera até 15 minutos):");
            log(">>>   1. Bloqueie a tela (Windows+L), espere ~10 s e desbloqueie.");
            log(">>>   2. Suspenda o Windows (Iniciar > Ligar/Desligar > Suspender), espere pelo menos 1 minuto e retome.");
            log(">>> Não precisa fazer mais nada: o Agent registra e a execução termina sozinha (nada é repetido).");
            log("");
            Instant t0 = Instant.now();
            while (h.app.context().execution().running() && Instant.now().isBefore(t0.plusSeconds(15 * 60))) Thread.sleep(1000);
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
