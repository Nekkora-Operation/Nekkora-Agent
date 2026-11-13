package com.nekkora.agent.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.app.ImportFlow.ProjectMode;
import com.nekkora.agent.app.ImportFlow.Stage;
import com.nekkora.agent.pack.ImportProblem;
import com.nekkora.agent.pack.PackageHistory;
import com.nekkora.agent.pack.PackagePlan;
import com.nekkora.agent.pack.ProjectImporter;
import com.nekkora.agent.pack.ZipLimits;
import com.nekkora.agent.platform.PlatformService.LinkCheck;
import com.nekkora.agent.platform.PlatformService.LinkCheckOutcome;
import com.nekkora.agent.platform.PlatformService.LinkRef;
import com.nekkora.agent.platform.PlatformService.SelectionCheck;
import com.nekkora.agent.platform.PlatformService.SelectionOutcome;
import com.nekkora.agent.platform.RemoteCatalog;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-PACOTES-001 T02 e PLATFORM-AGENT-CATALOGO-001 T02: o assistente preso ao vínculo confirmado e ao catálogo da máquina.
 * O vínculo e o catálogo são SIMULADOS aqui (a leitura real tem teste próprio contra o transporte: {@code PlatformLinkCheckTest}
 * e {@code PlatformCatalogTest}); a leitura do ZIP e o empacotamento são os reais.
 */
class ImportFlowTest {

    @TempDir
    Path tmp;

    static final String ORIGIN = "https://platform.test";
    final UUID orgA = UUID.randomUUID();
    final UUID orgB = UUID.randomUUID();
    LinkRef linkA;
    LinkRef linkB;

    /** Vínculo simulado: o atual (local) e as respostas da confirmação, em ordem. */
    final class FakeLink implements ImportFlow.Link {
        final AtomicReference<LinkRef> current = new AtomicReference<>();
        final Deque<LinkCheck> answers = new ArrayDeque<>();
        final AtomicInteger confirms = new AtomicInteger();
        final AtomicInteger selections = new AtomicInteger();
        final Deque<SelectionCheck> selectionAnswers = new ArrayDeque<>();
        Runnable onConfirm = () -> {};

        @Override
        public LinkCheck confirm() {
            confirms.incrementAndGet();
            onConfirm.run();
            return answers.isEmpty() ? ok(current.get()) : answers.poll();
        }

        @Override
        public Optional<LinkRef> current() {
            return Optional.ofNullable(current.get());
        }

        /** Sem resposta programada: visível, com o projeto do catálogo da última confirmação. */
        @Override
        public SelectionCheck selection(UUID automationId) {
            selections.incrementAndGet();
            if (!selectionAnswers.isEmpty()) return selectionAnswers.poll();
            return new SelectionCheck(SelectionOutcome.VISIBLE, current.get(), null, T, null, Optional.empty());
        }
    }

    /** Execução em fila: a tarefa só roda quando o teste manda (para simular respostas atrasadas). */
    static final class Manual implements java.util.concurrent.Executor {
        final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable r) {
            queue.add(r);
        }

