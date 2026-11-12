package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AGENT-PYTHON-001 (Marco 1): o lançador que o AGENDADOR DO CLIENTE chama no lugar do Python. Uma invocação = uma execução
 * identificável ({@link ExecutionId}). O cliente mantém script, ambiente virtual, argumentos, pasta de trabalho e horário:
 * a única mudança na tarefa é passar por aqui.
 * <ol>
 *   <li>confere o vínculo aprovado (interpretador, script e pasta de trabalho atual iguais aos aprovados);</li>
 *   <li>pede ao {@link Gate} (camada do app, só leitura local) uma autorização válida da Platform para a automação do vínculo.
 *       Sem autorização: NADA é iniciado (não existe autorização offline inventada);</li>
 *   <li>grava o pedido de vínculo à Platform ({@link LinkRequest}, antes de tudo), o registro READY → STARTING e só então cria
 *       o processo por {@link ProcessBuilder} com LISTA de argumentos (sem shell, sem texto remoto), herdando entrada e saídas
 *       (o agendador continua vendo e redirecionando tudo; o Agent não guarda a saída);</li>
 *   <li>grava a posse (PID e início do processo do cliente) e RUNNING; espera; grava o código de saída e o desfecho;</li>
 *   <li>devolve ao agendador o MESMO código de saída do script.</li>
 * </ol>
 * O lançador NUNCA fala com a rede: a entrega (registro, relatórios e eventos) é feita pelo Agent a partir dos fatos gravados.
 * Uma queda do canal não toca o processo. Desfecho: código 0 sem sinal = término sem erro sinalizado ({@code AGT-EXT-001}),
 * nunca sucesso de negócio; o sinal explícito do cliente é opcional ({@code NEKKORA_SINAL}).
 */
public final class ExternalLaunch {
    /** Códigos de saída do PRÓPRIO lançador (quando o script não chegou a rodar). */
    public static final int EXIT_USAGE = 120;
    public static final int EXIT_NOT_STARTED = 121;
    public static final String LAUNCHER_FILE = "lancador.json";
    public static final String SIGNAL_FILE = "sinal.json";
    public static final String SIGNAL_SCHEMA = "nekkora-agent/external-signal";
    public static final String SIGNAL_ENV = "NEKKORA_SINAL";

    /** Autorização válida (no prazo, pelo relógio com piso) para a automação, ou vazio. Implementada pelo app: só leitura. */
    public interface Gate {
        Optional<UUID> authorize(UUID automationId);
    }

    /** Grava, na pasta da execução, o pedido de vínculo à Platform ANTES do registro (o Agent cria a entrega ao encontrá-lo). */
    public interface LinkRequest {
        void write(Path runDir, ExecutionId id, UUID authorizationId) throws IOException;
    }

    /** O que aconteceu. {@code exitCode}: o que o agendador recebe. */
    public record Result(int exitCode, Optional<ExecutionId> execution, ExecutionState state, ErrorCode code) {}

    private final RunStore store;
    private final ExternalBindings bindings;
    private final Gate gate;
    private final LinkRequest link;
    private final Clock clock;

    public ExternalLaunch(RunStore store, ExternalBindings bindings, Gate gate, LinkRequest link, Clock clock) {
        this.store = store;
        this.bindings = bindings;
        this.gate = gate;
        this.link = link;
        this.clock = clock;
    }

    /**
     * {@code interpreter} e {@code script}: os que a tarefa agendada informa; {@code args}: os argumentos do cliente, repassados
     * sem interpretação; {@code currentDir}: a pasta de trabalho da tarefa.
     */
    public Result run(String bindingName, Path interpreter, Path script, List<String> args, Path currentDir, java.io.PrintStream err) {
        Optional<ExternalBindings.Binding> ob = bindings.find(bindingName);
        if (ob.isEmpty()) return refuse(err, ErrorCode.EXT_BINDING_UNKNOWN, EXIT_USAGE);
        ExternalBindings.Binding b = ob.get();
        if (!ExternalBindings.matches(b, interpreter, script, currentDir)) return refuse(err, ErrorCode.EXT_BINDING_MISMATCH, EXIT_USAGE);
        Optional<UUID> auth;
        try {
            auth = gate.authorize(b.automationId());
        } catch (RuntimeException e) {
            auth = Optional.empty();
        }
        if (auth.isEmpty()) return refuse(err, ErrorCode.EXT_NOT_AUTHORIZED, EXIT_NOT_STARTED);

        ExecutionId id = ExecutionId.random();
        Path dir = store.dir(id);
        Instant now = clock.instant();
        ExecutionRecord r = new ExecutionRecord(id, ExecutionPackage.Kind.EXTERNAL_MONITORED, "python externo", null, ExecutionState.READY, null, now, null,
                null, null, null, Map.of(), 0, 0, false, 0).withOrigin(ExecutionRecord.Origin.EXTERNAL_SCHEDULER);
        try {
            Files.createDirectories(dir);
            // o pedido de vínculo vem PRIMEIRO: uma pasta com estado.json e sem ele nunca é de uma execução externa vinculada
            link.write(dir, id, auth.get());
            writeLauncher(dir);
            // T13: confere de novo DEPOIS de marcar o lançador (o desvínculo grava o pedido e procura lançadores vivos): se um
            // desvínculo foi confirmado nesse intervalo, nada é iniciado (a pasta fica sem estado.json e não é adotada)
            Optional<UUID> again;
            try {
                again = gate.authorize(b.automationId());
            } catch (RuntimeException e) {
                again = Optional.empty();
            }
            if (again.isEmpty()) return refuse(err, ErrorCode.EXT_NOT_AUTHORIZED, EXIT_NOT_STARTED);
            store.save(r);
            r = r.moveTo(ExecutionState.STARTING, null, clock.instant());
            store.save(r);
            store.event(id, clock.instant(), "STARTING", Map.of("kind", r.kind().name()));
        } catch (IOException | ExecRefused e) {
            // nada foi iniciado: o registro (se existir) fica READY e a reconciliação o fecha como BLOCKED
            return refuse(err, ErrorCode.EXT_STORAGE_FAILED, EXIT_NOT_STARTED);
        }

        List<String> command = new ArrayList<>();
        command.add(b.interpreter().toString());
        command.add(b.script().toString());
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).directory(b.workDir().toFile()).inheritIO();
        Path signal = dir.resolve(SIGNAL_FILE);
        pb.environment().put(SIGNAL_ENV, signal.toString());
        Process p;
        try {
            p = pb.start();
        } catch (IOException | RuntimeException e) {
            ExecutionRecord f = r.moveTo(ExecutionState.FAILED, ErrorCode.EXT_LAUNCH_FAILED, clock.instant());
            saveQuietly(f);
            store.event(id, clock.instant(), "FAILED", Map.of("code", ErrorCode.EXT_LAUNCH_FAILED.code()));
            err.println("nekkora-lancar: " + ErrorCode.EXT_LAUNCH_FAILED.code() + " " + ErrorCode.EXT_LAUNCH_FAILED.description());
            return new Result(EXIT_NOT_STARTED, Optional.of(id), ExecutionState.FAILED, ErrorCode.EXT_LAUNCH_FAILED);
        }
        ProcessOwner owner = ProcessOwner.of(p, b.interpreter().toString());
        r = r.withOwner(owner).moveTo(ExecutionState.RUNNING, null, clock.instant());
        saveQuietly(r); // falha aqui não interrompe o processo do cliente: o desfecho é gravado de novo no fim
        store.event(id, clock.instant(), "RUNNING", Map.of("pid", owner.pid()));

