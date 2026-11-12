package com.nekkora.agent.app;

import com.nekkora.agent.exec.ApprovalStore;
import com.nekkora.agent.exec.ExecRefused;
import com.nekkora.agent.exec.ExecutionController;
import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.PackageShelf;
import com.nekkora.agent.exec.PythonRuntime;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.platform.LocalGate;
import com.nekkora.agent.platform.PlatformService;
import com.nekkora.agent.storage.AtomicWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AGENT-PYTHON-001 (Marco 2): liga a caixa de pedidos do transporte ({@link PlatformService#runRequests()}) ao executor, sem
 * que um chame o outro. Decide cada pedido RECEBIDO só por fatos LOCAIS — autorização guardada usável, pacote presente e
 * APROVADO nesta máquina (a aprovação continua distinta da autorização), ambiente Python compatível, máquina livre — e grava a
 * decisão antes do envio. Só lança um pedido cuja aceitação a Platform CONFIRMOU, com o {@code agentExecutionId} reservado e
 * o pedido de vínculo gravado antes do STARTING.
 * <p>
 * Recuperação (o processo só é criado depois do {@code estado.json} STARTING):
 * <ul>
 *   <li>pasta da execução com o pedido de vínculo E o {@code estado.json}: o lançamento foi tentado; a reconciliação do
 *       supervisor diz o resto (uma queda entre STARTING e a posse vira {@code OUTCOME_UNKNOWN}, visível na Platform);</li>
 *   <li>sem {@code estado.json} (ou recusado antes do vínculo): o processo comprovadamente não foi criado → NOT_STARTED;</li>
 *   <li>nada é relançado automaticamente.</li>
 * </ul>
 */
final class RunRequestCoordinator implements AutoCloseable {
    static final Duration PERIOD = Duration.ofSeconds(2);

    private final PlatformService platform;
    private final ExecutionController controller;
    private final PackageShelf shelf;
    private final ApprovalStore approvals;
    private final RunStore runs;
    private final Path probeDir;
    private final Clock clock;
    private ScheduledExecutorService exec;

    RunRequestCoordinator(PlatformService platform, ExecutionController controller, PackageShelf shelf, ApprovalStore approvals, RunStore runs, Path probeDir,
                          Clock clock) {
        this.platform = platform;
        this.controller = controller;
        this.shelf = shelf;
        this.approvals = approvals;
        this.runs = runs;
        this.probeDir = probeDir;
        this.clock = clock;
    }

    synchronized void start() {
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nekkora-agent-pedidos");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::tick, PERIOD.toMillis(), PERIOD.toMillis(), TimeUnit.MILLISECONDS);
    }

    void tick() {
        try {
            for (PlatformService.RunRequestView v : platform.runRequests()) {
                switch (v.stage()) {
                    case "RECEIVED" -> decide(v);
                    case "CONFIRMED" -> launch(v);
                    default -> {
                    }
                }
            }
        } catch (RuntimeException e) {
            // a linha de fundo continua; nenhuma execução é afetada
        }
    }

    /** Recusa (com o motivo) ou aceitação (com o identificador reservado). Gravada pelo transporte antes do envio. */
    void decide(PlatformService.RunRequestView v) {
        String refusal = refusal(v);
        if (refusal != null) platform.decideRunRequest(v.runRequestId(), "REFUSED", refusal, null);
        else platform.decideRunRequest(v.runRequestId(), "ACCEPTED", null, ExecutionId.random());
    }

    String refusal(PlatformService.RunRequestView v) {
        if (!clock.instant().isBefore(v.validUntil())) return "EXPIRED";
        String hex = v.fingerprint().substring("sha256:".length());
        if (!platform.authorizationUsable(v.authorizationId(), hex, v.rule())) return "AUTHORIZATION_UNUSABLE";
        Optional<PackageShelf.Entry> e = shelf.find(v.fingerprint());
        if (e.isEmpty()) return "PACKAGE_NOT_PRESENT";
        ExecutionPackage pkg;
        try {
            pkg = ExecutionPackage.inspect(e.get().dir());
        } catch (ExecRefused x) {
            return "PACKAGE_NOT_PRESENT"; // pasta alterada ou ilegível: o pacote aprovado não está mais lá
        }
        if (!pkg.fingerprint().equals(v.fingerprint()) || !ExecutionPackage.ruleOf(pkg.kind()).equals(v.rule())) return "PACKAGE_NOT_PRESENT";
        if (!approvals.approved(pkg)) return "PACKAGE_NOT_APPROVED";
        try {
            PythonRuntime.Probe probe = PythonRuntime.probe(e.get().python(), probeDir);
            PythonRuntime.requireCompatible(probe, pkg);
            if (pkg.kind() == ExecutionPackage.Kind.EXTERNAL_PYTHON) PythonRuntime.requireDependencies(probe.python(), pkg);
        } catch (ExecRefused x) {
            return "ENVIRONMENT_INCOMPATIBLE";
        }
        if (controller.running()) return "BUSY";
        return null;
    }

    void launch(PlatformService.RunRequestView v) {
        ExecutionId id = new ExecutionId(v.executionId());
        Path dir = runs.dir(id);
        boolean linked = Files.exists(dir.resolve(LocalGate.LINK_REQUEST));
        boolean recorded = Files.exists(dir.resolve("estado.json"));
        if (Files.exists(dir)) {
            // já houve tentativa com este identificador: nunca outra. Com vínculo e registro, o lançamento foi tentado (o
            // supervisor diz o desfecho); sem eles, o processo comprovadamente não foi criado
            if (linked && recorded) platform.runRequestLaunched(v.runRequestId());
            else platform.runRequestNotStarted(v.runRequestId());
            return;
        }
        if (controller.running()) return; // aceito e confirmado; espera a vaga (o supervisor atual termina antes)
        Optional<PackageShelf.Entry> e = shelf.find(v.fingerprint());
        Optional<LocalGate.LinkRequest> link = platform.linkRequestFor(v.runRequestId());
        if (e.isEmpty() || link.isEmpty()) {
            platform.runRequestNotStarted(v.runRequestId());
            return;
        }
        ExecutionController.Request req = new ExecutionController.Request(e.get().dir(), v.fingerprint(), e.get().python(), null, e.get().outputRoot(),
                ExecutionController.DEFAULT_TIMEOUT, id, ExecutionRecord.Origin.PLATFORM_REQUEST,
                (runDir, x) -> {
                    // T13: dentro do start (com a trava do executor): um desvínculo confirmado depois da decisão impede o início
                    if (platform.unlinkPending()) throw new java.io.IOException("desvinculo pendente");
                    LocalGate.writeLinkRequest(runDir, link.get(), AtomicWriter.DEFAULT);
                });
        try {
            controller.start(req);
            platform.runRequestLaunched(v.runRequestId());
        } catch (ExecRefused x) {
            // recusa depois do vínculo e do STARTING (falha ao criar o processo): o registro conta o fato; antes disso, nada rodou
            if (Files.exists(dir.resolve(LocalGate.LINK_REQUEST)) && Files.exists(dir.resolve("estado.json"))) platform.runRequestLaunched(v.runRequestId());
            else platform.runRequestNotStarted(v.runRequestId());
        }
    }

    @Override
    public synchronized void close() {
        if (exec != null) exec.shutdownNow();
        exec = null;
    }
}
