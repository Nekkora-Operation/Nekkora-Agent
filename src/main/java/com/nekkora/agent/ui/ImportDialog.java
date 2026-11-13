package com.nekkora.agent.ui;

import com.nekkora.agent.app.ImportFlow;
import com.nekkora.agent.app.ImportFlow.ProjectMode;
import com.nekkora.agent.app.ImportFlow.Stage;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.pack.Declarations;
import com.nekkora.agent.pack.ImportProblem;
import com.nekkora.agent.pack.ImportedProject;
import com.nekkora.agent.pack.ImportedProject.Kind;
import com.nekkora.agent.pack.ImportedProject.ProjectFile;
import com.nekkora.agent.pack.InclusionRules.Rule;
import com.nekkora.agent.pack.PackagePlan;
import com.nekkora.agent.pack.Packager;
import com.nekkora.agent.pack.ZipNames;
import com.nekkora.agent.platform.PlatformService.LinkCheck;
import com.nekkora.agent.platform.PlatformService.LinkRef;
import com.nekkora.agent.platform.RemoteCatalog;
import com.nekkora.tokens.Spacing;
import com.nekkora.ui.button.NekkoraButton;
import com.nekkora.ui.container.NekkoraCard;
import com.nekkora.ui.container.NekkoraCollapsiblePanel;
import com.nekkora.ui.container.NekkoraScrollPane;
import com.nekkora.ui.container.NekkoraStack;
import com.nekkora.ui.core.Severity;
import com.nekkora.ui.core.Status;
import com.nekkora.ui.data.NekkoraKeyValueView;
import com.nekkora.ui.data.NekkoraStatusBadge;
import com.nekkora.ui.feedback.NekkoraInlineAlert;
import com.nekkora.ui.input.NekkoraCheckBox;
import com.nekkora.ui.input.NekkoraComboBox;
import com.nekkora.ui.input.NekkoraFormField;
import com.nekkora.ui.input.NekkoraRadioButton;
import com.nekkora.ui.input.NekkoraTextArea;
import com.nekkora.ui.input.NekkoraTextField;
import com.nekkora.ui.typography.NekkoraParagraph;
import com.nekkora.ui.typography.TextTone;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * AGENT-PACOTES-001 T02: o assistente "Importar projeto (.zip)". Só mostra o {@link ImportFlow.View} e chama as ações; não
 * guarda estado além dos campos do formulário. Ordem: vínculo confirmado → ZIP → projeto, versão declarada e entrada (a
 * pessoa escolhe; nada é confirmado sozinho) → requisitos só quando faltam → arquivos → gerar. Nada é executado nem enviado.
 */
final class ImportDialog extends FittedDialog {

    interface Actions {
        void retry();

        void chooseZip();

        /** A pessoa pediu para gerar: o app escolhe a pasta (e pede consentimento para substituir). */
        void generate(PackagePlan plan, ProjectMode mode, UUID automationId);

        void openFolder(Path zip);

        ImportFlow.Suggestion suggest(ProjectMode mode, String name, UUID automationId, PackagePlan draft);

        List<RemoteCatalog.Project> catalog();

        /** PLATFORM-AGENT-CATALOGO-001: consultar a Platform de novo sem descartar o ZIP (uma consulta por vez). */
        default void refreshCatalog() {}

        /** T03: abrir a "Execução local" com este ZIP já escolhido (a aprovação e o início continuam com a pessoa). */
        default void prepare(Path zip) {}
    }

    /** Item do catálogo, pelo identificador estável, com contexto legível. */
    record CatalogItem(UUID automationId, String label) {}

    /** Item da lista de entrada. */
    record EntryItem(String path, String label, boolean usable) {}

    private final Actions actions;
    private final NekkoraStack body = NekkoraStack.vertical(Spacing.SPACE_6).align(NekkoraStack.Align.STRETCH);
    private final NekkoraCard linkCard = new NekkoraCard("Vínculo com a Platform", null);
    private final NekkoraCard fileCard = new NekkoraCard("Projeto (.zip)", null);
    private final NekkoraCard projectCard = new NekkoraCard("Identificação do pacote", null);
    private final NekkoraCard filesCard = new NekkoraCard("Arquivos do pacote", null);
    private final NekkoraCard resultCard = new NekkoraCard("Pacote gerado", null);