        int exit;
        while (true) {
            try {
                exit = p.waitFor();
                break;
            } catch (InterruptedException e) {
                // o lançador não abandona o processo do cliente por interrupção da própria linha
            }
        }
        Outcome o = classify(exit, readSignal(signal));
        ExecutionRecord end = r.withExit(exit, Map.of(), 0).moveTo(o.state(), o.code(), clock.instant());
        saveQuietly(end);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("exit", (long) exit);
        if (o.code() != null) f.put("code", o.code().code());
        store.event(id, clock.instant(), o.state().name(), f);
        return new Result(exit, Optional.of(id), o.state(), o.code());
    }

    /** Desfecho do monitoramento (contrato M1, sec. 2): código 0 nunca é, sozinho, sucesso de negócio. */
    public record Outcome(ExecutionState state, ErrorCode code) {}

    public enum Signal { NONE, BUSINESS_SUCCESS, BUSINESS_FAILURE }

    public static Outcome classify(int exitCode, Signal s) {
        if (s == Signal.BUSINESS_FAILURE) return new Outcome(ExecutionState.FAILED, ErrorCode.EXT_BUSINESS_FAILURE);
        if (exitCode != 0) return new Outcome(ExecutionState.FAILED, ErrorCode.EXT_EXIT_NONZERO);
        if (s == Signal.BUSINESS_SUCCESS) return new Outcome(ExecutionState.SUCCEEDED, null);
        return new Outcome(ExecutionState.SUCCEEDED, ErrorCode.EXT_EXIT_ZERO_NO_SIGNAL);
    }

    /** O sinal OPCIONAL do script: só o esquema fechado conta; qualquer outra coisa = sem sinal (nada é presumido). */
    static Signal readSignal(Path f) {
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > 4096) return Signal.NONE;
            Map<String, Object> m = JsonReader.object(Files.readString(f, StandardCharsets.UTF_8));
            if (!SIGNAL_SCHEMA.equals(JsonReader.text(m, "schema")) || !"1.0.0".equals(JsonReader.text(m, "version"))) return Signal.NONE;
            String o = JsonReader.text(m, "outcome");
            return "BUSINESS_SUCCESS".equals(o) ? Signal.BUSINESS_SUCCESS : "BUSINESS_FAILURE".equals(o) ? Signal.BUSINESS_FAILURE : Signal.NONE;
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            return Signal.NONE;
        }
    }

    /** Posse do PRÓPRIO lançador: a reconciliação do Agent só toca a execução quando ele já não existe. */
    private static void writeLauncher(Path dir) throws IOException {
        ProcessHandle self = ProcessHandle.current();
        long start = self.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
        String exe = self.info().command().orElse("");
        String json = "{\"pid\": " + self.pid() + ", \"startMillis\": " + start + ", \"executableDigest\": \"" + ProcessOwner.digest(exe) + "\"}\n";
        Files.writeString(dir.resolve(LAUNCHER_FILE), json, StandardCharsets.UTF_8);
    }

    /** O lançador dono desta execução (se gravado). */
    public static Optional<ProcessOwner> launcherOf(Path runDir) {
        try {
            Map<String, Object> m = JsonReader.object(Files.readString(runDir.resolve(LAUNCHER_FILE), StandardCharsets.UTF_8));
            return Optional.of(new ProcessOwner((Long) m.get("pid"), (Long) m.get("startMillis"), JsonReader.text(m, "executableDigest")));
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            return Optional.empty();
        }
    }

    private void saveQuietly(ExecutionRecord r) {
        try {
            store.save(r);
        } catch (ExecRefused e) {
            // o processo do cliente nunca é afetado por falha de gravação; a reconciliação trata o que ficar aberto
        }
    }

    private static Result refuse(java.io.PrintStream err, ErrorCode c, int exit) {
        err.println("nekkora-lancar: " + c.code() + " " + c.description());
        return new Result(exit, Optional.empty(), null, c);
    }
}
