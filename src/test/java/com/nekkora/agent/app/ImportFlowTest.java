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
import com.nekkora.agent.platform.PlatformService.AuthorizedVersion;
import com.nekkora.agent.platform.PlatformService.Catalog;
import com.nekkora.agent.platform.PlatformService.LinkCheck;
import com.nekkora.agent.platform.PlatformService.LinkCheckOutcome;
import com.nekkora.agent.platform.PlatformService.LinkRef;
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
 * AGENT-PACOTES-001 T02: o assistente preso ao vínculo confirmado. O vínculo é SIMULADO aqui (a confirmação real tem teste
 * próprio contra o transporte: {@code PlatformLinkCheckTest}); a leitura do ZIP e o empacotamento são os reais.
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

    static LinkCheck ok(LinkRef r, AuthorizedVersion... items) {
        return new LinkCheck(LinkCheckOutcome.CONFIRMED, r, Instant.parse("2026-11-12T20:00:00Z"), Optional.empty(), new Catalog(true, null, List.of(items)));
    }

    static LinkCheck fail(LinkCheckOutcome why, LinkRef r) {
        return new LinkCheck(why, r, null, Optional.empty(), new Catalog(false, why, List.of()));
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
        assertTrue(s.text().contains("Sugestão local") && s.text().contains("nada foi reservado"), s.text());
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
    void projectsAreSelectedByIdAndNeverMixed() throws Exception {
        UUID automationX = UUID.randomUUID();
        UUID automationY = UUID.randomUUID();
        UUID ws = UUID.randomUUID();
        AuthorizedVersion vx = new AuthorizedVersion(UUID.randomUUID(), ws, automationX, UUID.randomUUID(), "sha256:" + "1".repeat(64),
                "nekkora-agent/external-python-fingerprint/1", Instant.parse("2026-12-01T00:00:00Z"));
        AuthorizedVersion vy = new AuthorizedVersion(UUID.randomUUID(), ws, automationY, UUID.randomUUID(), "sha256:" + "2".repeat(64),
                "nekkora-agent/external-python-fingerprint/1", Instant.parse("2026-12-01T00:00:00Z"));
        // histórico: a automação X já teve 1.4.2 nesta máquina; a organização B tem um homônimo local
        history.record(new PackageHistory.Entry(ORIGIN, orgA, linkA.agentId(), PackageHistory.automationKey(automationX), "1.4.2", "sha256:c1", "sha256:f1",
                tmp.resolve("x-1.4.2.zip"), Instant.parse("2026-11-01T00:00:00Z")));
        history.record(new PackageHistory.Entry(ORIGIN, orgB, linkB.agentId(), PackageHistory.localKey("relatorio"), "7.0.0", "sha256:c2", "sha256:f2",
                tmp.resolve("r-7.0.0.zip"), Instant.parse("2026-11-01T00:00:00Z")));
        link.current.set(linkA);
        link.answers.add(ok(linkA, vx, vy));
        ImportFlow f = flow(Runnable::run);
        f.start();
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        assertEquals(2, f.catalog().size());
        assertEquals(Optional.of("1.4.3"), f.suggest(ProjectMode.EXISTING, null, automationX, null).version());
        ImportFlow.Suggestion y = f.suggest(ProjectMode.EXISTING, null, automationY, null);
        assertTrue(y.version().isEmpty(), "sem histórico de Y nesta máquina e sem rota de versões: nenhuma sugestão");
        assertTrue(y.text().contains("não consegue consultar as versões"));
        assertEquals(Optional.of("1.0.0"), f.suggest(ProjectMode.NEW, "relatorio", null, null).version(),
                "o homônimo de OUTRA organização não entra");
    }

    @Test
    void queryFailureNeverBecomesOnePointZero() throws Exception {
        link.current.set(linkA);
        link.answers.add(new LinkCheck(LinkCheckOutcome.CONFIRMED, linkA, Instant.now(), Optional.empty(), new Catalog(false, LinkCheckOutcome.SERVER_ERROR, List.of())));
        ImportFlow f = flow(Runnable::run);
        f.start();
        f.chooseZip(zip("p.zip", "main.py", "print(1)"));
        ImportFlow.Suggestion s = f.suggest(ProjectMode.NEW, "novo", null, null);
        assertTrue(s.version().isEmpty(), "catálogo indisponível não prova projeto novo");
        assertTrue(f.catalog().isEmpty());
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
        assertTrue(same.reuse().isPresent(), "mesmo conteúdo: reutilizar, sem versão nova automática");
        assertTrue(same.version().isEmpty());
        f.chooseZip(zip("p3.zip", "main.py", "print('diferente')\n"));
        ImportFlow.Suggestion diff = f.suggest(ProjectMode.NEW, "Relatorio", null, plan("Relatorio", "1.0.1"));
        assertTrue(diff.reuse().isEmpty());
        assertEquals(Optional.of("1.0.1"), diff.version());
        assertTrue(diff.text().contains("Nome igual não prova"), diff.text());
    }

    @Test
    void sameAsAuthorizedVersionIsShown() throws Exception {
        link.current.set(linkA);
        ImportFlow f0 = flow(Runnable::run);
        f0.start();
        f0.chooseZip(zip("p.zip", "main.py", "print(1)\n"));
        String fp = com.nekkora.agent.pack.Packager.preview(f0.view().project(), plan("p", "2.0.0")).fingerprint();
        UUID automation = UUID.randomUUID();
        AuthorizedVersion v = new AuthorizedVersion(UUID.randomUUID(), UUID.randomUUID(), automation, UUID.randomUUID(), fp,
                "nekkora-agent/external-python-fingerprint/1", Instant.parse("2026-12-01T00:00:00Z"));
        f0.cancel();
        link.answers.add(ok(linkA, v));
        ImportFlow f = flow(Runnable::run);
        f.start();
        f.chooseZip(zip("q.zip", "main.py", "print(1)\n"));
        ImportFlow.Suggestion s = f.suggest(ProjectMode.EXISTING, null, automation, plan("p", "2.0.0"));
        assertEquals(v, s.authorized().orElseThrow(), "idêntico à versão autorizada (mesma impressão)");
        assertNotNull(s.text());
        assertFalse(f.suggest(ProjectMode.EXISTING, null, UUID.randomUUID(), plan("p", "2.0.0")).authorized().isPresent(),
                "outra automação não herda a comparação");
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
