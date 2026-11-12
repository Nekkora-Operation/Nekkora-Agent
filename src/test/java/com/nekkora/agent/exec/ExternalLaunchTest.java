package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-PYTHON-001 (Marco 1): o lançador com processos REAIS de Python (o interpretador dos testes; sem ele, PULADO). Prova:
 * o código de saída do script volta ao agendador; código 0 sem sinal NÃO é sucesso de negócio; o sinal explícito é opcional;
 * vínculo divergente ou sem autorização não inicia nada; o pedido de vínculo é gravado antes do registro; argumento sentinela
 * não aparece em nenhum arquivo do Agent; lançador morto → desfecho desconhecido só depois que ele não existe.
 */
class ExternalLaunchTest {
    static final String SENTINEL = "SENTINELA-AP1-9f3c";

    @TempDir
    Path tmp;

    Path python;
    Path work;
    Path script;
    RunStore runs;
    ExternalBindings bindings;
    final UUID automation = UUID.randomUUID();
    final UUID authorization = UUID.randomUUID();
    final List<Path> linkRequests = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        python = ExecFixtures.python();
        assumeTrue(python != null, "sem Python de teste (NEKKORA_TEST_PYTHON): pulado, nunca dado por aprovado");
        work = Files.createDirectories(tmp.resolve("cliente"));
        script = work.resolve("tarefa.py");
        Files.writeString(script, """
                import os, sys, json
                args = sys.argv[1:]
                with open("efeito.txt", "a", encoding="utf-8") as f:
                    f.write("x\\n")
                if "--sinalizar" in args and os.environ.get("NEKKORA_SINAL"):
                    json.dump({"schema": "nekkora-agent/external-signal", "version": "1.0.0", "outcome": "BUSINESS_SUCCESS"}, open(os.environ["NEKKORA_SINAL"], "w"))
                if "--sinal-falha" in args and os.environ.get("NEKKORA_SINAL"):
                    json.dump({"schema": "nekkora-agent/external-signal", "version": "1.0.0", "outcome": "BUSINESS_FAILURE"}, open(os.environ["NEKKORA_SINAL"], "w"))
                print("saida do cliente " + " ".join(args))
                sys.exit(int(os.environ.get("CODIGO", "0")) if "--codigo" not in args else int(args[args.index("--codigo") + 1]))
                """, StandardCharsets.UTF_8);
        runs = new RunStore(tmp.resolve("dados").resolve("execucoes"), AtomicWriter.DEFAULT);
        bindings = new ExternalBindings(tmp.resolve("dados").resolve("externo").resolve("vinculos.json"), AtomicWriter.DEFAULT);
        bindings.approve("relatorio", automation, python, script, work, Instant.now());
    }

    ExternalLaunch launcher(Optional<UUID> auth) {
        return new ExternalLaunch(runs, bindings, a -> a.equals(automation) ? auth : Optional.empty(), (dir, id, z) -> {
            Files.writeString(dir.resolve("plataforma.json"), "{\"authorizationId\": \"" + z + "\"}\n");
            linkRequests.add(dir);
        }, Clock.systemUTC());
    }

    ExternalLaunch.Result launch(List<String> args) {
        return launcher(Optional.of(authorization)).run("relatorio", python, script, args, work, new PrintStream(new ByteArrayOutputStream()));
    }

    long effects() throws Exception {
        Path f = work.resolve("efeito.txt");
        return Files.exists(f) ? Files.readAllLines(f).size() : 0;
    }

    @Test
    void codigoZeroSemSinalNaoESucessoDeNegocioEOCodigoVoltaAoAgendador() throws Exception {
        ExternalLaunch.Result r = launch(List.of("--entrada", "x.csv"));
        assertEquals(0, r.exitCode());
        ExecutionRecord rec = runs.record(r.execution().orElseThrow()).orElseThrow();
        assertEquals(ExecutionState.SUCCEEDED, rec.state());
        assertEquals(ErrorCode.EXT_EXIT_ZERO_NO_SIGNAL, rec.code(), "código 0 = término sem erro sinalizado, nunca sucesso de negócio");
        assertEquals(0, rec.exitCode());
        assertEquals(ExecutionPackage.Kind.EXTERNAL_MONITORED, rec.kind());
        assertEquals(ExecutionRecord.Origin.EXTERNAL_SCHEDULER, rec.origin());
        assertNull(rec.fingerprint(), "monitoramento sem impressão");
        assertTrue(rec.started() != null && rec.ended() != null);
        assertEquals(1, effects());
        assertEquals(1, linkRequests.size(), "um pedido de vínculo por invocação");
        assertTrue(Files.exists(runs.dir(rec.id()).resolve(ExternalLaunch.LAUNCHER_FILE)));
    }

    /**
     * PLATFORM-AGENT-V2-004 T13: um desvínculo confirmado ENTRE a primeira conferência da trava e a marca do lançador é visto
     * na segunda conferência (depois de lancador.json): nada é iniciado, sem estado.json (a pasta nunca é adotada).
     */
    @Test
    void desvinculoConfirmadoDuranteOLancamentoImpedeOInicio() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ExternalLaunch l = new ExternalLaunch(runs, bindings, a -> calls.incrementAndGet() == 1 ? Optional.of(authorization) : Optional.empty(), (dir, id, z) -> {
            Files.writeString(dir.resolve("plataforma.json"), "{\"authorizationId\": \"" + z + "\"}\n");
            linkRequests.add(dir);
        }, Clock.systemUTC());
        ExternalLaunch.Result r = l.run("relatorio", python, script, List.of(), work, new PrintStream(new ByteArrayOutputStream()));
        assertEquals(ErrorCode.EXT_NOT_AUTHORIZED, r.code());
        assertEquals(2, calls.get(), "a trava foi conferida de novo depois da marca do lançador");
        assertEquals(0, effects(), "o processo do cliente nunca foi criado");
        assertEquals(1, linkRequests.size());
        assertFalse(Files.exists(linkRequests.get(0).resolve("estado.json")), "sem registro: a pasta nunca vira execução adotada");
    }

    @Test
    void erroComEfeitoPreservaOCodigoDeSaida() throws Exception {
        ExternalLaunch.Result r = launch(List.of("--codigo", "7"));
        assertEquals(7, r.exitCode());
        ExecutionRecord rec = runs.record(r.execution().orElseThrow()).orElseThrow();
        assertEquals(ExecutionState.FAILED, rec.state());
        assertEquals(ErrorCode.EXT_EXIT_NONZERO, rec.code());
        assertEquals(7, rec.exitCode());
        assertEquals(1, effects(), "o efeito aconteceu uma vez");
    }

    @Test
    void sinalExplicitoDoClienteDecideONegocio() throws Exception {
        ExecutionRecord ok = runs.record(launch(List.of("--sinalizar")).execution().orElseThrow()).orElseThrow();
        assertEquals(ExecutionState.SUCCEEDED, ok.state());
        assertNull(ok.code(), "sucesso de negócio sinalizado");
        ExecutionRecord bad = runs.record(launch(List.of("--sinal-falha")).execution().orElseThrow()).orElseThrow();
        assertEquals(ExecutionState.FAILED, bad.state());
        assertEquals(ErrorCode.EXT_BUSINESS_FAILURE, bad.code());
        assertEquals(0, bad.exitCode(), "o código de saída continua o observado");
    }

    @Test
    void semAutorizacaoOuVinculoDivergenteNadaEIniciado() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ExternalLaunch.Result none = launcher(Optional.empty()).run("relatorio", python, script, List.of(), work, new PrintStream(err));
        assertEquals(ExternalLaunch.EXIT_NOT_STARTED, none.exitCode());
        assertTrue(err.toString().contains("AGT-EXT-012"));
        Path other = Files.writeString(work.resolve("outro.py"), "print(1)\n");
        assertEquals(ExternalLaunch.EXIT_USAGE, launcher(Optional.of(authorization)).run("relatorio", python, other, List.of(), work,
                new PrintStream(new ByteArrayOutputStream())).exitCode(), "script diferente do aprovado");
        assertEquals(ExternalLaunch.EXIT_USAGE, launcher(Optional.of(authorization)).run("relatorio", python, script, List.of(), tmp,
                new PrintStream(new ByteArrayOutputStream())).exitCode(), "pasta de trabalho diferente da aprovada");
        assertEquals(ExternalLaunch.EXIT_USAGE, launcher(Optional.of(authorization)).run("inexistente", python, script, List.of(), work,
                new PrintStream(new ByteArrayOutputStream())).exitCode());
        assertEquals(0, effects(), "nenhum processo do cliente rodou");
        assertTrue(runs.all().isEmpty(), "nenhuma execução registrada");
    }

    @Test
    void argumentoSentinelaNaoVazaParaOsArquivosDoAgent() throws Exception {
        ExternalLaunch.Result r = launch(List.of("--token", SENTINEL));
        assertEquals(0, r.exitCode());
        try (Stream<Path> s = Files.walk(tmp.resolve("dados"))) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                if (p.getFileName().toString().equals("vinculos.json")) continue; // o vínculo local guarda os caminhos aprovados, nunca argumentos
                assertFalse(Files.readString(p, StandardCharsets.ISO_8859_1).contains(SENTINEL), "sentinela em " + p.getFileName());
                assertFalse(Files.readString(p, StandardCharsets.ISO_8859_1).contains("saida do cliente"), "saída do cliente em " + p.getFileName());
            }
        }
        assertFalse(Files.readString(tmp.resolve("dados").resolve("externo").resolve("vinculos.json")).contains(SENTINEL));
    }

    @Test
    void lancadorMortoViraDesfechoDesconhecidoSoQuandoNaoExisteMais() throws Exception {
        ExecutionId id = ExecutionId.random();
        Path d = runs.dir(id);
        Files.createDirectories(d);
        // posse de um lançador VIVO (este processo de teste): a reconciliação não toca
        ProcessHandle self = ProcessHandle.current();
        Files.writeString(d.resolve(ExternalLaunch.LAUNCHER_FILE), "{\"pid\": " + self.pid() + ", \"startMillis\": "
                + self.info().startInstant().orElseThrow().toEpochMilli() + ", \"executableDigest\": \"" + ProcessOwner.digest(self.info().command().orElseThrow())
                + "\"}\n");
        ExecutionRecord running = new ExecutionRecord(id, ExecutionPackage.Kind.EXTERNAL_MONITORED, "python externo", null, ExecutionState.READY, null,
                Instant.now(), null, null, null, null, java.util.Map.of(), 0, 0, false, 0).withOrigin(ExecutionRecord.Origin.EXTERNAL_SCHEDULER)
                .moveTo(ExecutionState.STARTING, null, Instant.now()).moveTo(ExecutionState.RUNNING, null, Instant.now());
        runs.save(running);
        assertEquals(0, ExternalRuns.reconcile(runs, Clock.systemUTC()));
        assertEquals(ExecutionState.RUNNING, runs.record(id).orElseThrow().state());
        // lançador que já não existe (PID que não confere): desconhecido, sem relançar
        Files.writeString(d.resolve(ExternalLaunch.LAUNCHER_FILE), "{\"pid\": 999999, \"startMillis\": 1, \"executableDigest\": \"sha256:" + "0".repeat(64) + "\"}\n");
        assertEquals(1, ExternalRuns.reconcile(runs, Clock.systemUTC()));
        ExecutionRecord closed = runs.record(id).orElseThrow();
        assertEquals(ExecutionState.OUTCOME_UNKNOWN, closed.state());
        assertEquals(ErrorCode.EXT_LAUNCHER_LOST, closed.code());
        assertEquals(0, ExternalRuns.reconcile(runs, Clock.systemUTC()), "terminal: nada mais muda");
        // o controlador da janela nunca adota execução do lançador
        assertEquals(0, effects());
    }

    @Test
    void classificacaoDoDesfecho() {
        assertEquals(new ExternalLaunch.Outcome(ExecutionState.SUCCEEDED, ErrorCode.EXT_EXIT_ZERO_NO_SIGNAL), ExternalLaunch.classify(0, ExternalLaunch.Signal.NONE));
        assertEquals(new ExternalLaunch.Outcome(ExecutionState.SUCCEEDED, null), ExternalLaunch.classify(0, ExternalLaunch.Signal.BUSINESS_SUCCESS));
        assertEquals(new ExternalLaunch.Outcome(ExecutionState.FAILED, ErrorCode.EXT_EXIT_NONZERO), ExternalLaunch.classify(2, ExternalLaunch.Signal.BUSINESS_SUCCESS));
        assertEquals(new ExternalLaunch.Outcome(ExecutionState.FAILED, ErrorCode.EXT_BUSINESS_FAILURE), ExternalLaunch.classify(0, ExternalLaunch.Signal.BUSINESS_FAILURE));
    }
}
