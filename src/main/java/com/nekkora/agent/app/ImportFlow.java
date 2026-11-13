package com.nekkora.agent.app;

import com.nekkora.agent.pack.ImportProblem;
import com.nekkora.agent.pack.ImportedProject;
import com.nekkora.agent.pack.PackageHistory;
import com.nekkora.agent.pack.PackagePlan;
import com.nekkora.agent.pack.Packager;
import com.nekkora.agent.pack.ProjectImporter;
import com.nekkora.agent.pack.SafeZip;
import com.nekkora.agent.platform.PlatformService.AuthorizedVersion;
import com.nekkora.agent.platform.PlatformService.LinkCheck;
import com.nekkora.agent.platform.PlatformService.LinkCheckOutcome;
import com.nekkora.agent.platform.PlatformService.LinkRef;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * AGENT-PACOTES-001 T02: o assistente "Importar projeto (.zip)" sem tela (a tela só mostra o {@link View} e chama as ações).
 * <ul>
 *   <li>Só começa e só conclui com o vínculo CONFIRMADO ONLINE ({@link Link#confirm}); a conclusão confirma de novo antes de
 *       gravar o pacote.</li>
 *   <li>O estado fica preso ao vínculo confirmado ({@link LinkRef}: origem, organização, Agent — novo a cada pareamento — e
 *       Worker) e a uma GERAÇÃO local: mudar o vínculo, desvincular, cancelar ou recomeçar invalida tudo o que estava em
 *       andamento; uma resposta atrasada de uma geração anterior é descartada e nunca habilita nada.</li>
 *   <li>Nada é criado na Platform: as únicas chamadas são a presença e a leitura das autorizações da máquina. Gerar o pacote
 *       não reserva versão, não cria rascunho, não publica e não executa nada.</li>
 * </ul>
 * Limite honesto: uma revogação feita na Platform DEPOIS da última confirmação só é vista na próxima consulta; a Platform
 * confere de novo cada autorização quando uma execução é registrada.
 */
public final class ImportFlow {

    /** O vínculo, visto pelo assistente. */
    public interface Link {
        /** Confirmação online (fora da EDT). */
        LinkCheck confirm();

        /** O vínculo atual, sem rede. */
        Optional<LinkRef> current();
    }

    public enum Stage {
        /** Confirmando o vínculo com a Platform. */
        CHECKING,
        /** Vínculo não confirmado: importação bloqueada ({@link View#check} diz por quê). */
        BLOCKED,
        /** Vínculo confirmado: escolha o ZIP. */
        READY,
        /** Lendo o ZIP. */
        READING,
        /** ZIP lido: projeto, entrada e requisitos. */
        CONFIGURE,
        /** Confirmando o vínculo de novo e gerando o pacote. */
        BUILDING,
        /** Pacote gerado. */
        DONE,
        /** O vínculo mudou durante o assistente: tudo descartado; é preciso recomeçar. */
        INVALIDATED,
        /** Assistente fechado. */
        CLOSED
    }

    /** Projeto novo (preparação local; não cria nada na Platform) ou existente (escolhido pelo identificador estável). */
    public enum ProjectMode { NEW, EXISTING }

    /** Uma automação do catálogo parcial: as versões autorizadas a ESTA máquina (sem nome: o contrato não traz). */
    public record CatalogProject(UUID automationId, UUID workspaceId, List<AuthorizedVersion> versions) {}

    /**
     * Sugestão de versão declarada. {@code version} vazio = sem sugestão (a pessoa informa). {@code reuse}: pacote local de MESMO
     * conteúdo (reutilizar em vez de gerar outra versão). {@code authorized}: o pacote seria idêntico a uma versão autorizada.
     */
    public record Suggestion(Optional<String> version, String text, Optional<PackageHistory.Entry> reuse, Optional<AuthorizedVersion> authorized) {}

    /** O que a tela mostra. */
    public record View(Stage stage, LinkCheck check, ImportedProject project, ImportProblem problem, Packager.Built built, String note, long generation) {}

    private final Link link;
    private final ProjectImporter importer;
    private final PackageHistory history;
    private final Clock clock;
    private final Executor background;
    private final Executor deliver;
    private final Consumer<View> listener;
    private final AtomicLong generation = new AtomicLong();
    private volatile View view;
    private volatile LinkRef session;
    private volatile boolean opCancelled;

    public ImportFlow(Link link, ProjectImporter importer, PackageHistory history, Clock clock, Executor background, Executor deliver, Consumer<View> listener) {
        this.link = link;
        this.importer = importer;
        this.history = history;
        this.clock = clock;
        this.background = background;
        this.deliver = deliver;
        this.listener = listener;
        this.view = new View(Stage.CHECKING, null, null, null, null, null, 0);
    }

    public View view() {
        return view;
    }

    /** O vínculo a que o assistente está preso (vazio antes da confirmação). */
    public Optional<LinkRef> session() {
        return Optional.ofNullable(session);
    }

    // ------------------------------------------------------------------ ações

    /** Começa (ou recomeça): descarta tudo, confirma o vínculo online. */
    public void start() {
        long g = reset();
        publish(new View(Stage.CHECKING, null, null, null, null, null, g));
        background.execute(() -> {
            LinkCheck c = link.confirm();
            deliver(g, () -> {
                // confirmado, mas o vínculo local já é outro (pareado de novo durante a consulta): não vale para o atual
                if (c.confirmed() && link.current().map(c.link()::equals).orElse(false)) {
                    session = c.link();
                    publish(new View(Stage.READY, c, null, null, null, null, g));
                } else {
                    session = null;
                    publish(new View(Stage.BLOCKED, c, null, null, null, null, g));
                }
            });
        });
    }

    /** O ZIP escolhido: lido numa pasta privada (nada executado, nada enviado). */
    public void chooseZip(Path zip) {
        View v = view;
        if (session == null || !List.of(Stage.READY, Stage.CONFIGURE, Stage.DONE).contains(v.stage())) return;
        if (!linkStillSame()) {
            invalidate();
            return;
        }
        long g = next();
        closeProject(v.project());
        LinkCheck c = v.check();
        publish(new View(Stage.READING, c, null, null, null, null, g));
        background.execute(() -> {
            ImportedProject p = null;
            ImportProblem problem = null;
            try {
                p = importer.open(zip, cancel(g));
            } catch (ImportProblem e) {
                problem = e;
            }
            ImportedProject opened = p;
            ImportProblem failed = problem;
            deliver(g, () -> {
                if (!linkStillSame()) {
                    closeProject(opened);
                    invalidate();
                    return;
                }
                publish(new View(failed == null ? Stage.CONFIGURE : Stage.READY, c, opened, failed, null, null, g));
            }, () -> closeProject(opened)); // resposta de uma geração anterior: descartada, e a pasta dela apagada
        });
    }

    /**
     * Gera o pacote: confirma o vínculo ONLINE de novo (mesmo vínculo da sessão), grava pelo empacotador (temporário →
     * conferência → troca de nome) e registra a associação local. Se o vínculo mudar no meio, a troca de nome não acontece.
     */
    public void build(PackagePlan plan, ProjectMode mode, UUID automationId, Path outDir, boolean overwrite) {
        View v = view;
        if (v.stage() != Stage.CONFIGURE || v.project() == null || session == null) return;
        long g = next();
        ImportedProject p = v.project();
        LinkRef s = session;
        publish(new View(Stage.BUILDING, v.check(), p, null, null, null, g));
        background.execute(() -> {
            LinkCheck again = link.confirm();
            if (!again.confirmed() || !s.equals(again.link())) {
                boolean changed = again.confirmed() || again.link() != null && !s.equals(again.link());
                deliver(g, () -> {
                    if (changed || !linkStillSame()) invalidate();
                    else publish(new View(Stage.CONFIGURE, again, p, null, null, null, g)); // a tela mostra o motivo; tentar de novo
                });
                return;
            }
            Packager.Built built = null;
            ImportProblem problem = null;
            try {
                built = Packager.build(p, plan, outDir, overwrite, cancel(g));
            } catch (ImportProblem e) {
                problem = e;
            }
            String note = null;
            if (built != null && generation.get() == g && linkStillSame()) {
                String key = mode == ProjectMode.EXISTING && automationId != null ? PackageHistory.automationKey(automationId) : PackageHistory.localKey(plan.name());
                try {
                    history.record(new PackageHistory.Entry(s.origin(), s.organizationId(), s.agentId(), key, plan.version(), built.contentIdentity(), built.fingerprint(),
                            built.zip(), clock.instant()));
                } catch (java.io.IOException e) {
                    note = "O pacote foi gerado, mas a associação local com a organização não pôde ser gravada.";
                }
            }
            Packager.Built ok = built;
            ImportProblem failed = problem;
            String n = note;
            deliver(g, () -> {
                if (failed != null && failed.code() == ImportProblem.Code.CANCELLED && !linkStillSame()) {
                    invalidate();
                    return;
                }
                publish(new View(ok != null ? Stage.DONE : Stage.CONFIGURE, again, p, failed, ok, n, g));
            });
        });
    }

    /** Cancelar: interrompe o que estiver em andamento e descarta a pasta da operação. Nada remoto é criado ou desfeito. */
    public void cancel() {
        opCancelled = true;
        View v = view;
        long g = reset();
        closeProject(v.project());
        publish(new View(Stage.CLOSED, v.check(), null, null, null, null, g));
    }

    /** O canal mudou (vínculo, desvínculo, recusa): se o vínculo da sessão não é mais o atual, tudo é descartado. */
    public void platformChanged() {
        if (session != null && !linkStillSame()) invalidate();
    }

    // ------------------------------------------------------------------ sugestões (sem rede)

    /** As automações do catálogo parcial (versões autorizadas a esta máquina), agrupadas pelo identificador. */
    public List<CatalogProject> catalog() {
        View v = view;
        if (v.check() == null || !v.check().catalog().available()) return List.of();
        Map<UUID, List<AuthorizedVersion>> by = new LinkedHashMap<>();
        for (AuthorizedVersion a : v.check().catalog().items()) by.computeIfAbsent(a.automationId(), k -> new ArrayList<>()).add(a);
        List<CatalogProject> out = new ArrayList<>();
        by.forEach((k, list) -> out.add(new CatalogProject(k, list.get(0).workspaceId(), List.copyOf(list))));
        return out;
    }

    /**
     * Sugestão da versão declarada. Regras: projeto declarado novo, catálogo lido e nenhum pacote local com esse nome → 1.0.0;
     * projeto com pacotes gerados nesta máquina → o terceiro número + 1 da maior; conteúdo igual a um pacote local → reutilizar
     * (sem versão nova); conteúdo igual a uma versão autorizada → dizer isso. Falha de consulta nunca vira "1.0.0".
     */
    public Suggestion suggest(ProjectMode mode, String name, UUID automationId, PackagePlan draft) {
        View v = view;
        LinkRef s = session;
        if (s == null || v.project() == null) return new Suggestion(Optional.empty(), "Sem sugestão: o vínculo não está confirmado.", Optional.empty(), Optional.empty());
        Optional<PackageHistory.Entry> reuse = Optional.empty();
        Optional<AuthorizedVersion> authorized = Optional.empty();
        if (draft != null) {
            try {
                Packager.Preview pv = Packager.preview(v.project(), draft);
                reuse = history.sameContent(s.origin(), s.organizationId(), pv.contentIdentity());
                authorized = v.check().catalog().items().stream().filter(a -> a.fingerprint().equals(pv.fingerprint()))
                        .filter(a -> mode != ProjectMode.EXISTING || a.automationId().equals(automationId)).findFirst();
            } catch (ImportProblem e) {
                // escolhas incompletas: só a sugestão de número
            }
        }
        boolean catalogOk = v.check() != null && v.check().catalog().available();
        Optional<AuthorizedVersion> auth = authorized;
        if (reuse.isPresent()) {
            return new Suggestion(Optional.empty(), "O mesmo conteúdo já foi empacotado nesta máquina como versão " + reuse.get().version()
                    + ". Reutilize esse pacote em vez de gerar uma versão nova.", reuse, authorized);
        }
        if (mode == ProjectMode.EXISTING) {
            if (automationId == null) return new Suggestion(Optional.empty(), "Escolha a automação pelo identificador.", reuse, authorized);
            Optional<String> next = history.nextVersion(s.origin(), s.organizationId(), PackageHistory.automationKey(automationId));
            return next.map(n -> new Suggestion(Optional.of(n), "Sugestão local " + n + ": a próxima depois da maior versão gerada NESTA máquina para esta automação. "
                    + "A Platform não informa as versões desta automação à máquina; nada foi reservado nela.", Optional.<PackageHistory.Entry>empty(), auth))
                    .orElse(new Suggestion(Optional.empty(), "Sem sugestão: a credencial desta máquina não consegue consultar as versões desta automação na "
                            + "Platform e esta máquina ainda não gerou pacotes para ela. Informe a versão declarada no pacote.", Optional.empty(), authorized));
        }
        if (!catalogOk) {
            return new Suggestion(Optional.empty(), "Sem sugestão: a consulta à Platform não foi concluída, então não dá para tratar o projeto como novo. "
                    + "Informe a versão ou tente a consulta de novo.", Optional.empty(), authorized);
        }
        if (name == null || name.isBlank()) return new Suggestion(Optional.empty(), "Informe o nome do projeto.", Optional.empty(), authorized);
        List<PackageHistory.Entry> same = history.forProject(s.origin(), s.organizationId(), PackageHistory.localKey(name));
        if (!same.isEmpty()) {
            Optional<String> next = history.nextVersion(s.origin(), s.organizationId(), PackageHistory.localKey(name));
            return new Suggestion(next, "Esta máquina já gerou um pacote com este nome para esta organização (versão " + same.get(0).version() + "). Nome igual não "
                    + "prova que é o mesmo projeto: confira. " + next.map(n -> "Sugestão local: " + n + ".").orElse(""), Optional.empty(), authorized);
        }
        return new Suggestion(Optional.of("1.0.0"), "Sugestão local 1.0.0 para um projeto novo. A Platform não lista projetos para esta máquina, então ela não "
                + "confirmou que o projeto é novo; nada foi reservado nem publicado.", Optional.empty(), authorized);
    }

    // ------------------------------------------------------------------ apoio

    private SafeZip.Cancel cancel(long g) {
        opCancelled = false;
        return () -> opCancelled || generation.get() != g || !linkStillSame();
    }

    private boolean linkStillSame() {
        LinkRef s = session;
        return s != null && link.current().map(s::equals).orElse(false);
    }

    private void invalidate() {
        opCancelled = true;
        View v = view;
        long g = reset();
        closeProject(v.project());
        publish(new View(Stage.INVALIDATED, v.check(), null, null, null,
                "O vínculo desta máquina mudou (desvinculada, recusada ou pareada de novo). O que estava em andamento foi descartado; nada foi "
                        + "associado ao vínculo novo. Comece de novo.", g));
    }

    private long reset() {
        session = null;
        return generation.incrementAndGet();
    }

    private long next() {
        return generation.incrementAndGet();
    }

    private void closeProject(ImportedProject p) {
        if (p != null) importer.close(p);
    }

    /** Entrega na linha da tela, só se a geração ainda é a atual. */
    private void deliver(long g, Runnable r) {
        deliver(g, r, () -> {});
    }

    /** Idem; {@code stale} roda se a geração mudou (a resposta é descartada, nunca aplicada). */
    private void deliver(long g, Runnable r, Runnable stale) {
        if (generation.get() != g) {
            stale.run();
            return;
        }
        deliver.execute(() -> {
            if (generation.get() == g) r.run();
            else stale.run();
        });
    }

    private void publish(View v) {
        view = v;
        listener.accept(v);
    }

    static boolean refused(LinkCheckOutcome o) {
        return o == LinkCheckOutcome.CREDENTIAL_REFUSED;
    }
}