    final NekkoraButton importButton = NekkoraButton.primary(ImportText.BUTTON + "…");
    final NekkoraButton retry = NekkoraButton.secondary("Tentar de novo");
    final NekkoraButton refresh = NekkoraButton.secondary("Consultar a Platform de novo");
    final NekkoraRadioButton existingProject = new NekkoraRadioButton("Usar projeto existente");
    final NekkoraRadioButton newProject = new NekkoraRadioButton("Preparar novo projeto", true);
    final NekkoraTextField name = new NekkoraTextField("ex.: relatorio-vendas", 24);
    final NekkoraComboBox<CatalogItem> automation = new NekkoraComboBox<>();
    final NekkoraTextField version = new NekkoraTextField("x.y.z", 10);
    final NekkoraButton useSuggestion = NekkoraButton.ghost("Usar sugestão");
    final NekkoraComboBox<EntryItem> entry = new NekkoraComboBox<>();
    final NekkoraTextField python = new NekkoraTextField(">=3.10,<3.14", 16);
    final NekkoraTextArea dependencies = new NekkoraTextArea("uma por linha: nome==1.2.3", 4);
    final NekkoraCheckBox dependenciesConfirmed = new NekkoraCheckBox("Conferi: a lista acima tem todas as dependências do projeto (vazia = nenhuma)");
    final NekkoraButton rebuild = NekkoraButton.secondary("Preparar de novo (mudar entrada ou requisitos)…");
    final NekkoraButton openFolder = NekkoraButton.secondary("Abrir pasta");
    final NekkoraButton prepareRun = NekkoraButton.primary("Preparar execução…");
    final NekkoraButton prepareReady = NekkoraButton.primary("Preparar execução…");
    private final NekkoraButton generate;
    private final NekkoraParagraph suggestionText = new NekkoraParagraph("", TextTone.SECONDARY);
    private final NekkoraParagraph versionsText = new NekkoraParagraph("", TextTone.SECONDARY);
    private final NekkoraStack warningsBox = NekkoraStack.vertical(Spacing.SPACE_2).align(NekkoraStack.Align.STRETCH);
    private final NekkoraFormField pythonField;
    private final NekkoraFormField dependenciesField;
    private final NekkoraFormField nameField;
    private final NekkoraFormField automationField;
    private final Set<String> includeData = new LinkedHashSet<>();
    private final Set<String> exclude = new LinkedHashSet<>();

    private ImportFlow.View view;
    private LinkCheck lastCheck;
    private ImportedProject formFor;
    private boolean rebuildMode;
    private boolean updating;
    private Optional<String> suggested = Optional.empty();

