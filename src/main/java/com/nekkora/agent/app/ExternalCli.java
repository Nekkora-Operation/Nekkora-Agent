package com.nekkora.agent.app;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecRefused;
import com.nekkora.agent.exec.ExternalBindings;
import com.nekkora.agent.exec.ExternalLaunch;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.platform.LocalGate;
import com.nekkora.agent.storage.AgentDataDir;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AGENT-PYTHON-001: comandos de console do Agent, sem janela e sem a trava de instância única (o Agent pode estar aberto).
 * <ul>
 *   <li>{@code lancar --vinculo <nome> -- <python> <script> [argumentos do cliente...]}: o que a tarefa agendada chama. A
 *       pasta de trabalho é a da tarefa. Devolve o código de saída do script (ou 120/121 quando ele não rodou);</li>
 *   <li>{@code vincular --nome <nome> --automacao <uuid> --python <exe> --script <py> --pasta <dir>}: ato da pessoa NA
 *       MÁQUINA; mostra o que será aprovado e pede a confirmação digitada ({@code SIM}) no console;</li>
 *   <li>{@code vinculos}: lista os vínculos aprovados (nome e automação; os caminhos só são mostrados aqui, localmente);</li>
 *   <li>{@code desvincular --nome <nome>}.</li>
 * </ul>
 * Nada aqui fala com a rede. O lançador consulta a autorização guardada pelo Agent ({@link LocalGate}, só leitura).
 */
public final class ExternalCli {
    public static final List<String> COMMANDS = List.of("lancar", "vincular", "vinculos", "desvincular", "pacote");

    private ExternalCli() {}

    public static boolean handles(String[] args) {
        return args.length > 0 && COMMANDS.contains(args[0]);
    }

    public static int run(String[] args, PrintStream out, PrintStream err, java.io.InputStream in, Clock clock) {
        AgentDataDir data;
        try {
            data = AgentDataDir.open(AgentDataDir.locateCurrent());
        } catch (IOException e) {
            err.println("nekkora: " + ErrorCode.DATA_DIR_UNAVAILABLE.code() + " " + ErrorCode.DATA_DIR_UNAVAILABLE.description());
            return ExternalLaunch.EXIT_USAGE;
        }
        ExternalBindings bindings = new ExternalBindings(ExternalBindings.defaultFile(data.root()), AtomicWriter.DEFAULT);
        return switch (args[0]) {
            case "lancar" -> launch(Arrays.copyOfRange(args, 1, args.length), data, bindings, err, clock);
            case "vincular" -> bind(Arrays.copyOfRange(args, 1, args.length), bindings, out, err, in, clock);
            case "vinculos" -> list(bindings, out);
            case "desvincular" -> unbind(Arrays.copyOfRange(args, 1, args.length), bindings, out, err);
            case "pacote" -> pkg(Arrays.copyOfRange(args, 1, args.length), data, out, err, in, clock);
            default -> ExternalLaunch.EXIT_USAGE;
        };
    }

    static int launch(String[] a, AgentDataDir data, ExternalBindings bindings, PrintStream err, Clock clock) {
        String name = null;
        int i = 0;
        for (; i < a.length; i++) {
            if ("--vinculo".equals(a[i]) && i + 1 < a.length) name = a[++i];
            else if ("--".equals(a[i])) {
                i++;
                break;
            } else {
                err.println("nekkora-lancar: uso: lancar --vinculo <nome> -- <python> <script> [argumentos...]");
                return ExternalLaunch.EXIT_USAGE;
            }
        }
        if (name == null || a.length - i < 2) {
            err.println("nekkora-lancar: uso: lancar --vinculo <nome> -- <python> <script> [argumentos...]");
            return ExternalLaunch.EXIT_USAGE;
        }
        Path python = Path.of(a[i]).toAbsolutePath();
        Path script = Path.of(a[i + 1]);
        Path cwd = Path.of("").toAbsolutePath();
        if (!script.isAbsolute()) script = cwd.resolve(script);
        List<String> clientArgs = new ArrayList<>(Arrays.asList(a).subList(i + 2, a.length));
        final String bindingName = name;
        Path platformDir = data.root().resolve("plataforma");
        RunStore runs = new RunStore(data.root().resolve("execucoes"), AtomicWriter.DEFAULT);
        ExternalLaunch.Gate gate = automation -> LocalGate.monitored(platformDir, automation, clock.instant()).map(LocalGate.LinkRequest::authorizationId);
        ExternalLaunch.LinkRequest link = (dir, id, authorization) -> {
            Optional<LocalGate.LinkRequest> r = LocalGate.monitored(platformDir, bindings.find(bindingName).orElseThrow().automationId(), clock.instant())
                    .filter(x -> x.authorizationId().equals(authorization));
            if (r.isEmpty()) throw new IOException("autorização mudou entre a conferência e a gravação");
            LocalGate.writeLinkRequest(dir, r.get(), AtomicWriter.DEFAULT);
        };
        return new ExternalLaunch(runs, bindings, gate, link, clock).run(bindingName, python, script, clientArgs, cwd, err).exitCode();
    }

