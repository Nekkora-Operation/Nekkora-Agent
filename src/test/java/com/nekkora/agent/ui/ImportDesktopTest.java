package com.nekkora.agent.ui;

import static com.nekkora.agent.ui.DesktopHarness.capture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.app.ImportFlow;
import com.nekkora.agent.app.ImportFlow.ProjectMode;
import com.nekkora.agent.app.ImportFlow.Stage;
import com.nekkora.agent.os.TrayController;
import com.nekkora.agent.pack.ImportedProject;
import com.nekkora.agent.pack.PackagePlan;
import com.nekkora.agent.pack.Packager;
import com.nekkora.agent.pack.ProjectImporter;
import com.nekkora.agent.pack.SafeZip;
import com.nekkora.agent.pack.ZipLimits;
import com.nekkora.agent.platform.PlatformService.Catalog;
import com.nekkora.agent.platform.PlatformService.LinkCheck;
import com.nekkora.agent.platform.PlatformService.LinkCheckOutcome;
import com.nekkora.agent.platform.PlatformService.LinkRef;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-PACOTES-001 T02 na janela real (sessão interativa do Windows): o assistente abre pelo botão e pelo atalho (Alt+I),
 * bloqueia sem vínculo com a orientação certa, não escolhe a entrada sozinho, só habilita "Gerar" com as escolhas feitas,
 * percorre os campos pelo Tab e se ajusta a janelas estreitas. A confirmação do vínculo com a Platform é simulada no teste
 * do componente (a real tem teste próprio contra o transporte).
 * <p>
 * Teclado: os eventos de tecla são entregues DENTRO da janela de teste (atalho pela tabela de ações do Swing, Tab pela política
 * de foco). Nada é digitado pelo sistema operacional: um {@code Robot} mandaria as teclas para a janela que estiver em primeiro
 * plano na sessão da pessoa.
 */
@Tag("desktop")
class ImportDesktopTest {
    @TempDir
    Path tmp;

    private DesktopHarness h;
    private ImportDialog lone;

    @AfterEach
    void tearDown() throws Exception {
        if (lone != null) DesktopHarness.onEdt(() -> {
            lone.dispose();
            return null;
        });
        if (h != null) h.close();
    }

    @Test
    void withoutPairingTheAssistantOpensByKeyboardAndIsBlocked() throws Exception {
        h = DesktopHarness.start(tmp.resolve("dados"), Map.of("COMPUTERNAME", "LAB-AGENT-01"), new String[0], TrayController.SYSTEM);
        MainWindow w = h.window();
        assertEquals(MainWindow.IMPORT_LABEL, h.edt(() -> w.importButton().getText()));
        assertEquals(KeyEvent.VK_I, h.edt(() -> w.importButton().getMnemonic()), "Alt+I");
        h.later(() -> altKey(w.importButton(), KeyEvent.VK_I));
        h.waitFor(() -> h.app.activeDialog() instanceof ImportDialog d && d.isShowing(), "Alt+I abre o assistente");
        ImportDialog d = (ImportDialog) h.edt(h.app::activeDialog);
        h.waitFor(() -> h.app.importFlow() != null && h.app.importFlow().view().stage() == Stage.BLOCKED, "vínculo consultado");
        String all = String.join("\n", h.edt(() -> DesktopHarness.texts(d.getContentPane())));
        assertTrue(all.contains(ImportText.NOT_PAIRED), all);
        assertTrue(all.contains(ImportText.HELP), "ajuda do ZIP visível");
        assertFalse(h.edt(d.importButton::isEnabled), "sem vínculo, nada é importado");
        assertFalse(h.edt(() -> d.generateButton().isEnabled()));
        capture(d, "PAC-T02-sem-vinculo");
        h.later(() -> key(d.getRootPane(), KeyEvent.VK_ESCAPE, 0));
        h.waitFor(() -> h.app.activeDialog() == null, "Esc fecha");
        assertNull(h.edt(h.app::importFlow));
        Path imp = tmp.resolve("dados").resolve("importacao");
        if (Files.isDirectory(imp)) {
            try (var s = Files.list(imp)) {
                assertEquals(0, s.filter(p -> p.getFileName().toString().startsWith("op-")).count(), "nenhuma operação deixada");
            }
        }
    }

    /** Ações simuladas: registram o que a tela pede; a sugestão e o catálogo vêm de um fluxo real com vínculo simulado. */
    final class Recorder implements ImportDialog.Actions {
        final List<String> calls = new ArrayList<>();
        ImportFlow flow;

        @Override
        public void retry() {
            calls.add("retry");
        }

        @Override
        public void chooseZip() {
            calls.add("chooseZip");
        }

