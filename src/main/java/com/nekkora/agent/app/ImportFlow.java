package com.nekkora.agent.app;

import com.nekkora.agent.pack.ImportProblem;
import com.nekkora.agent.pack.ImportedProject;
import com.nekkora.agent.pack.PackageHistory;
import com.nekkora.agent.pack.PackagePlan;
import com.nekkora.agent.pack.Packager;
import com.nekkora.agent.pack.ProjectImporter;
import com.nekkora.agent.pack.SafeZip;
import com.nekkora.agent.platform.PlatformService.LinkCheck;
import com.nekkora.agent.platform.PlatformService.LinkCheckOutcome;
import com.nekkora.agent.platform.PlatformService.LinkRef;
import com.nekkora.agent.platform.PlatformService.SelectionCheck;
import com.nekkora.agent.platform.PlatformService.SelectionOutcome;
import com.nekkora.agent.platform.RemoteCatalog;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
 *   <li>Nada é criado na Platform: as chamadas são a presença, a leitura das autorizações e do CATÁLOGO da máquina
 *       (PLATFORM-AGENT-CATALOGO-001) e a revalidação da automação escolhida. Gerar o pacote não reserva versão, não cria
 *       rascunho, não publica e não executa nada.</li>
 *   <li>O catálogo é o subconjunto autorizado ao Worker desta máquina: nunca prova que um projeto não existe. Projeto
 *       existente é escolhido pelo identificador (nunca pelo nome do ZIP nem por nome igual); "novo" só conclui com o catálogo
 *       lido por inteiro na conclusão; projeto existente só conclui se a Platform confirmar, na conclusão, que ele continua
 *       visível a esta máquina. Versão sugerida é sempre sugestão, sem reserva.</li>
 * </ul>
 * Limite honesto: uma revogação feita na Platform DEPOIS da última confirmação só é vista na próxima consulta; a Platform
 * confere de novo cada autorização quando uma execução é registrada.
 */
public final class ImportFlow {

    /** O vínculo, visto pelo assistente. */
    public interface Link {
        /** Confirmação online (fora da EDT), com o catálogo da máquina. */
        LinkCheck confirm();

        /** O vínculo atual, sem rede. */
        Optional<LinkRef> current();

        /** Revalidação online da automação escolhida (fora da EDT). */
        SelectionCheck selection(UUID automationId);
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

    /** Comparação do pacote preparado com as versões visíveis do projeto escolhido. */
    public enum Comparison {
        /** Idêntico (mesma impressão, pela regra completa) a uma versão visível. */
        IDENTICAL,
        /** Diferente de todas as versões visíveis comparáveis. */
        DIFFERENT,
        /** Não deu para comparar (escolhas incompletas, versões sem versão declarada, catálogo não lido). */
        UNAVAILABLE,
        /** Não se aplica (projeto novo). */
        NOT_APPLICABLE
    }