    static int bind(String[] a, ExternalBindings bindings, PrintStream out, PrintStream err, java.io.InputStream in, Clock clock) {
        String name = null, automation = null, python = null, script = null, dir = null;
        for (int i = 0; i + 1 < a.length; i += 2) {
            switch (a[i]) {
                case "--nome" -> name = a[i + 1];
                case "--automacao" -> automation = a[i + 1];
                case "--python" -> python = a[i + 1];
                case "--script" -> script = a[i + 1];
                case "--pasta" -> dir = a[i + 1];
                default -> {
                    err.println("vincular: opção desconhecida " + a[i]);
                    return ExternalLaunch.EXIT_USAGE;
                }
            }
        }
        if (name == null || automation == null || python == null || script == null || dir == null) {
            err.println("uso: vincular --nome <nome> --automacao <uuid> --python <exe> --script <py> --pasta <dir>");
            return ExternalLaunch.EXIT_USAGE;
        }
        UUID automationId;
        try {
            automationId = UUID.fromString(automation);
        } catch (IllegalArgumentException e) {
            err.println("vincular: automação deve ser o identificador (UUID) mostrado na Platform");
            return ExternalLaunch.EXIT_USAGE;
        }
        out.println("Vínculo a aprovar NESTA máquina (os caminhos nunca saem dela):");
        out.println("  nome:        " + name);
        out.println("  automação:   " + automationId);
        out.println("  interpretador: " + python);
        out.println("  script:      " + script);
        out.println("  pasta:       " + dir);
        out.print("Digite SIM para aprovar: ");
        out.flush();
        String answer;
        try {
            answer = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
        } catch (IOException e) {
            answer = null;
        }
        if (!"SIM".equals(answer == null ? null : answer.strip())) {
            err.println("vincular: não aprovado (nada foi gravado)");
            return ExternalLaunch.EXIT_USAGE;
        }
        try {
            bindings.approve(name, automationId, Path.of(python), Path.of(script), Path.of(dir), clock.instant());
        } catch (ExecRefused e) {
            err.println("vincular: " + e.code().code() + " " + e.code().description());
            return ExternalLaunch.EXIT_USAGE;
        }
        out.println("Vínculo aprovado. Na tarefa agendada, troque o programa por nekkora-lancar e passe:");
        out.println("  --vinculo " + name + " -- \"" + python + "\" \"" + script + "\" <seus argumentos>");
        return 0;
    }