        @Override
        public void generate(PackagePlan plan, ProjectMode mode, UUID automationId) {
            calls.add("generate " + plan.name() + " " + plan.version() + " " + plan.entry() + " " + mode);
        }

        @Override
        public void openFolder(Path zip) {
            calls.add("openFolder");
        }

        @Override
        public void prepare(Path zip) {
            calls.add("prepare " + zip.getFileName());
        }

        @Override
        public ImportFlow.Suggestion suggest(ProjectMode mode, String name, UUID automationId, PackagePlan draft) {
            return flow.suggest(mode, name, automationId, draft);
        }

        @Override
        public List<ImportFlow.CatalogProject> catalog() {
            return flow.catalog();
        }
    }

    private ImportFlow.View configured(Recorder rec) throws Exception {
        Path zip = tmp.resolve("projeto.zip");
        try (OutputStream o = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(o)) {
            for (String[] f : new String[][] {{"relatorio/main.py", "print('ok')\n"}, {"relatorio/util/calc.py", "X = 1\n"},
                    {"relatorio/dados/exemplo.csv", "a,b\n"}, {"relatorio/.env", "SENHA=nao-e-real\n"}}) {
                z.putNextEntry(new ZipEntry(f[0]));
                z.write(f[1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        LinkRef link = new LinkRef("https://platform.exemplo.test", UUID.fromString("85388156-0000-4000-8000-000000000001"), UUID.randomUUID(), UUID.randomUUID());
        LinkCheck ok = new LinkCheck(LinkCheckOutcome.CONFIRMED, link, Instant.now(), Optional.empty(), new Catalog(true, null, List.of()));
        ProjectImporter importer = new ProjectImporter(tmp.resolve("imp"), ZipLimits.DEFAULT);
        rec.flow = new ImportFlow(new ImportFlow.Link() {
            @Override
            public LinkCheck confirm() {
                return ok;
            }

            @Override
            public Optional<LinkRef> current() {
                return Optional.of(link);
            }
        }, importer, new com.nekkora.agent.pack.PackageHistory(tmp.resolve("imp").resolve("historico.json"), com.nekkora.agent.storage.AtomicWriter.DEFAULT),
                java.time.Clock.systemUTC(), Runnable::run, Runnable::run, v -> {});
        rec.flow.start();
        rec.flow.chooseZip(zip);
        assertEquals(Stage.CONFIGURE, rec.flow.view().stage());
        return rec.flow.view();
    }

    @Test
    void entryIsNeverChosenAloneAndGenerateNeedsTheChoices() throws Exception {
        Recorder rec = new Recorder();
        ImportFlow.View v = configured(rec);
        lone = DesktopHarness.onEdt(() -> {
            ImportDialog d = new ImportDialog(null, rec);
            d.render(v);
            d.setVisible(false);
            return d;
        });
        javax.swing.SwingUtilities.invokeLater(() -> lone.showDialog());
        new DesktopHarnessWait().until(() -> lone.isShowing(), "assistente visível");
        String all = String.join("\n", DesktopHarness.onEdt(() -> DesktopHarness.texts(lone.getContentPane())));
        assertTrue(all.contains("Pasta “relatorio” (pasta externa única do ZIP)"), "a raiz reconhecida aparece antes de empacotar");
        assertTrue(all.contains("Organização 85388156…"), "organização pela identificação disponível");
        assertTrue(all.contains("A Platform não informa o nome da organização"), all);
        assertNull(DesktopHarness.onEdt(() -> lone.entry.getSelectedValue()), "a entrada não é escolhida sozinha, nem a sugerida");
        assertTrue(DesktopHarness.onEdt(() -> {
            for (int i = 0; i < lone.entry.getItemCount(); i++) if (lone.entry.getItemAt(i).label().equals("main.py  (sugerido pelo nome; confira)")) return true;
            return false;
        }), "main.py aparece como sugestão, não como escolha");
        assertFalse(DesktopHarness.onEdt(() -> lone.generateButton().isEnabled()));
        assertTrue(DesktopHarness.onEdt(lone::suggestionShown).contains("Informe o nome do projeto"), "sem nome, nenhuma versão é sugerida");
        capture(lone, "PAC-T02-configurar-inicio");
        // Tab percorre os campos principais, na ordem da tela (política de foco real da janela)
        List<Component> visited = DesktopHarness.onEdt(() -> {
            List<Component> out = new ArrayList<>();
            java.awt.FocusTraversalPolicy policy = lone.getFocusTraversalPolicy();
            Component c = lone.newProject;
            for (int i = 0; i < 20 && c != null; i++) {
                c = policy.getComponentAfter(lone, c);
                out.add(c);
            }
            return out;
        });
        assertTrue(visited.contains(lone.name), "Tab chega ao nome");
        assertTrue(visited.contains(lone.version), "Tab chega à versão");
        assertTrue(visited.stream().anyMatch(c -> c == lone.entry || javax.swing.SwingUtilities.isDescendingFrom(c, lone.entry)), "Tab chega à entrada");
        // escolhas da pessoa
        DesktopHarness.onEdt(() -> {
            lone.name.setText("relatorio");
            lone.version.setText("1.0.0");
            lone.python.setText(">=3.10,<3.14");
            lone.dependenciesConfirmed.setSelected(true);
            lone.dependenciesConfirmed.getActionListeners()[0].actionPerformed(null);
            lone.entry.setSelectedIndex(0);
            return null;
        });
        assertTrue(DesktopHarness.onEdt(() -> lone.generateButton().isEnabled()), "com nome, versão e entrada: Gerar habilitado");
        assertTrue(DesktopHarness.onEdt(lone::suggestionShown).contains("Sugestão local 1.0.0"), "1.0.0 rotulada como sugestão local");
        assertEquals("main.py", DesktopHarness.onEdt(() -> lone.plan().entry()));
        assertTrue(DesktopHarness.onEdt(() -> lone.plan().includeData().isEmpty()), "dados ficam fora até a pessoa incluir");
        capture(lone, "PAC-T02-configurar-pronto");
        // janela estreita (escala alta / tela pequena): nada quebra, o conteúdo rola
        DesktopHarness.onEdt(() -> {
            lone.setSize(420, 520);
            lone.validate();
            return null;
        });
        capture(lone, "PAC-T02-configurar-estreito");
    }

    @Test
    void resultShowsAssociationAndNoFullPath() throws Exception {
        Recorder rec = new Recorder();
        ImportFlow.View v = configured(rec);
        ImportedProject p = v.project();
        Path out = Files.createDirectories(tmp.resolve("Minha Pasta Secreta de Cliente"));
        Packager.Built b = Packager.build(p, new PackagePlan("relatorio", "1.0.0", "main.py", ">=3.10,<3.14", List.of(), true, Set.of(), Set.of(), false), out, false,
                SafeZip.Cancel.NEVER);
        ImportFlow.View done = new ImportFlow.View(Stage.DONE, v.check(), p, null, b, null, v.generation());
        lone = DesktopHarness.onEdt(() -> {
            ImportDialog d = new ImportDialog(null, rec);
            d.render(done);
            return d;
        });
        javax.swing.SwingUtilities.invokeLater(() -> lone.showDialog());
        new DesktopHarnessWait().until(() -> lone.isShowing(), "resultado visível");
        String all = String.join("\n", DesktopHarness.onEdt(() -> DesktopHarness.texts(lone.getContentPane())));
        assertTrue(all.contains("relatorio-1.0.0.zip"), all);
        assertTrue(all.contains("Organização associada"), all);
        assertTrue(all.contains("não foi publicada nem reservada"), all);
        assertFalse(all.contains(out.toString()), "o caminho completo não aparece (a tela pode ser capturada)");
        capture(lone, "PAC-T02-resultado");
        DesktopHarness.onEdt(() -> {
            lone.openFolder.doClick();
            return null;
        });
        assertTrue(rec.calls.contains("openFolder"));
        assertTrue(DesktopHarness.onEdt(lone.prepareRun::isEnabled), "Preparar execução disponível no resultado");
        DesktopHarness.onEdt(() -> {
            lone.prepareRun.doClick();
            return null;
        });
        assertTrue(rec.calls.contains("prepare relatorio-1.0.0.zip"), "prepara o ZIP gerado; aprovar e iniciar continuam na Execução local: " + rec.calls);
    }

    /** Alt+tecla entregue ao componente (o Swing procura o atalho na janela dele). */
    static void altKey(Component c, int code) {
        key(c, code, java.awt.event.InputEvent.ALT_DOWN_MASK);
    }

    static void key(Component c, int code, int modifiers) {
        long t = System.currentTimeMillis();
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED, t, modifiers, code, KeyEvent.CHAR_UNDEFINED));
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED, t, modifiers, code, KeyEvent.CHAR_UNDEFINED));
    }

    /** Espera simples na EDT para componentes sem aplicativo. */
    static final class DesktopHarnessWait {
        void until(java.util.function.BooleanSupplier c, String what) throws Exception {
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (true) {
                java.util.concurrent.atomic.AtomicBoolean ok = new java.util.concurrent.atomic.AtomicBoolean();
                javax.swing.SwingUtilities.invokeAndWait(() -> ok.set(c.getAsBoolean()));
                if (ok.get()) return;
                if (System.nanoTime() > deadline) throw new AssertionError("tempo esgotado: " + what);
                Thread.sleep(20);
            }
        }
    }
}