    /**
     * Sugestão de versão declarada. {@code version} vazio = sem sugestão (a pessoa informa). Sempre SUGESTÃO: nada é reservado.
     * {@code reuse}: pacote gerado nesta máquina com o mesmo conteúdo. {@code identical}: versão visível na Platform idêntica ao
     * pacote preparado (com a versão declarada dela). {@code warnings}: o que a pessoa precisa conferir.
     */
    public record Suggestion(Optional<String> version, String text, Optional<PackageHistory.Entry> reuse, Optional<RemoteCatalog.Version> identical,
                             Comparison comparison, List<String> warnings) {
        public Suggestion {
            warnings = List.copyOf(warnings);
        }

        static Suggestion none(String text) {
            return new Suggestion(Optional.empty(), text, Optional.empty(), Optional.empty(), Comparison.UNAVAILABLE, List.of());
        }
    }

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
    private volatile boolean refreshing;

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
            // PLATFORM-AGENT-CATALOGO-001: a escolha do projeto vale só se a Platform a confirmar AGORA
            String blocked = null;
            RemoteCatalog cat = again.catalog();
            if (cat.state() == RemoteCatalog.State.UNSUPPORTED) {
                blocked = UNSUPPORTED;
            } else if (mode == ProjectMode.EXISTING) {
                if (automationId == null) {
                    blocked = "Escolha o projeto existente na lista.";
                } else {
                    SelectionCheck sel = link.selection(automationId);
                    if (sel.outcome() == SelectionOutcome.VISIBLE && s.equals(sel.link())) {
                        blocked = null;
                    } else if (sel.link() != null && !s.equals(sel.link())) {
                        deliver(g, this::invalidate);
                        return;
                    } else {
                        blocked = selectionBlocked(sel);
                    }
                }
            } else if (!cat.complete()) {
                blocked = "O catálogo da Platform não foi lido por inteiro agora" + why(cat) + ". Sem ele, o assistente não conclui um projeto como novo: falha "
                        + "de rede, página faltante ou falta de permissão não indicam que o projeto não existe. Tente de novo.";
            }
            if (blocked != null) {
                String b = blocked;
                deliver(g, () -> {
                    if (!linkStillSame()) invalidate();
                    else publish(new View(Stage.CONFIGURE, again, p, null, null, b, g));
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

    /**
     * Consulta a Platform de novo (vínculo e catálogo) sem descartar o ZIP lido. Uma consulta por vez: pedir de novo enquanto
     * uma está em andamento não cria outra.
     */
    public void refreshCatalog() {
        View v = view;
        if (session == null || refreshing || !List.of(Stage.READY, Stage.CONFIGURE, Stage.DONE).contains(v.stage())) return;
        if (!linkStillSame()) {
            invalidate();
            return;
        }
        refreshing = true;
        long g = next();
        LinkRef s = session;
        background.execute(() -> {
            LinkCheck c;
            try {
                c = link.confirm();
            } finally {
                refreshing = false;
            }
            LinkCheck checked = c;
            deliver(g, () -> {
                View now = view;
                boolean changed = checked.link() != null && !s.equals(checked.link());
                if (changed || !linkStillSame()) {
                    invalidate();
                    return;
                }
                publish(new View(now.stage(), checked, now.project(), now.problem(), now.built(), null, g));
            });
        });
    }

    /** O catálogo lido (completo ou parcial), por workspace e nome; vazio se não foi lido. */
    public List<RemoteCatalog.Project> catalog() {
        View v = view;
        // sem sessão (invalidado, fechado, antes da confirmação): nada do vínculo anterior fica escolhível
        if (session == null || v.check() == null || !v.check().confirmed() || !v.check().catalog().readable()) return List.of();
        List<RemoteCatalog.Project> out = new ArrayList<>(v.check().catalog().projects());
        out.sort(Comparator.comparing((RemoteCatalog.Project p) -> p.workspaceName().toLowerCase(Locale.ROOT))
                .thenComparing(p -> p.automationName().toLowerCase(Locale.ROOT)).thenComparing(p -> p.automationId().toString()));
        return out;
    }

    /**
     * Sugestão da versão declarada (PLATFORM-AGENT-CATALOGO-001). Projeto existente: a partir das versões VISÍVEIS a esta
     * máquina na Platform (fato remoto; o histórico local não prevalece), com a comparação pela regra completa da impressão
     * (a impressão inclui a versão: compara-se o pacote preparado COM a versão declarada de cada versão visível). Projeto novo:
     * "Pacote inicial 1.0.0" só com o catálogo lido por inteiro, sem afirmar que o nome está livre. Sempre sugestão, sem reserva.
     */
    public Suggestion suggest(ProjectMode mode, String name, UUID automationId, PackagePlan draft) {
        View v = view;
        LinkRef s = session;
        if (s == null || v.project() == null || v.check() == null) return Suggestion.none("Sem sugestão: o vínculo não está confirmado.");
        RemoteCatalog cat = v.check().catalog();
        List<String> warnings = new ArrayList<>();
        Packager.Preview preview = null;
        if (draft != null) {
            try {
                preview = Packager.preview(v.project(), draft);
            } catch (ImportProblem e) {
                // escolhas incompletas: sem comparação
            }
        }
        Optional<PackageHistory.Entry> reuse = preview == null ? Optional.empty() : history.sameContent(s.origin(), s.organizationId(), preview.contentIdentity());
        reuse.ifPresent(r -> warnings.add("O mesmo conteúdo já foi empacotado nesta máquina como versão " + r.version() + " (" + r.zip().getFileName()
                + "). Se for o mesmo pacote, reutilize-o."));
        if (cat.state() == RemoteCatalog.State.UNSUPPORTED) return new Suggestion(Optional.empty(), UNSUPPORTED, reuse, Optional.empty(), Comparison.UNAVAILABLE, warnings);
        if (mode == ProjectMode.EXISTING) return existing(s, cat, automationId, draft, v.project(), reuse, warnings);
        return fresh(s, cat, name, reuse, warnings);
    }

    private Suggestion existing(LinkRef s, RemoteCatalog cat, UUID automationId, PackagePlan draft, ImportedProject project,
                                Optional<PackageHistory.Entry> reuse, List<String> warnings) {
        if (automationId == null) return new Suggestion(Optional.empty(), "Escolha o projeto na lista.", reuse, Optional.empty(), Comparison.UNAVAILABLE, warnings);
        Optional<RemoteCatalog.Project> op = cat.project(automationId);
        if (op.isEmpty()) {
            return new Suggestion(Optional.empty(), "Este projeto não está no catálogo lido agora. Consulte a Platform de novo.", reuse, Optional.empty(),
                    Comparison.UNAVAILABLE, warnings);
        }
        RemoteCatalog.Project pr = op.get();
        String when = at(cat.observedAt());
        List<RemoteCatalog.Version> versions = pr.versions();
        if (versions.isEmpty()) {
            return new Suggestion(Optional.empty(), "Nenhuma versão publicada deste projeto está autorizada a esta máquina (consulta " + when + "). Informe a "
                    + "versão declarada no pacote; nada foi reservado.", reuse, Optional.empty(), Comparison.UNAVAILABLE, warnings);
        }
        // comparação pela regra completa: o pacote preparado COM a versão declarada de cada versão visível
        Comparison comparison = Comparison.UNAVAILABLE;
        Optional<RemoteCatalog.Version> identical = Optional.empty();
        if (draft != null) {
            boolean comparable = false;
            for (RemoteCatalog.Version rv : versions) {
                if (!com.nekkora.agent.exec.ExecutionPackage.EXTERNAL_RULE.equals(rv.rule()) || RemoteCatalog.semver(rv.packageVersion()).isEmpty()) continue;
                try {
                    Packager.Preview pv = Packager.preview(project, withVersion(draft, rv.packageVersion()));
                    comparable = true;
                    if (pv.fingerprint().equals(rv.fingerprint())) {
                        identical = Optional.of(rv);
                        break;
                    }
                } catch (ImportProblem e) {
                    break; // escolhas incompletas: não dá para comparar nenhuma
                }
            }
            comparison = identical.isPresent() ? Comparison.IDENTICAL : comparable ? Comparison.DIFFERENT : Comparison.UNAVAILABLE;
        }
        if (identical.isPresent()) {
            RemoteCatalog.Version rv = identical.get();
            return new Suggestion(Optional.of(rv.packageVersion()), "Igual à versão nº " + rv.versionNumber() + " da Platform (versão declarada " + rv.packageVersion()
                    + ", consulta " + when + "): mesmo conteúdo e mesma identificação. Use " + rv.packageVersion() + " para manter a mesma identidade; não é "
                    + "preciso uma versão nova.", reuse, identical, comparison, warnings);
        }
        if (draft != null && RemoteCatalog.semver(draft.version()).isPresent()) {
            versions.stream().filter(rv -> draft.version().equals(rv.packageVersion())).findFirst().ifPresent(rv -> warnings.add("A versão declarada "
                    + draft.version() + " já existe na Platform (nº " + rv.versionNumber() + ") com outro conteúdo ou identificação. Use outra versão."));
        }
        if (pr.versionsTruncated()) warnings.add("A Platform mostrou só as versões mais recentes deste projeto visíveis a esta máquina.");
        Optional<RemoteCatalog.Version> top = RemoteCatalog.highestDeclared(versions);
        String compared = switch (comparison) {
            case DIFFERENT -> " O pacote preparado é diferente de todas as versões visíveis.";
            case UNAVAILABLE -> draft == null ? "" : " Complete a entrada, o Python e as dependências para comparar com as versões da Platform.";
            default -> "";
        };
        if (top.isEmpty()) {
            RemoteCatalog.Version latest = versions.get(0);
            return new Suggestion(Optional.empty(), "As versões visíveis deste projeto não informam versão declarada no formato MAIOR.MENOR.CORREÇÃO (por exemplo, "
                    + "pacote do Studio). O número da Platform (nº " + latest.versionNumber() + ") não é convertido em versão do pacote: informe a versão "
                    + "declarada." + compared, reuse, Optional.empty(), comparison, warnings);
        }
        RemoteCatalog.Version hi = top.get();
        Optional<String> next = RemoteCatalog.nextPatch(hi.packageVersion());
        localAhead(s, automationId, hi.packageVersion()).ifPresent(w -> warnings.add(w));
        String text = next.map(n -> "Sugestão " + n + ", sem reserva: a próxima depois de " + hi.packageVersion() + ", a maior versão declarada entre as "
                + "visíveis a esta máquina (nº " + hi.versionNumber() + ", consulta " + when + "). Pode haver versão mais recente na Platform não autorizada a "
                + "esta máquina; uma publicação futura confere a disponibilidade.").orElse("A maior versão visível (" + hi.packageVersion() + ") não tem próxima "
                + "correção no formato do pacote: informe a versão declarada.");
        return new Suggestion(next, text + compared, reuse, Optional.empty(), comparison, warnings);
    }

    /** Uma versão gerada NESTA máquina acima da maior visível: avisada, nunca usada no lugar do fato remoto. */
    private Optional<String> localAhead(LinkRef s, UUID automationId, String remoteHighest) {
        return history.forProject(s.origin(), s.organizationId(), PackageHistory.automationKey(automationId)).stream().map(PackageHistory.Entry::version)
                .filter(x -> RemoteCatalog.semver(x).isPresent()).max(RemoteCatalog.SEMVER_ORDER)
                .filter(x -> RemoteCatalog.SEMVER_ORDER.compare(x, remoteHighest) > 0)
                .map(x -> "Esta máquina já gerou localmente a versão " + x + " para este projeto, acima da maior visível na Platform. A sugestão segue a Platform; "
                        + "confira se aquele pacote foi publicado.");
    }

    private Suggestion fresh(LinkRef s, RemoteCatalog cat, String name, Optional<PackageHistory.Entry> reuse, List<String> warnings) {
        if (!cat.complete()) {
            return new Suggestion(Optional.empty(), "Sem sugestão: o catálogo da Platform não foi lido por inteiro" + why(cat) + ". Falha de consulta, página "
                    + "faltante ou falta de permissão não indicam que o projeto é novo.", reuse, Optional.empty(), Comparison.NOT_APPLICABLE, warnings);
        }
        if (name == null || name.isBlank()) {
            return new Suggestion(Optional.empty(), "Informe o nome do projeto.", reuse, Optional.empty(), Comparison.NOT_APPLICABLE, warnings);
        }
        String key = name.strip().toLowerCase(Locale.ROOT);
        cat.projects().stream().filter(pr -> pr.automationName().strip().toLowerCase(Locale.ROOT).equals(key)).forEach(pr -> warnings.add("Há um projeto "
                + "com o nome “" + pr.automationName() + "” no catálogo (workspace “" + pr.workspaceName() + "”). Se for o mesmo, escolha “Usar projeto existente”: o "
                + "Agent não escolhe pelo nome."));
        List<PackageHistory.Entry> same = history.forProject(s.origin(), s.organizationId(), PackageHistory.localKey(name));
        if (!same.isEmpty()) {
            Optional<String> next = history.nextVersion(s.origin(), s.organizationId(), PackageHistory.localKey(name));
            return new Suggestion(next, "Esta máquina já gerou um pacote com este nome para esta organização (versão " + same.get(0).version() + "). Nome igual não "
                    + "prova que é o mesmo projeto: confira. " + next.map(n -> "Sugestão local " + n + ", sem reserva.").orElse(""), reuse, Optional.empty(),
                    Comparison.NOT_APPLICABLE, warnings);
        }
        return new Suggestion(Optional.of("1.0.0"), INITIAL + " Isto não confirma que o nome está livre nem que não exista projeto com esse nome na "
                + "Platform: o catálogo só mostra o que está autorizado a esta máquina. Nada foi reservado.", reuse, Optional.empty(), Comparison.NOT_APPLICABLE,
                warnings);
    }

    public static final String INITIAL = "Pacote inicial 1.0.0 — projeto ainda não cadastrado por este assistente.";
    public static final String UNSUPPORTED = "A Platform conectada não oferece o catálogo à máquina (versão anterior da Platform). Sem ele, o assistente não "
            + "lista projetos, não sugere versão e não conclui o pacote; o histórico desta máquina não substitui a Platform. Atualize a Platform.";

    static PackagePlan withVersion(PackagePlan d, String version) {
        return new PackagePlan(d.name(), version, d.entry(), d.python(), d.dependencies(), d.dependenciesConfirmed(), d.includeData(), d.exclude(), d.rebuild());
    }

    static String at(Instant t) {
        return t == null ? "sem hora" : "às " + DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(t);
    }

    /** O motivo, entre parênteses, de o catálogo não estar completo (para a tela). */
    public static String whyText(RemoteCatalog c) {
        return why(c);
    }

    static String why(RemoteCatalog c) {
        if (c.problem() == null) return c.state() == RemoteCatalog.State.PARTIAL ? " (lista longa demais para uma consulta)" : "";
        return switch (c.problem()) {
            case UNREACHABLE, BUSY -> " (sem conexão)";
            case TLS_FAILED -> " (conexão segura recusada)";
            case TIMED_OUT -> " (a Platform não respondeu a tempo)";
            case FORBIDDEN -> " (a Platform negou a consulta a esta máquina)";
            case RATE_LIMITED -> " (limite de consultas" + c.retryAfterSeconds().map(x -> "; tente em " + x + " s").orElse("") + ")";
            case SERVER_ERROR -> " (erro no servidor da Platform)";
            case INVALID_RESPONSE -> " (resposta fora do contrato)";
            default -> "";
        };
    }

    static String selectionBlocked(SelectionCheck sel) {
        return switch (sel.outcome()) {
            case NOT_VISIBLE -> "O projeto escolhido não está mais acessível a esta máquina na Platform (autorização revogada, vencida ou retirada). Nada foi "
                    + "gerado. Consulte a Platform de novo e escolha outro projeto.";
            case UNSUPPORTED -> UNSUPPORTED;
            case VISIBLE -> "A confirmação do projeto não é deste vínculo. Nada foi gerado.";
            case FAILED -> "Não deu para confirmar com a Platform que o projeto escolhido continua acessível a esta máquina"
                    + why(new RemoteCatalog(RemoteCatalog.State.UNAVAILABLE, sel.problem(), sel.retryAfterSeconds(), null, List.of(), null))
                    + ". Nada foi gerado; tente de novo.";
        };
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