    /**
     * {@code pacote instalar <zip|pasta> --python <exe> --saida <pasta>}: AGENT-PYTHON-001 (Marco 2). Confere o pacote (sem
     * executar nada dele), confere o ambiente Python preparado pela pessoa (versão e dependências exatas), mostra o que será
     * aprovado e pede a confirmação digitada ({@code SIM}). Aprovado: fica presente nesta máquina, pela impressão, para os
     * pedidos de execução da Platform. Não instala dependências.
     */
    static int pkg(String[] a, AgentDataDir data, PrintStream out, PrintStream err, java.io.InputStream in, Clock clock) {
        if (a.length < 2 || !"instalar".equals(a[0])) {
            err.println("uso: pacote instalar <zip|pasta> --python <exe> --saida <pasta>");
            return ExternalLaunch.EXIT_USAGE;
        }
        Path source = Path.of(a[1]).toAbsolutePath();
        String python = null, outDir = null;
        for (int i = 2; i + 1 < a.length; i += 2) {
            switch (a[i]) {
                case "--python" -> python = a[i + 1];
                case "--saida" -> outDir = a[i + 1];
                default -> {
                    err.println("pacote: opção desconhecida " + a[i]);
                    return ExternalLaunch.EXIT_USAGE;
                }
            }
        }
        if (python == null || outDir == null || !java.nio.file.Files.isDirectory(Path.of(outDir))) {
            err.println("uso: pacote instalar <zip|pasta> --python <exe> --saida <pasta existente>");
            return ExternalLaunch.EXIT_USAGE;
        }
        com.nekkora.agent.exec.PackageShelf shelf = new com.nekkora.agent.exec.PackageShelf(com.nekkora.agent.exec.PackageShelf.defaultRoot(data.root()),
                AtomicWriter.DEFAULT);
        com.nekkora.agent.exec.ApprovalStore approvals = new com.nekkora.agent.exec.ApprovalStore(data.root().resolve("execucao").resolve("aprovacoes.json"),
                AtomicWriter.DEFAULT);
        com.nekkora.agent.exec.ExecutionPackage p;
        try {
            p = shelf.stage(source);
        } catch (ExecRefused e) {
            err.println("pacote recusado: " + e.code().code() + " " + e.code().description());
            return ExternalLaunch.EXIT_USAGE;
        }
        out.println("Pacote a aprovar NESTA máquina:");
        out.println("  tipo:        " + p.kind());
        out.println("  nome/versão: " + p.name() + (p.packageVersion() == null ? "" : " " + p.packageVersion()));
        out.println("  entrada:     " + p.entry());
        out.println("  arquivos:    " + p.files().size());
        out.println("  Python:      " + (p.pythonRequirement() == null ? "-" : p.pythonRequirement()));
        out.println("  dependências: " + (p.dependencies().isEmpty() ? "(nenhuma)" : String.join(", ", p.dependencies())));
        out.println("  impressão (" + com.nekkora.agent.exec.ExecutionPackage.ruleOf(p.kind()) + "): " + p.fingerprint().substring("sha256:".length()));
        out.println("  gerador declarado (não verificado): " + p.declaredGenerator().orElse("-"));
        try {
            com.nekkora.agent.exec.PythonRuntime.Probe probe = com.nekkora.agent.exec.PythonRuntime.probe(
                    com.nekkora.agent.exec.PythonRuntime.validate(Path.of(python)), data.root().resolve("execucao").resolve("sonda"));
            com.nekkora.agent.exec.PythonRuntime.requireCompatible(probe, p);
            if (p.kind() == com.nekkora.agent.exec.ExecutionPackage.Kind.EXTERNAL_PYTHON) com.nekkora.agent.exec.PythonRuntime.requireDependencies(probe.python(), p);
            out.println("  ambiente:    Python " + probe.pythonVersion() + " compatível; dependências conferidas");
        } catch (ExecRefused e) {
            shelf.discard(p);
            err.println("ambiente incompatível: " + e.code().code() + " " + e.code().description() + " (nada foi aprovado; prepare o ambiente e repita)");
            return ExternalLaunch.EXIT_USAGE;
        }
        out.print("Digite SIM para aprovar este pacote nesta máquina: ");
        out.flush();
        String answer;
        try {
            answer = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
        } catch (IOException e) {
            answer = null;
        }
        if (!"SIM".equals(answer == null ? null : answer.strip())) {
            shelf.discard(p);
            err.println("pacote: não aprovado (nada foi gravado)");
            return ExternalLaunch.EXIT_USAGE;
        }
        try {
            com.nekkora.agent.exec.PackageShelf.Entry e = shelf.install(p, Path.of(python), Path.of(outDir), clock.instant());
            approvals.approve(com.nekkora.agent.exec.ExecutionPackage.inspect(e.dir()), clock.instant());
            out.println("Pacote aprovado e presente nesta máquina. Pedidos de execução da Platform para esta versão podem ser aceitos.");
            return 0;
        } catch (ExecRefused e) {
            err.println("pacote: " + e.code().code() + " " + e.code().description());
            return ExternalLaunch.EXIT_USAGE;
        }
    }

    static int list(ExternalBindings bindings, PrintStream out) {
        bindings.all().values().forEach(b -> out.println(b.name() + "\t" + b.automationId() + "\t" + b.interpreter() + "\t" + b.script() + "\t" + b.workDir()));
        return 0;
    }

    static int unbind(String[] a, ExternalBindings bindings, PrintStream out, PrintStream err) {
        if (a.length != 2 || !"--nome".equals(a[0])) {
            err.println("uso: desvincular --nome <nome>");
            return ExternalLaunch.EXIT_USAGE;
        }
        try {
            out.println(bindings.remove(a[1]) ? "Vínculo removido." : "Vínculo inexistente.");
            return 0;
        } catch (ExecRefused e) {
            err.println("desvincular: " + e.code().code());
            return ExternalLaunch.EXIT_USAGE;
        }
    }
}