    ImportDialog(Component parent, Actions actions) {
        super(parent, ImportText.TITLE);
        this.actions = actions;
        setDescription(ImportText.HELP + " " + ImportText.NOTHING_RUNS);
        setDialogSize(Size.LARGE);
        NekkoraRadioButton.group(newProject, existingProject);
        importButton.setMnemonic(KeyEvent.VK_I);
        importButton.getAccessibleContext().setAccessibleDescription(ImportText.HELP);
        retry.setMnemonic(KeyEvent.VK_T);
        refresh.setMnemonic(KeyEvent.VK_C);
        refresh.getAccessibleContext().setAccessibleDescription("Consulta o vínculo e a lista de projetos de novo; o ZIP lido continua");
        newProject.setMnemonic(KeyEvent.VK_N);
        existingProject.setMnemonic(KeyEvent.VK_X);
        useSuggestion.setMnemonic(KeyEvent.VK_U);
        openFolder.setMnemonic(KeyEvent.VK_A);
        automation.setLabelProvider(CatalogItem::label);
        automation.setPlaceholder("Escolha o projeto");
        automation.getAccessibleContext().setAccessibleName("Projeto existente na Platform");
        entry.setLabelProvider(EntryItem::label);
        entry.setPlaceholder("Escolha o arquivo .py de entrada");
        entry.getAccessibleContext().setAccessibleName("Arquivo de entrada");
        name.getAccessibleContext().setAccessibleName("Nome do pacote");
        version.getAccessibleContext().setAccessibleName("Versão declarada no pacote");
        python.getAccessibleContext().setAccessibleName("Versão de Python exigida");
        dependencies.getAccessibleContext().setAccessibleName("Dependências com versão exata");
        nameField = new NekkoraFormField("Nome do pacote", name).required().helper("Letras sem acento, números, ponto, hífen ou sublinhado.");
        automationField = new NekkoraFormField("Projeto existente", automation).required().helper("Escolhido por você na lista; o Agent não escolhe pelo nome.");
        pythonField = new NekkoraFormField("Versão de Python", python).required();
        dependenciesField = new NekkoraFormField("Dependências", dependencies.scrollable()).required();

        importButton.addActionListener(e -> actions.chooseZip());
        retry.addActionListener(e -> actions.retry());
        refresh.addActionListener(e -> {
            refresh.setEnabled(false); // uma consulta por vez; volta com a próxima tela
            actions.refreshCatalog();
        });
        openFolder.addActionListener(e -> {
            if (view != null && view.built() != null) actions.openFolder(view.built().zip());
        });
        prepareRun.setMnemonic(KeyEvent.VK_P);
        prepareReady.setMnemonic(KeyEvent.VK_P);
        prepareRun.getAccessibleContext().setAccessibleDescription("Abre a Execução local com este pacote; aprovar e iniciar continuam com você");
        prepareReady.getAccessibleContext().setAccessibleDescription("Abre a Execução local com este ZIP; aprovar e iniciar continuam com você");
        prepareRun.addActionListener(e -> {
            if (view != null && view.built() != null) actions.prepare(view.built().zip());
        });
        prepareReady.addActionListener(e -> {
            if (view != null && view.project() != null) actions.prepare(view.project().source());
        });
        rebuild.addActionListener(e -> {
            rebuildMode = true;
            formFor = null;
            render(view);
        });
        useSuggestion.addActionListener(e -> suggested.ifPresent(version::setText));
        newProject.addActionListener(e -> formChanged());
        existingProject.addActionListener(e -> formChanged());
        automation.addActionListener(e -> formChanged());
        entry.addActionListener(e -> formChanged());
        dependenciesConfirmed.addActionListener(e -> formChanged());
        DocumentListener d = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                formChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                formChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                formChanged();
            }
        };
        name.getDocument().addDocumentListener(d);
        version.getDocument().addDocumentListener(d);
        python.getDocument().addDocumentListener(d);
        dependencies.getDocument().addDocumentListener(d);

        body.setPadding(Spacing.SPACE_2);
        body.addAll(linkCard, fileCard, projectCard, filesCard, resultCard);
        NekkoraScrollPane sp = NekkoraScrollPane.verticalOnly(body);
        sp.setBorder(null);
        setContent(sp);
        addSecondary("Fechar");
        generate = addPrimary("Gerar pacote…", () -> {
            requestGenerate();
            return false; // o diálogo continua aberto para mostrar o resultado
        });
        generate.setMnemonic(KeyEvent.VK_G);
        getRootPane().setDefaultButton(null);
        render(new ImportFlow.View(Stage.CHECKING, null, null, null, null, null, 0));
    }

    // ------------------------------------------------------------------ desenho

    void render(ImportFlow.View v) {
        this.view = v;
        renderLink(v);
        renderFile(v);
        boolean configure = v.stage() == Stage.CONFIGURE && v.project() != null && (v.project().kind() == Kind.CODE || rebuildMode && v.project().kind() == Kind.EXTERNAL_PACKAGE);
        if (v.project() != formFor) {
            if (v.project() == null || v.project().kind() != Kind.EXTERNAL_PACKAGE) rebuildMode = false;
            if (configure) initForm(v.project());
            formFor = configure ? v.project() : null;
        } else if (configure && v.check() != lastCheck && lastCheck != null) {
            // nova consulta à Platform (Consultar de novo): a lista muda; a escolha só fica se o mesmo projeto continua visível
            fillCatalog(automationId());
            renderProject(v.project());
        }
        lastCheck = v.check();
        projectCard.setVisible(configure);
        filesCard.setVisible(configure);
        if (configure) renderFiles(v.project());
        renderResult(v);
        updateButtons();
        body.revalidate();
        body.repaint();
    }

    private void renderLink(ImportFlow.View v) {
        NekkoraStack s = NekkoraStack.vertical(Spacing.SPACE_4).align(NekkoraStack.Align.STRETCH);
        LinkCheck c = v.check();
        if (v.stage() == Stage.CHECKING) {
            s.add(new NekkoraInlineAlert(Severity.INFO, "Confirmando o vínculo", "Consultando a Platform para confirmar o vínculo desta máquina…"));
        } else if (v.stage() == Stage.INVALIDATED) {
            s.add(new NekkoraInlineAlert(Severity.WARNING, "Vínculo mudou", v.note()));
            s.add(retry);
        } else if (c == null || !c.confirmed()) {
            s.add(new NekkoraInlineAlert(c != null && c.outcome() == com.nekkora.agent.platform.PlatformService.LinkCheckOutcome.NOT_PAIRED ? Severity.INFO : Severity.WARNING,
                    "Importação bloqueada", ImportText.blocked(c)));
            s.add(retry);
        } else {
            NekkoraKeyValueView kv = new NekkoraKeyValueView();
            NekkoraStatusBadge b = new NekkoraStatusBadge(Status.SUCCESS, "Confirmado agora");
            b.getAccessibleContext().setAccessibleName("Vínculo confirmado pela Platform");
            kv.add("Vínculo", b);
            kv.add("Organização", ImportText.organization(c));
            s.add(kv);
            s.add(new NekkoraParagraph(ImportText.confirmed(c), TextTone.SECONDARY));
            RemoteCatalog cat = c.catalog();
            Severity sev = cat.complete() ? Severity.INFO : cat.state() == RemoteCatalog.State.UNSUPPORTED ? Severity.ERROR : Severity.WARNING;
            s.add(new NekkoraInlineAlert(sev, cat.complete() ? "Projetos da Platform" : "Lista de projetos incompleta", ImportText.catalogNote(c)));
            s.add(refresh);
        }
        linkCard.setContent(s);
    }

    private void renderFile(ImportFlow.View v) {
        NekkoraStack s = NekkoraStack.vertical(Spacing.SPACE_4).align(NekkoraStack.Align.STRETCH);
        NekkoraParagraph help = new NekkoraParagraph(ImportText.HELP, TextTone.SECONDARY);
        s.addAll(importButton, help);
        if (v.stage() == Stage.READING) s.add(new NekkoraInlineAlert(Severity.INFO, "Lendo o ZIP", "Conferindo os bytes e a estrutura numa pasta privada do Agent…"));
        if (v.problem() != null && v.stage() != Stage.CONFIGURE && v.stage() != Stage.BUILDING) {
            s.add(new NekkoraInlineAlert(Severity.ERROR, "ZIP não aceito", v.problem().message()));
        }
        ImportedProject p = v.project();
        if (p != null) {
            NekkoraKeyValueView kv = new NekkoraKeyValueView();
            kv.add("Arquivo", ZipNames.display(String.valueOf(p.source().getFileName())));
            kv.add("Raiz do projeto", p.rootFolder().isEmpty() ? "A raiz do ZIP" : "Pasta “" + ZipNames.display(p.rootFolder()) + "” (pasta externa única do ZIP)");
            kv.add("Formato reconhecido", p.kind().label());
            if (p.existing() != null) {
                ExecutionPackage e = p.existing();
                kv.add("Nome declarado", e.name());
                if (e.packageVersion() != null) kv.add("Versão declarada", e.packageVersion());
                kv.add("Entrada", e.entry());
            }
            s.add(kv);
            for (ImportProblem b : p.blockers()) s.add(new NekkoraInlineAlert(Severity.ERROR, "Não dá para gerar um pacote deste ZIP", b.message()));
            for (String n : p.notes()) s.add(new NekkoraInlineAlert(Severity.INFO, "Observação", n));
            if (p.kind() == Kind.EXTERNAL_PACKAGE && !rebuildMode) {
                s.add(new NekkoraParagraph("Este ZIP já é um pacote Python externo válido: ele é usado como está, sem regravar o manifesto. Para mudar "
                        + "a entrada ou os requisitos, prepare de novo (um pacote NOVO, com nova integridade e nova aprovação).", TextTone.SECONDARY));
                NekkoraStack b = NekkoraStack.horizontal(Spacing.SPACE_4).align(NekkoraStack.Align.CENTER);
                b.addAll(prepareReady, rebuild);
                s.add(b);
            }
            if (p.kind() == Kind.STUDIO_PACKAGE || p.kind() == Kind.LAB_PACKAGE) {
                s.add(new NekkoraParagraph("Pacote reconhecido: ele segue pelo fluxo de execução já suportado, sem conversão para pacote Python externo.",
                        TextTone.SECONDARY));
                s.add(prepareReady);
            }
        }
        fileCard.setContent(s);
    }

    private void initForm(ImportedProject p) {
        updating = true;
        try {
            includeData.clear();
            exclude.clear();
            ExecutionPackage existing = p.kind() == Kind.EXTERNAL_PACKAGE ? p.existing() : null;
            name.setText(existing != null ? existing.name() : "");
            version.setText("");
            Declarations d = p.declarations();
            python.setText(existing != null ? existing.pythonRequirement() : d.python().orElse(""));
            List<String> deps = existing != null ? existing.dependencies() : d.exact();
            dependencies.setText(String.join("\n", deps));
            dependenciesConfirmed.setSelected(false);
            newProject.setSelected(true);
            fillCatalog(null);
            entry.removeAllItems();
            for (String py : p.pythonFiles()) {
                boolean ok = ZipNames.contractSafe(py);
                String label = ZipNames.display(py) + (p.suggestedEntry().map(py::equals).orElse(false) ? "  (sugerido pelo nome; confira)" : "")
                        + (ok ? "" : "  (nome fora do formato do pacote)");
                entry.addItem(new EntryItem(py, label, ok));
            }
            // nada é escolhido sozinho, nem a sugestão: a pessoa confirma a entrada
            entry.setSelectedItem(existing != null ? entry.getItemCount() > 0 ? findEntry(existing.entry()) : null : null);
        } finally {
            updating = false;
        }
        renderProject(p);
    }

    /** A lista de projetos do catálogo; mantém a escolha só se o mesmo identificador continua na lista. */
    private void fillCatalog(UUID keep) {
        boolean was = updating;
        updating = true;
        try {
            automation.removeAllItems();
            CatalogItem selected = null;
            for (RemoteCatalog.Project c : actions.catalog()) {
                CatalogItem it = new CatalogItem(c.automationId(), ImportText.project(c));
                automation.addItem(it);
                if (c.automationId().equals(keep)) selected = it;
            }
            automation.setSelectedItem(selected);
        } finally {
            updating = was;
        }
    }

    private EntryItem findEntry(String path) {
        for (int i = 0; i < entry.getItemCount(); i++) if (entry.getItemAt(i).path().equals(path)) return entry.getItemAt(i);
        return null;
    }

    private void renderProject(ImportedProject p) {
        NekkoraStack s = NekkoraStack.vertical(Spacing.SPACE_5).align(NekkoraStack.Align.STRETCH);
        NekkoraStack modes = NekkoraStack.vertical(Spacing.SPACE_2).align(NekkoraStack.Align.STRETCH);
        modes.addAll(existingProject, newProject);
        s.add(modes);
        boolean existing = existingProject.isSelected();
        if (existing) {
            s.add(automationField);
            s.add(versionsText);
            s.add(new NekkoraParagraph(ImportText.catalogNote(view.check()), TextTone.SECONDARY));
        } else {
            s.add(new NekkoraParagraph("“Preparar novo projeto” associa o pacote a esta organização só nesta máquina; não cria projeto, rascunho nem versão na "
                    + "Platform.", TextTone.SECONDARY));
        }
        automation.setEnabled(automation.getItemCount() > 0);
        s.add(nameField);
        NekkoraStack v = NekkoraStack.horizontal(Spacing.SPACE_4).align(NekkoraStack.Align.CENTER);
        v.addAll(version, useSuggestion);
        s.add(new NekkoraFormField("Versão declarada no pacote", v).required().helper("packageVersion do manifesto; não é o número da versão na Platform."));
        s.add(suggestionText);
        s.add(warningsBox);
        s.add(new NekkoraFormField("Arquivo de entrada", entry).required().helper("O .py que o Agent executa; o código não é executado para descobrir."));
        Declarations d = p.declarations();
        boolean pyDeclared = d.python().isPresent() || p.kind() == Kind.EXTERNAL_PACKAGE;
        if (pyDeclared && !rebuildMode) {
            s.add(new NekkoraParagraph("Python exigido: " + python.getText() + " (declarado em " + (d.pythonSource() == null ? "nekkora-external.json" : d.pythonSource())
                    + "). Declarado não é validado: o ambiente é conferido na preparação da execução.", TextTone.SECONDARY));
        } else {
            pythonField.helper("Formato aceito, por exemplo >=3.10,<3.14 ou ==3.12.*.");
            s.add(new NekkoraParagraph(d.pythonUnsupported().map(u -> "O projeto declara " + u + ", que o formato do pacote não aceita como está. ")
                    .orElse("O projeto não declara a versão de Python. ") + "Informe a versão exigida; o Agent não escolhe uma por conta própria.", TextTone.SECONDARY));
            s.add(pythonField);
        }
        if (d.dependenciesNeedConfirmation() || rebuildMode) {
            StringBuilder why = new StringBuilder();
            switch (d.dependencyStatus()) {
                case NOT_DECLARED -> why.append("O projeto não declara dependências. Isso não quer dizer que não tenha: o Agent não deduz pelos imports. ");
                case PARTIAL -> why.append("O projeto declara dependências que o formato do pacote não aceita como estão");
                default -> why.append("Confira a lista. ");
            }
            if (!d.unsupported().isEmpty()) why.append(" (").append(String.join("; ", d.unsupported())).append(")");
            if (!d.otherFiles().isEmpty()) why.append(". Arquivos de declaração que o Agent não lê: ").append(String.join(", ", d.otherFiles()));
            why.append(". Cada dependência precisa de versão exata (nome==1.2.3).");
            dependenciesField.helper("Uma por linha, com versão exata: nome==1.2.3.");
            s.add(new NekkoraParagraph(why.toString(), TextTone.SECONDARY));
            s.add(dependenciesField);
            s.add(dependenciesConfirmed);
        } else {
            s.add(new NekkoraParagraph("Dependências declaradas no requirements.txt: " + (d.exact().isEmpty() ? "nenhuma linha (lista vazia declarada pelo projeto)"
                    : String.join(", ", d.exact())) + ". Declaradas não quer dizer instaladas: o ambiente é conferido na preparação da execução.", TextTone.SECONDARY));
        }
        projectCard.setContent(s);
        refreshSuggestion();
    }

    private void renderFiles(ImportedProject p) {
        NekkoraStack s = NekkoraStack.vertical(Spacing.SPACE_4).align(NekkoraStack.Align.STRETCH);
        int in = 0;
        List<ProjectFile> out = new ArrayList<>();
        for (ProjectFile f : p.files()) {
            if (f.rule() == Rule.DESCRIPTOR) continue;
            boolean included = f.rule() == Rule.DATA ? includeData.contains(f.path()) : f.rule().included() && !exclude.contains(f.path());
            if (included) in++;
            else out.add(f);
        }
        s.add(new NekkoraParagraph(in + " arquivo(s) no pacote · " + out.size() + " fora. A estrutura de pastas é preservada.", TextTone.SECONDARY));
        NekkoraStack rows = NekkoraStack.vertical(Spacing.SPACE_2).align(NekkoraStack.Align.STRETCH);
        int shown = 0;
        for (ProjectFile f : p.files()) {
            if (f.rule() == Rule.DESCRIPTOR || shown >= 300) continue;
            String label = ZipNames.display(f.path());
            if (f.rule() == Rule.DATA) {
                NekkoraCheckBox c = new NekkoraCheckBox("Incluir " + label + " — " + f.rule().label(), includeData.contains(f.path()));
                c.addActionListener(e -> toggle(includeData, f.path(), c.isSelected(), p));
                rows.add(c);
            } else if (f.rule().included()) {
                if (f.rule() == Rule.INCLUDE_WARNED || !f.contractSafe()) {
                    String why = !f.contractSafe() ? "nome fora do formato do pacote (espaço, acento ou caractere especial): só entra renomeado no projeto"
                            : f.rule().label();
                    NekkoraCheckBox c = new NekkoraCheckBox("Deixar fora " + label + " — " + why, exclude.contains(f.path()));
                    c.addActionListener(e -> toggle(exclude, f.path(), c.isSelected(), p));
                    rows.add(c);
                } else {
                    rows.add(new NekkoraParagraph(label, TextTone.SECONDARY));
                }
            } else {
                rows.add(new NekkoraParagraph(label + " — " + f.rule().label(), TextTone.MUTED));
            }
            shown++;
        }
        s.add(new NekkoraCollapsiblePanel("Lista de arquivos", rows));
        if (p.files().stream().anyMatch(f -> f.rule() == Rule.SECRET || f.rule() == Rule.INCLUDE_WARNED)) {
            s.add(new NekkoraInlineAlert(Severity.WARNING, "Confira arquivos sensíveis", "Arquivos como .env, chaves e credenciais ficam fora do pacote. A "
                    + "conferência pelo nome não garante ausência de segredos ou de código malicioso: revise o projeto antes de gerar."));
        }
        filesCard.setContent(s);
    }

    private void toggle(Set<String> set, String path, boolean on, ImportedProject p) {
        if (on) set.add(path);
        else set.remove(path);
        renderFiles(p);
        refreshSuggestion();
        updateButtons();
        body.revalidate();
    }

    private void renderResult(ImportFlow.View v) {
        Packager.Built b = v.built();
        resultCard.setVisible(v.stage() == Stage.DONE && b != null || v.stage() == Stage.BUILDING || v.stage() == Stage.CONFIGURE && v.problem() != null);
        NekkoraStack s = NekkoraStack.vertical(Spacing.SPACE_4).align(NekkoraStack.Align.STRETCH);
        if (v.stage() == Stage.BUILDING) {
            resultCard.setTitle("Gerando o pacote", null);
            s.add(new NekkoraInlineAlert(Severity.INFO, "Gerando", "Confirmando o vínculo de novo e gravando o pacote (nada é executado)…"));
        } else if (v.stage() == Stage.CONFIGURE && v.problem() != null) {
            resultCard.setTitle("Pacote não gerado", null);
            s.add(new NekkoraInlineAlert(Severity.ERROR, "Pacote não gerado", v.problem().message()));
        } else if (v.stage() == Stage.CONFIGURE && v.note() != null) {
            resultCard.setTitle("Pacote não gerado", null);
            s.add(new NekkoraInlineAlert(Severity.WARNING, "Pacote não gerado", v.note()));
            resultCard.setVisible(true);
        } else if (v.stage() == Stage.CONFIGURE && v.check() != null && !v.check().confirmed()) {
            resultCard.setTitle("Pacote não gerado", null);
            s.add(new NekkoraInlineAlert(Severity.WARNING, "Vínculo não confirmado na conclusão", ImportText.blocked(v.check())));
            resultCard.setVisible(true);
        } else if (b != null) {
            resultCard.setTitle("Pacote gerado", null);
            LinkRef link = v.check() == null ? null : v.check().link();
            NekkoraKeyValueView kv = new NekkoraKeyValueView();
            kv.add("Nome", b.name());
            kv.add("Versão declarada", b.version());
            kv.add("Entrada", b.entry());
            kv.add("Organização associada", ImportText.organization(v.check()));
            if (projectChoice != null) kv.add("Projeto", projectChoice);
            kv.add("Pacote", ZipNames.display(String.valueOf(b.zip().getFileName())));
            kv.add("Pasta", ZipNames.display(String.valueOf(b.zip().getParent().getFileName())));
            s.add(kv);
            s.add(new NekkoraParagraph("A versão declarada é a escrita no pacote: não foi publicada nem reservada na Platform. A associação com a organização é "
                    + "local desta máquina (o manifesto não tem esse campo). Gerar o pacote não executou nenhum código.", TextTone.SECONDARY));
            if (v.note() != null) s.add(new NekkoraInlineAlert(Severity.WARNING, "Atenção", v.note()));
            NekkoraStack buttons = NekkoraStack.horizontal(Spacing.SPACE_4).align(NekkoraStack.Align.CENTER);
            buttons.addAll(prepareRun, openFolder);
            s.add(buttons);
            NekkoraTextArea tech = new NekkoraTextArea("", 7);
            tech.setText("Impressão (" + ExecutionPackage.EXTERNAL_RULE + "):\n  " + b.fingerprint().substring("sha256:".length())
                    + "\nSHA-256 do ZIP (bytes do arquivo; não se compara com a impressão):\n  " + b.sha256()
                    + "\nIdentidade de conteúdo local (" + Packager.CONTENT_RULE + "):\n  " + b.contentIdentity().substring("sha256:".length())
                    + "\nOrganização: " + (link == null ? "—" : link.organizationId()) + "\nArquivos no pacote: " + b.files());
            tech.setReadOnly(true);
            tech.setLineWrap(true);
            tech.getAccessibleContext().setAccessibleName("Detalhes técnicos do pacote");
            s.add(new NekkoraCollapsiblePanel("Detalhes técnicos", tech));
        }
        if (v.stage() == Stage.CONFIGURE && v.check() != null && !v.check().confirmed()) resultCard.setVisible(true);
        resultCard.setContent(s);
    }

    // ------------------------------------------------------------------ formulário

    private void formChanged() {
        if (updating || view == null || view.project() == null) return;
        if (projectCard.isVisible()) {
            boolean modeChanged = automationField.getParent() == null == existingProject.isSelected();
            if (modeChanged) renderProject(view.project());
            else refreshSuggestion();
        }
        updateButtons();
    }

    /** As escolhas atuais como plano (sem validar: quem valida é o empacotador). */
    PackagePlan plan() {
        EntryItem e = entry.getSelectedValue();
        List<String> deps = new ArrayList<>();
        for (String line : dependencies.getText().split("\\R")) if (!line.isBlank()) deps.add(line.strip());
        return new PackagePlan(name.getText().strip(), version.getText().strip(), e == null ? null : e.path(), python.getText().strip(), deps,
                dependenciesConfirmed.isSelected(), Set.copyOf(includeData), Set.copyOf(exclude), rebuildMode);
    }

    ProjectMode mode() {
        return existingProject.isSelected() ? ProjectMode.EXISTING : ProjectMode.NEW;
    }

    UUID automationId() {
        CatalogItem c = automation.getSelectedValue();
        return existingProject.isSelected() && c != null ? c.automationId() : null;
    }

    private void refreshSuggestion() {
        if (view == null || view.project() == null) return;
        UUID id = automationId();
        versionsText.setText(id == null ? "" : actions.catalog().stream().filter(p -> p.automationId().equals(id)).findFirst()
                .map(p -> p.versions().isEmpty() ? "Nenhuma versão publicada deste projeto está autorizada a esta máquina."
                        : "Versões na Platform visíveis a esta máquina: " + String.join("; ", p.versions().stream().map(ImportText::version).toList())
                        + (p.versionsTruncated() ? " (só as mais recentes)." : "."))
                .orElse(""));
        ImportFlow.Suggestion s = actions.suggest(mode(), name.getText().strip(), id, plan());
        suggested = s.version();
        suggestionText.setText(s.text());
        warningsBox.removeAll();
        for (String w : s.warnings()) warningsBox.add(new NekkoraInlineAlert(Severity.WARNING, "Confira", w));
        warningsBox.revalidate();
        useSuggestion.setVisible(s.version().isPresent());
        useSuggestion.setText(s.version().map(x -> "Usar " + x).orElse("Usar sugestão"));
    }

    private void updateButtons() {
        ImportFlow.View v = view;
        Stage st = v == null ? Stage.CHECKING : v.stage();
        boolean linkOk = v != null && v.check() != null && v.check().confirmed() && st != Stage.INVALIDATED;
        importButton.setEnabled(linkOk && (st == Stage.READY || st == Stage.CONFIGURE || st == Stage.DONE));
        // preparar a execução também exige o vínculo confirmado no assistente (a execução em si segue as regras de sempre)
        prepareRun.setEnabled(linkOk && st == Stage.DONE);
        prepareReady.setEnabled(linkOk && (st == Stage.CONFIGURE || st == Stage.READY));
        retry.setEnabled(st == Stage.BLOCKED || st == Stage.INVALIDATED);
        refresh.setEnabled(linkOk && (st == Stage.READY || st == Stage.CONFIGURE || st == Stage.DONE));
        RemoteCatalog cat = v == null || v.check() == null ? null : v.check().catalog();
        boolean catalogOk = cat != null && cat.state() != RemoteCatalog.State.UNSUPPORTED
                && (existingProject.isSelected() ? automationId() != null : cat.complete());
        boolean configure = st == Stage.CONFIGURE && projectCard.isVisible();
        generate.setEnabled(configure && linkOk && catalogOk && entry.getSelectedValue() != null && entry.getSelectedValue().usable()
                && !name.getText().isBlank() && !version.getText().isBlank());
        generate.getAccessibleContext().setAccessibleDescription(generate.isEnabled() ? "Escolhe a pasta e grava o pacote" : "Indisponível: faltam escolhas");
    }

    private void requestGenerate() {
        if (!generate.isEnabled()) return;
        CatalogItem c = existingProject.isSelected() ? automation.getSelectedValue() : null;
        projectChoice = c == null ? "Novo (preparado nesta máquina; não cadastrado na Platform)" : c.label();
        actions.generate(plan(), mode(), automationId());
    }

    private String projectChoice;

    // acesso para testes de desktop
    NekkoraButton generateButton() {
        return generate;
    }

    String suggestionShown() {
        return suggestionText.getText();
    }
}