        void runAll() {
            while (!queue.isEmpty()) queue.poll().run();
        }
    }

    FakeLink link;
    PackageHistory history;
    ProjectImporter importer;
    List<ImportFlow.View> views;

    @BeforeEach
    void setUp() {
        linkA = new LinkRef(ORIGIN, orgA, UUID.randomUUID(), UUID.randomUUID());
        linkB = new LinkRef(ORIGIN, orgB, UUID.randomUUID(), UUID.randomUUID());
        link = new FakeLink();
        history = new PackageHistory(tmp.resolve("dados").resolve("importacao").resolve("historico.json"), AtomicWriter.DEFAULT);
        importer = new ProjectImporter(tmp.resolve("dados").resolve("importacao"), ZipLimits.DEFAULT);
        views = new ArrayList<>();
    }

    ImportFlow flow(java.util.concurrent.Executor background) {
        return new ImportFlow(link, importer, history, Clock.fixed(Instant.parse("2026-11-12T20:00:00Z"), ZoneOffset.UTC), background, Runnable::run, views::add);
    }

    static final Instant T = Instant.parse("2026-11-12T20:00:00Z");
    static final String EXT = com.nekkora.agent.exec.ExecutionPackage.EXTERNAL_RULE;

    static RemoteCatalog complete(LinkRef r, RemoteCatalog.Project... ps) {
        return new RemoteCatalog(RemoteCatalog.State.COMPLETE, null, Optional.empty(), new RemoteCatalog.Organization(r.organizationId(), "Org " + r.organizationId()
                .toString().substring(0, 4)), List.of(ps), T);
    }

    static LinkCheck ok(LinkRef r, RemoteCatalog.Project... ps) {
        return new LinkCheck(LinkCheckOutcome.CONFIRMED, r, T, Optional.empty(), complete(r, ps));
    }

    static LinkCheck okWith(LinkRef r, RemoteCatalog c) {
        return new LinkCheck(LinkCheckOutcome.CONFIRMED, r, T, Optional.empty(), c);
    }

    static LinkCheck fail(LinkCheckOutcome why, LinkRef r) {
        return new LinkCheck(why, r, null, Optional.empty(), RemoteCatalog.unavailable(why, Optional.empty()));
    }

    static RemoteCatalog.Version version(int n, String declared, String fingerprint) {
        return new RemoteCatalog.Version(UUID.randomUUID(), n, T.minusSeconds(3600L * n), "EXTERNAL_PACKAGE", EXT, fingerprint, "p", declared);
    }

    static RemoteCatalog.Project project(UUID automation, String name, RemoteCatalog.Version... vs) {
        return new RemoteCatalog.Project(UUID.randomUUID(), "Financeiro", automation, name, List.of(vs), false);
    }

    static String fp(char c) {
        return "sha256:" + String.valueOf(c).repeat(64);
    }

    Path zip(String name, String... pathAndText) throws Exception {
        Path z = tmp.resolve(name);
        try (OutputStream o = Files.newOutputStream(z); ZipOutputStream zo = new ZipOutputStream(o)) {
            for (int i = 0; i < pathAndText.length; i += 2) {
                zo.putNextEntry(new ZipEntry(pathAndText[i]));
                zo.write(pathAndText[i + 1].getBytes(StandardCharsets.UTF_8));
                zo.closeEntry();
            }
        }
        return z;
    }

    static PackagePlan plan(String name, String version) {
        return new PackagePlan(name, version, "main.py", ">=3.10,<3.14", List.of(), true, Set.of(), Set.of(), false);
    }

    long operations() throws Exception {
        Path root = tmp.resolve("dados").resolve("importacao");
        if (!Files.isDirectory(root)) return 0;
        try (var s = Files.list(root)) {
            return s.filter(p -> p.getFileName().toString().startsWith("op-")).count();
        }
    }

    // =====================================================================================================

    @Test
    void withoutLinkImportIsBlocked() throws Exception {
        link.answers.add(fail(LinkCheckOutcome.NOT_PAIRED, null));
        ImportFlow f = flow(Runnable::run);
        f.start();
        assertEquals(Stage.BLOCKED, f.view().stage());
        assertEquals(LinkCheckOutcome.NOT_PAIRED, f.view().check().outcome());
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        assertEquals(Stage.BLOCKED, f.view().stage(), "sem vínculo confirmado, nada é lido");
        assertEquals(0, operations());
    }

    @Test
    void eachLinkFailureBlocksAndRetryWorks() throws Exception {
        link.current.set(linkA);
        for (LinkCheckOutcome why : List.of(LinkCheckOutcome.UNREACHABLE, LinkCheckOutcome.BUSY, LinkCheckOutcome.FORBIDDEN, LinkCheckOutcome.RATE_LIMITED,
                LinkCheckOutcome.SERVER_ERROR, LinkCheckOutcome.CREDENTIAL_REFUSED, LinkCheckOutcome.UNLINK_PENDING)) {
            link.answers.add(fail(why, linkA));
            ImportFlow f = flow(Runnable::run);
            f.start();
            assertEquals(Stage.BLOCKED, f.view().stage(), why.name());
            assertEquals(why, f.view().check().outcome());
            f.start(); // tentar de novo
            assertEquals(Stage.READY, f.view().stage(), "tentar de novo confirma quando a Platform responde");
        }
    }

    @Test
    void confirmedFlowGeneratesPackageAndAssociatesLocally() throws Exception {
        link.current.set(linkA);
        ImportFlow f = flow(Runnable::run);
        f.start();
        assertEquals(Stage.READY, f.view().stage());
        f.chooseZip(zip("p.zip", "main.py", "print('ok')\n", "util/x.py", "X = 1\n"));
        assertEquals(Stage.CONFIGURE, f.view().stage());
        ImportFlow.Suggestion s = f.suggest(ProjectMode.NEW, "relatorio", null, null);
        assertEquals(Optional.of("1.0.0"), s.version());
        assertTrue(s.text().startsWith(ImportFlow.INITIAL) && s.text().contains("Nada foi reservado"), s.text());
        Path out = Files.createDirectories(tmp.resolve("saida"));
        f.build(plan("relatorio", "1.0.0"), ProjectMode.NEW, null, out, false);
        assertEquals(Stage.DONE, f.view().stage(), String.valueOf(f.view().problem()));
        assertTrue(Files.isRegularFile(f.view().built().zip()));
        assertEquals(2, link.confirms.get(), "confirmado no início e de novo antes de gravar");
        PackageHistory.Entry e = history.forProject(ORIGIN, orgA, PackageHistory.localKey("relatorio")).get(0);
        assertEquals(orgA, e.organizationId(), "associação local à organização confirmada");
        assertEquals(linkA.agentId(), e.agentId());
    }

    @Test
    void offlineAtCompletionBlocksAndWritesNothing() throws Exception {
        link.current.set(linkA);
        ImportFlow f = flow(Runnable::run);
        f.start();
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        link.answers.add(fail(LinkCheckOutcome.UNREACHABLE, linkA));
        Path out = Files.createDirectories(tmp.resolve("saida"));
        f.build(plan("p", "1.0.0"), ProjectMode.NEW, null, out, false);
        assertEquals(Stage.CONFIGURE, f.view().stage(), "a pessoa pode tentar de novo");
        assertEquals(LinkCheckOutcome.UNREACHABLE, f.view().check().outcome());
        assertNull(f.view().built());
        try (var s = Files.list(out)) {
            assertEquals(0, s.count(), "nenhum artefato final");
        }
        f.build(plan("p", "1.0.0"), ProjectMode.NEW, null, out, false);
        assertEquals(Stage.DONE, f.view().stage());
    }

    @Test
    void linkChangeDuringConsultationIsNotAccepted() throws Exception {
        link.current.set(linkA);
        link.onConfirm = () -> link.current.set(linkB); // pareada de novo enquanto a consulta estava em andamento
        link.answers.add(ok(linkA));
        ImportFlow f = flow(Runnable::run);
        f.start();
        assertEquals(Stage.BLOCKED, f.view().stage(), "a confirmação era do vínculo anterior");
        assertTrue(f.session().isEmpty());
    }

    @Test
    void linkChangeDuringReadingDiscardsEverything() throws Exception {
        link.current.set(linkA);
        Manual bg = new Manual();
        ImportFlow f = flow(bg);
        f.start();
        bg.runAll();
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        link.current.set(linkB); // desvinculada e pareada em outra organização durante a leitura
        bg.runAll();
        assertEquals(Stage.INVALIDATED, f.view().stage());
        assertNull(f.view().project());
        assertEquals(0, operations(), "a pasta da operação foi apagada");
        f.platformChanged();
        assertEquals(Stage.INVALIDATED, f.view().stage());
    }

    @Test
    void linkChangeDuringCompletionLeavesNoFinalPackage() throws Exception {
        link.current.set(linkA);
        ImportFlow f = flow(Runnable::run);
        f.start();
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        Path out = Files.createDirectories(tmp.resolve("saida"));
        // a reconfirmação passa; o vínculo muda logo depois, durante a gravação (antes da troca de nome)
        link.answers.add(ok(linkA));
        com.nekkora.agent.pack.PackagerHooks.beforeMove(() -> link.current.set(linkB));
        try {
            f.build(plan("p", "1.0.0"), ProjectMode.NEW, null, out, false);
        } finally {
            com.nekkora.agent.pack.PackagerHooks.beforeMove(() -> {});
        }
        assertEquals(Stage.INVALIDATED, f.view().stage());
        try (var s = Files.list(out)) {
            assertEquals(0, s.count(), "nem pacote nem temporário");
        }
        assertTrue(history.forProject(ORIGIN, orgA, PackageHistory.localKey("p")).isEmpty());
        assertTrue(history.forProject(ORIGIN, orgB, PackageHistory.localKey("p")).isEmpty(), "nada associado à organização nova");
    }

    @Test
    void lateAnswerOfAnOldGenerationNeverEnables() throws Exception {
        link.current.set(linkA);
        Manual bg = new Manual();
        ImportFlow f = flow(bg);
        f.start(); // 1ª consulta: fica pendente
        f.start(); // a pessoa tentou de novo
        Runnable first = bg.queue.poll();
        Runnable second = bg.queue.poll();
        link.answers.clear();
        link.answers.add(fail(LinkCheckOutcome.UNREACHABLE, linkA));
        second.run(); // a 2ª responde: offline
        assertEquals(Stage.BLOCKED, f.view().stage());
        link.answers.clear();
        link.answers.add(ok(linkA));
        first.run(); // a 1ª responde atrasada: confirmada — mas é de outra geração
        assertEquals(Stage.BLOCKED, f.view().stage(), "resposta antiga não reabilita");
        assertTrue(f.session().isEmpty());
    }

    @Test
    void lateReadOfOldGenerationIsDiscardedAndCleaned() throws Exception {
        link.current.set(linkA);
        Manual bg = new Manual();
        ImportFlow f = flow(bg);
        f.start();
        bg.runAll();
        f.chooseZip(zip("a.zip", "main.py", "print('a')"));
        Runnable oldRead = bg.queue.poll();
        f.cancel();
        oldRead.run();
        assertEquals(Stage.CLOSED, f.view().stage());
        assertEquals(0, operations(), "a leitura atrasada foi descartada e a pasta dela apagada");
    }

    @Test
    void cancelCreatesNothingRemote() throws Exception {
        link.current.set(linkA);
        ImportFlow f = flow(Runnable::run);
        f.start();
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        f.cancel();
        assertEquals(Stage.CLOSED, f.view().stage());
        assertEquals(1, link.confirms.get(), "só a confirmação inicial; nenhuma outra chamada existe neste fluxo");
        assertEquals(0, operations());
    }

    @Test
    void projectsAreListedWithNamesAndSelectedById() throws Exception {
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        // dois projetos com o MESMO nome em workspaces diferentes: distinguidos pelo identificador
        RemoteCatalog.Project px = new RemoteCatalog.Project(UUID.randomUUID(), "Vendas", x, "relatorio", List.of(version(1, "1.0.0", fp('1'))), false);
        RemoteCatalog.Project py = new RemoteCatalog.Project(UUID.randomUUID(), "Compras", y, "relatorio", List.of(version(3, "2.4.0", fp('2'))), false);
        link.current.set(linkA);
        link.answers.add(ok(linkA, px, py));
        ImportFlow f = flow(Runnable::run);
        f.start();
        f.chooseZip(zip("relatorio.zip", "main.py", "print(1)"));
        assertEquals(2, f.catalog().size());
        assertEquals("Compras", f.catalog().get(0).workspaceName(), "ordem legível: workspace, nome");
        assertEquals(Optional.of("1.0.1"), f.suggest(ProjectMode.EXISTING, null, x, null).version());
        assertEquals(Optional.of("2.4.1"), f.suggest(ProjectMode.EXISTING, null, y, null).version());
        // o nome do ZIP e o nome igual não escolhem nada
        ImportFlow.Suggestion fresh = f.suggest(ProjectMode.NEW, "relatorio", null, null);
        assertTrue(fresh.text().startsWith(ImportFlow.INITIAL), fresh.text());
        assertEquals(Optional.of("1.0.0"), fresh.version());
        assertEquals(2, fresh.warnings().stream().filter(w -> w.contains("Usar projeto existente")).count(), "homônimos avisados, nunca escolhidos");
        assertTrue(fresh.text().contains("não confirma que o nome está livre"), fresh.text());
    }

    @Test
    void semverIsNumericAndPlatformNumberIsNotConverted() throws Exception {
        UUID a = UUID.randomUUID();
        UUID studio = UUID.randomUUID();
        link.current.set(linkA);
        RemoteCatalog.Version s1 = new RemoteCatalog.Version(UUID.randomUUID(), 7, T, "STUDIO_PACKAGE", "nekkora-agent/package-fingerprint/1", fp('9'), null, null);
        link.answers.add(ok(linkA, project(a, "a", version(5, "1.9.0", fp('3')), version(6, "1.10.0", fp('4'))), project(studio, "s", s1)));
        ImportFlow f = flow(Runnable::run);
        f.start();
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        ImportFlow.Suggestion sa = f.suggest(ProjectMode.EXISTING, null, a, null);
        assertEquals(Optional.of("1.10.1"), sa.version(), "1.10.0 > 1.9.0");
        assertTrue(sa.text().contains("sem reserva") && sa.text().contains("nº 6"), sa.text());
        ImportFlow.Suggestion ss = f.suggest(ProjectMode.EXISTING, null, studio, null);
        assertTrue(ss.version().isEmpty(), "sem versão declarada: nada é inventado");
        assertTrue(ss.text().contains("não é convertido"), ss.text());
    }

    @Test
    void remoteFactsBeatLocalHistory() throws Exception {
        UUID a = UUID.randomUUID();
        history.record(new PackageHistory.Entry(ORIGIN, orgA, linkA.agentId(), PackageHistory.automationKey(a), "3.0.0", "sha256:c1", "sha256:f1",
                tmp.resolve("a-3.0.0.zip"), Instant.parse("2026-11-01T00:00:00Z")));
        link.current.set(linkA);
        link.answers.add(ok(linkA, project(a, "a", version(2, "1.2.0", fp('5')))));
        ImportFlow f = flow(Runnable::run);
        f.start();
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        ImportFlow.Suggestion s1 = f.suggest(ProjectMode.EXISTING, null, a, null);
        assertEquals(Optional.of("1.2.1"), s1.version(), "o histórico local (3.0.0) não prevalece sobre a Platform");
        assertTrue(s1.warnings().stream().anyMatch(w -> w.contains("3.0.0")), "mas a divergência é avisada");
    }

    @Test
    void identicalPackageIsRecognizedEvenRecompressed() throws Exception {
        link.current.set(linkA);
        ImportFlow f0 = flow(Runnable::run);
        f0.start();
        f0.chooseZip(zip("p.zip", "main.py", "print(1)\n", "lib/util.py", "X = 1\n"));
        String known = com.nekkora.agent.pack.Packager.preview(f0.view().project(), plan("p", "2.0.0")).fingerprint();
        f0.cancel();
        UUID a = UUID.randomUUID();
        RemoteCatalog.Version v2 = version(4, "2.0.0", known);
        link.answers.add(ok(linkA, project(a, "p", v2, version(3, "1.0.0", fp('6')))));
        ImportFlow f = flow(Runnable::run);
        f.start();
        // outro ZIP, sem compressão, ordem diferente: o mesmo conteúdo
        f.chooseZip(storedZip("q.zip", "lib/util.py", "X = 1\n", "main.py", "print(1)\n"));
        ImportFlow.Suggestion same = f.suggest(ProjectMode.EXISTING, null, a, plan("p", "9.9.9"));
        assertEquals(ImportFlow.Comparison.IDENTICAL, same.comparison());
        assertEquals(v2, same.identical().orElseThrow());
        assertEquals(Optional.of("2.0.0"), same.version(), "reutilizar a identidade da versão existente");
        // conteúdo alterado: diferente, com sugestão (sem reserva)
        f.chooseZip(zip("r.zip", "main.py", "print(2)\n", "lib/util.py", "X = 1\n"));
        ImportFlow.Suggestion diff = f.suggest(ProjectMode.EXISTING, null, a, plan("p", "2.0.0"));
        assertEquals(ImportFlow.Comparison.DIFFERENT, diff.comparison());
        assertTrue(diff.identical().isEmpty());
        assertEquals(Optional.of("2.0.1"), diff.version());
        assertTrue(diff.warnings().stream().anyMatch(w -> w.contains("2.0.0 já existe")), "versão já usada com outro conteúdo é avisada");
        // escolhas incompletas: comparação indisponível, nunca "igual"
        ImportFlow.Suggestion none = f.suggest(ProjectMode.EXISTING, null, a, new PackagePlan("p", "2.0.0", null, ">=3.10,<3.14", List.of(), true, Set.of(),
                Set.of(), false));
        assertEquals(ImportFlow.Comparison.UNAVAILABLE, none.comparison());
    }

    @Test
    void partialOrFailedCatalogNeverMakesANewProject() throws Exception {
        link.current.set(linkA);
        RemoteCatalog partial = new RemoteCatalog(RemoteCatalog.State.PARTIAL, LinkCheckOutcome.SERVER_ERROR, Optional.empty(),
                new RemoteCatalog.Organization(orgA, "Org A"), List.of(), T);
        link.answers.add(okWith(linkA, partial));
        ImportFlow f = flow(Runnable::run);
        f.start();
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        ImportFlow.Suggestion s1 = f.suggest(ProjectMode.NEW, "novo", null, null);
        assertTrue(s1.version().isEmpty(), "lista parcial não prova projeto novo");
        assertTrue(s1.text().contains("não indicam que o projeto é novo"), s1.text());
        // na conclusão, a consulta de novo também falha: nada gravado
        link.answers.add(okWith(linkA, RemoteCatalog.unavailable(LinkCheckOutcome.FORBIDDEN, Optional.empty())));
        Path out = Files.createDirectories(tmp.resolve("saida"));
        f.build(plan("novo", "1.0.0"), ProjectMode.NEW, null, out, false);
        assertEquals(Stage.CONFIGURE, f.view().stage());
        assertTrue(f.view().note().contains("não conclui um projeto como novo"), f.view().note());
        try (var l = Files.list(out)) {
            assertEquals(0, l.count());
        }
    }

    @Test
    void oldPlatformWithoutCatalogIsExplainedAndBlocksCompletion() throws Exception {
        link.current.set(linkA);
        link.answers.add(okWith(linkA, RemoteCatalog.unsupported()));
        link.answers.add(okWith(linkA, RemoteCatalog.unsupported()));
        history.record(new PackageHistory.Entry(ORIGIN, orgA, linkA.agentId(), PackageHistory.localKey("velho"), "4.0.0", "sha256:c9", "sha256:f9",
                tmp.resolve("v.zip"), Instant.parse("2026-11-01T00:00:00Z")));
        ImportFlow f = flow(Runnable::run);
        f.start();
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        ImportFlow.Suggestion s1 = f.suggest(ProjectMode.NEW, "velho", null, null);
        assertTrue(s1.version().isEmpty(), "o histórico local não substitui a Platform em silêncio");
        assertEquals(ImportFlow.UNSUPPORTED, s1.text());
        Path out = Files.createDirectories(tmp.resolve("saida"));
        f.build(plan("velho", "4.0.1"), ProjectMode.NEW, null, out, false);
        assertEquals(Stage.CONFIGURE, f.view().stage());
        assertEquals(ImportFlow.UNSUPPORTED, f.view().note());
    }

    @Test
    void existingSelectionIsRevalidatedBeforeWriting() throws Exception {
        UUID a = UUID.randomUUID();
        link.current.set(linkA);
        link.answers.add(ok(linkA, project(a, "a", version(1, "1.0.0", fp('7')))));
        ImportFlow f = flow(Runnable::run);
        f.start();
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        Path out = Files.createDirectories(tmp.resolve("saida"));
        link.selectionAnswers.add(new SelectionCheck(SelectionOutcome.NOT_VISIBLE, linkA, null, null, null, Optional.empty()));
        f.build(plan("a", "1.0.1"), ProjectMode.EXISTING, a, out, false);
        assertEquals(Stage.CONFIGURE, f.view().stage());
        assertTrue(f.view().note().contains("não está mais acessível"), f.view().note());
        try (var l = Files.list(out)) {
            assertEquals(0, l.count(), "seleção revogada: nada gravado");
        }
        link.selectionAnswers.add(new SelectionCheck(SelectionOutcome.FAILED, linkA, null, null, LinkCheckOutcome.RATE_LIMITED, Optional.of(20L)));
        f.build(plan("a", "1.0.1"), ProjectMode.EXISTING, a, out, false);
        assertTrue(f.view().note().contains("Não deu para confirmar") && f.view().note().contains("20 s"), f.view().note());
        // visível agora: gera e associa ao projeto pelo identificador
        f.build(plan("a", "1.0.1"), ProjectMode.EXISTING, a, out, false);
        assertEquals(Stage.DONE, f.view().stage());
        assertEquals(3, link.selections.get(), "uma revalidação por tentativa de conclusão");
        assertEquals("1.0.1", history.forProject(ORIGIN, orgA, PackageHistory.automationKey(a)).get(0).version());
    }

    @Test
    void twoPreparationsGetTheSameSuggestionWithoutReservation() throws Exception {
        UUID a = UUID.randomUUID();
        link.current.set(linkA);
        link.answers.add(ok(linkA, project(a, "a", version(1, "1.0.0", fp('8')))));
        link.answers.add(ok(linkA, project(a, "a", version(1, "1.0.0", fp('8')))));
        ImportFlow one = flow(Runnable::run);
        one.start();
        one.chooseZip(zip("p.zip", "main.py", "print('um')"));
        ImportFlow two = flow(Runnable::run);
        two.start();
        two.chooseZip(zip("q.zip", "main.py", "print('dois')"));
        ImportFlow.Suggestion s1 = one.suggest(ProjectMode.EXISTING, null, a, null);
        ImportFlow.Suggestion s2 = two.suggest(ProjectMode.EXISTING, null, a, null);
        assertEquals(s1.version(), s2.version(), "duas preparações podem chegar à mesma sugestão");
        assertTrue(s1.text().contains("sem reserva") && s1.text().contains("publicação futura confere"), s1.text());
    }

    @Test
    void refreshKeepsTheZipAndNeverRunsTwiceAtOnce() throws Exception {
        UUID a = UUID.randomUUID();
        link.current.set(linkA);
        Manual bg = new Manual();
        ImportFlow f = flow(bg);
        f.start();
        bg.runAll();
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        bg.runAll();
        assertEquals(Stage.CONFIGURE, f.view().stage());
        link.answers.add(ok(linkA, project(a, "a", version(1, "1.0.0", fp('a')))));
        f.refreshCatalog();
        f.refreshCatalog(); // pedido repetido enquanto a primeira consulta não voltou
        assertEquals(1, bg.queue.size(), "uma consulta por vez");
        bg.runAll();
        assertEquals(Stage.CONFIGURE, f.view().stage(), "o ZIP lido continua");
        assertEquals(1, f.catalog().size());
        // resposta atrasada de uma consulta, chegando depois de o vínculo mudar: descartada, tudo invalidado
        link.answers.add(ok(linkA, project(a, "a")));
        f.refreshCatalog();
        link.current.set(linkB);
        bg.runAll();
        assertEquals(Stage.INVALIDATED, f.view().stage());
        assertTrue(f.catalog().isEmpty(), "nada do vínculo anterior fica escolhível");
    }

    @Test
    void homonymInSameOrganizationIsFlaggedAndSameContentOffersReuse() throws Exception {
        link.current.set(linkA);
        ImportFlow f = flow(Runnable::run);
        f.start();
        f.chooseZip(zip("p.zip", "main.py", "print('igual')\n"));
        Path out = Files.createDirectories(tmp.resolve("saida"));
        f.build(plan("relatorio", "1.0.0"), ProjectMode.NEW, null, out, false);
        assertEquals(Stage.DONE, f.view().stage());
        // outro ZIP (recompactado, outra data) com o mesmo conteúdo
        f.chooseZip(zip("p2.zip", "main.py", "print('igual')\r\n"));
        assertEquals(Stage.CONFIGURE, f.view().stage());
        ImportFlow.Suggestion same = f.suggest(ProjectMode.NEW, "relatorio", null, plan("relatorio", "1.0.1"));
        assertTrue(same.reuse().isPresent(), "mesmo conteúdo: reutilizar");
        f.chooseZip(zip("p3.zip", "main.py", "print('diferente')\n"));
        ImportFlow.Suggestion diff = f.suggest(ProjectMode.NEW, "Relatorio", null, plan("Relatorio", "1.0.1"));
        assertTrue(diff.reuse().isEmpty());
        assertEquals(Optional.of("1.0.1"), diff.version());
        assertTrue(diff.text().contains("Nome igual não prova"), diff.text());
    }

    Path storedZip(String name, String... pathAndText) throws Exception {
        Path z = tmp.resolve(name);
        try (OutputStream o = Files.newOutputStream(z); ZipOutputStream zo = new ZipOutputStream(o)) {
            zo.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            for (int i = 0; i < pathAndText.length; i += 2) {
                zo.putNextEntry(new ZipEntry(pathAndText[i]));
                zo.write(pathAndText[i + 1].getBytes(StandardCharsets.UTF_8));
                zo.closeEntry();
            }
        }
        return z;
    }

    @Test
    void readProblemIsShownAndAssistantStaysUsable() throws Exception {
        link.current.set(linkA);
        ImportFlow f = flow(Runnable::run);
        f.start();
        Path fake = Files.writeString(tmp.resolve("falso.zip"), "não é zip");
        f.chooseZip(fake);
        assertEquals(Stage.READY, f.view().stage());
        assertEquals(ImportProblem.Code.NOT_ZIP, f.view().problem().code());
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        assertEquals(Stage.CONFIGURE, f.view().stage());
    }
}
