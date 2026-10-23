package com.nekkora.agent.ui;

import com.nekkora.agent.exec.ExecutionController;
import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.ExecutionSetup;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.obs.ActivityEvent;
import com.nekkora.agent.obs.ActivityView;
import com.nekkora.tokens.Spacing;
import com.nekkora.ui.button.NekkoraButton;
import com.nekkora.ui.container.NekkoraCard;
import com.nekkora.ui.container.NekkoraScrollPane;
import com.nekkora.ui.container.NekkoraStack;
import com.nekkora.ui.core.Severity;
import com.nekkora.ui.core.Status;
import com.nekkora.ui.data.NekkoraKeyValueView;
import com.nekkora.ui.data.NekkoraStatusBadge;
import com.nekkora.ui.data.NekkoraTable;
import com.nekkora.ui.data.NekkoraTableModel;
import com.nekkora.ui.data.NekkoraTableModel.Column;
import com.nekkora.ui.feedback.NekkoraInlineAlert;
import com.nekkora.ui.icon.IconName;
import com.nekkora.ui.input.NekkoraComboBox;
import com.nekkora.ui.input.NekkoraSearchField;
import com.nekkora.ui.theme.Scale;
import com.nekkora.ui.typography.NekkoraCaption;
import com.nekkora.ui.typography.NekkoraParagraph;
import com.nekkora.ui.typography.NekkoraText;
import com.nekkora.ui.typography.TextTone;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.Timer;

/**
 * Aba "Atividade" da janela de execução (OBS-001, somente leitura): estado do supervisor, etapa atual, transações,
 * última falha e a linha do tempo de eventos do Agent, do Framework e do coletor — da execução em andamento ou de
 * qualquer execução do histórico, inclusive depois de reiniciar o Agent.
 *
 * <p>Nada aqui lê arquivo nem conhece o formato do Framework: os dados chegam pelo {@link Data} (fora da EDT, só o
 * envelope do Agent). Um {@link Timer} de {@link #REFRESH_MS} ms busca só os eventos NOVOS (por sequência) — a EDT
 * nunca espera disco e a tabela recebe lotes, não um evento por vez. O estado e o desfecho mostrados são sempre os do
 * supervisor; o último evento nunca é tomado como fim da execução.
 */
final class ActivityPanel extends JPanel {

    /** Dados da atividade (implementado pelo {@link AgentApp} com uma linha de fundo). Respostas chegam na EDT. */
    interface Data {
        /** O histórico; {@link RunStore.Listing#failed()} quando a leitura falhou (nunca confundido com "nenhuma execução"). */
        void history(Consumer<RunStore.Listing> onEdt);

        void view(ExecutionId id, long after, Consumer<Loaded> onEdt);

        void export(ExecutionId id, List<ActivityEvent> events, ActivityView.Completeness completeness);
    }

    /** Resposta de uma leitura: a atividade, "sem registro" (vazio) ou falha de leitura (tenta de novo sozinho). */
    record Loaded(Optional<ActivityView> view, boolean failed) {
        static Loaded of(Optional<ActivityView> v) {
            return new Loaded(v, false);
        }

        static Loaded failure() {
            return new Loaded(Optional.empty(), true);
        }
    }

    /** Situação da lista de execuções. */
    enum HistoryState { LOADING, EMPTY, LOADED, FAILED }

    static final int REFRESH_MS = 400;
    /** Espera antes de tentar de novo uma leitura que falhou (não martela o disco nem o log). */
    static final long RETRY_MS = 5_000;
    /** Leitura sem resposta além disto é dada como perdida: a aba avisa e pede de novo (nunca fica parada). */
    static final long LOST_REPLY_MS = 15_000;
    private static final List<String> PHASE_ORDER = List.of("INIT", "TRANSFER_DATA_IN", "PROCESS", "TRANSFER_DATA_OUT", "END");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final String ALL_LEVELS = "Todos os níveis", WARN_UP = "Avisos e erros", ERRORS = "Só erros";
    private static final String ALL_SOURCES = "Todas as origens", ALL_PHASES = "Todas as fases";
    private static final String BY_OBSERVATION = "Ordem de observação", BY_OCCURRENCE = "Ordem de ocorrência";

    private final Data data;
    private final NekkoraComboBox<ExecutionRecord> runs = new NekkoraComboBox<>();
    /** No lugar da lista quando não há o que escolher (o combo vazio ficaria em branco). */
    private final NekkoraText runsEmpty = new NekkoraText("", TextTone.SECONDARY);
    private final NekkoraButton reload = NekkoraButton.secondary("Atualizar lista");
    private final NekkoraCard summaryCard = new NekkoraCard("Acompanhamento da execução", null);
    private final NekkoraCard timeline = new NekkoraCard("Linha do tempo", null);
    private final NekkoraStack alerts = NekkoraStack.vertical(Spacing.SPACE_3).align(NekkoraStack.Align.STRETCH);
    private final NekkoraComboBox<String> level = new NekkoraComboBox<>(ALL_LEVELS, WARN_UP, ERRORS);
    private final NekkoraComboBox<String> source = new NekkoraComboBox<>(ALL_SOURCES, "Agent", "Framework", "Coletor");
    private final NekkoraComboBox<String> phase = new NekkoraComboBox<>(ALL_PHASES, "INIT", "TRANSFER_DATA_IN", "PROCESS", "TRANSFER_DATA_OUT", "END");
    private final NekkoraComboBox<String> order = new NekkoraComboBox<>(BY_OBSERVATION, BY_OCCURRENCE);
    private final NekkoraSearchField search = new NekkoraSearchField("Buscar etapa, ação, código…");
    private final NekkoraTableModel<ActivityEvent> model;
    private final NekkoraTable table;
    /** O painel de rolagem da tabela (criado UMA vez: cada {@code scrollable()} cria outro e leva a tabela junto). */
    private final NekkoraScrollPane tableScroll;
    private final NekkoraStack detail = NekkoraStack.vertical(Spacing.SPACE_3).align(NekkoraStack.Align.STRETCH);
    private final NekkoraCaption footer = new NekkoraCaption("", TextTone.SECONDARY);
    private final NekkoraButton export = NekkoraButton.secondary("Exportar atividade…");
    private final Timer timer;

    // estado (só na EDT)
    private ExecutionId selected;
    private ExecutionId current;
    private boolean follow = true;
    private boolean updatingRuns;
    private boolean inFlight;
    private long inFlightSince;
    private long request;
    private boolean dirty = true;
    private final List<ActivityEvent> all = new ArrayList<>();
    private final Map<String, Integer> txIndex = new HashMap<>();
    private final Set<Long> late = new HashSet<>();
    private Instant maxOccurred;
    private long lastSeq;
    private ActivityView.Health health;
    private ExecutionRecord record;
    private Optional<ExecutionController.Snapshot> snap = Optional.empty();
    private com.nekkora.agent.exec.ExecutionState currentState;
    private String query = "";
    private boolean unavailable;
    private boolean readFailed;
    private long retryAt;
    private HistoryState historyState = HistoryState.LOADING;
    private int historyUnreadable;
    private ActivityView.Completeness completeness = ActivityView.completeness(null, List.of());

    ActivityPanel(Data data) {
        super(new BorderLayout());
        this.data = data;
        setOpaque(false);
        // ordem: o que a pessoa procura primeiro (o que aconteceu, em que etapa, com que resultado) fica à esquerda
        model = new NekkoraTableModel<>(List.of(
                Column.<ActivityEvent>number("#", e -> e.sequence()).width(52),
                Column.<ActivityEvent>mono("Hora", this::time).width(112),
                Column.<ActivityEvent>status("Nível", e -> status(e.severity())).width(110),
                Column.<ActivityEvent>text("Evento", e -> ActivityText.event(e.code())).width(190),
                Column.<ActivityEvent>mono("Etapa", e -> e.stepId() == null ? "" : e.stepId()).width(220),
                Column.<ActivityEvent>text("Ação", e -> e.action() == null ? "" : ActivityText.action(e.action())).width(120),
                Column.<ActivityEvent>mono("Resultado", ActivityPanel::result).width(170),
                Column.<ActivityEvent>text("Tent.", e -> e.attempt() == null ? "" : String.valueOf(e.attempt())).width(48),
                Column.<ActivityEvent>text("Duração", e -> ActivityText.duration(e.elapsedMs())).width(76),
                Column.<ActivityEvent>text("Fase", e -> ActivityText.phase(e.phase())).width(96),
                Column.<ActivityEvent>mono("Transação", e -> tx(e.transactionRef())).width(92),
                Column.<ActivityEvent>text("Origem", e -> ActivityText.source(e.source())).width(100)));
        table = new NekkoraTable(model);
        table.setAutoResizeMode(javax.swing.JTable.AUTO_RESIZE_OFF);
        table.setEmptyState(IconName.INFO, "Sem eventos", "Nenhum evento corresponde à seleção.");
        table.getAccessibleContext().setAccessibleName("Linha do tempo da execução");
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) renderDetail();
        });
        tableScroll = table.scrollable();
        tableScroll.setPreferredSize(new Dimension(Scale.px(700), Scale.px(250)));
        tableScroll.setMinimumSize(new Dimension(Scale.px(300), Scale.px(160)));

        runs.setLabelProvider(r -> r == null ? "—" : runLabel(r));
        runs.getAccessibleContext().setAccessibleName("Execução acompanhada");
        runs.addActionListener(e -> {
            if (updatingRuns) return;
            ExecutionRecord r = runs.getSelectedValue();
            if (r == null) return;
            follow = r.id().equals(current);
            select(r.id());
        });
        reload.addActionListener(e -> reloadHistory());
        reload.setMnemonic(KeyEvent.VK_L);
        for (NekkoraComboBox<String> c : List.of(level, source, phase, order)) c.addActionListener(e -> refilter());
        level.getAccessibleContext().setAccessibleName("Filtrar por nível");
        source.getAccessibleContext().setAccessibleName("Filtrar por origem");
        phase.getAccessibleContext().setAccessibleName("Filtrar por fase");
        order.getAccessibleContext().setAccessibleName("Ordem da linha do tempo");
        search.setDebounceDelay(250);
        search.setColumns(26);
        search.onSearch(q -> {
            query = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
            refilter();
        });
        search.getAccessibleContext().setAccessibleName("Buscar nos metadados dos eventos");
        export.setMnemonic(KeyEvent.VK_O);
        export.addActionListener(e -> {
            if (selected != null && !all.isEmpty()) data.export(selected, List.copyOf(all), completeness);
        });

        NekkoraStack top = NekkoraStack.horizontal(Spacing.SPACE_4).align(NekkoraStack.Align.CENTER);
        top.addAll(runs, runsEmpty, reload);
        // dois andares: os filtros cabem na largura do diálogo em todas as escalas
        NekkoraStack filterRow = NekkoraStack.horizontal(Spacing.SPACE_3).align(NekkoraStack.Align.CENTER);
        filterRow.addAll(level, source, phase);
        NekkoraStack searchRow = NekkoraStack.horizontal(Spacing.SPACE_3).align(NekkoraStack.Align.CENTER);
        searchRow.addAll(order, search);
        NekkoraStack filters = NekkoraStack.vertical(Spacing.SPACE_3).align(NekkoraStack.Align.STRETCH);
        filters.addAll(filterRow, searchRow);
        NekkoraStack foot = NekkoraStack.horizontal(Spacing.SPACE_4).align(NekkoraStack.Align.CENTER);
        foot.addAll(footer);
        foot.addGlue();
        foot.add(export);
        NekkoraStack tl = NekkoraStack.vertical(Spacing.SPACE_4).align(NekkoraStack.Align.STRETCH);
        tl.addAll(filters, tableScroll, foot);
        timeline.setContent(tl);
        NekkoraCard detailCard = new NekkoraCard("Evento selecionado", null);
        detailCard.setContent(detail);

        NekkoraStack body = NekkoraStack.vertical(Spacing.SPACE_5).align(NekkoraStack.Align.STRETCH);
        body.setPadding(Spacing.SPACE_2);
        body.addAll(top, summaryCard, alerts, timeline, detailCard);
        NekkoraScrollPane sp = NekkoraScrollPane.verticalOnly(body);
        sp.setBorder(null);
        add(sp, BorderLayout.CENTER);

        timer = new Timer(REFRESH_MS, e -> tick());
        timer.setCoalesce(true);
        renderRuns();
        renderSummary();
        renderDetail();
    }

    /** Começa a acompanhar (abrir a janela). */
    void start() {
        reloadHistory();
        timer.start();
    }

    /** Para o temporizador (janela fechada). A coleta continua no serviço. */
    void stop() {
        timer.stop();
    }

    /** O supervisor mudou (chamado pelo diálogo a cada notícia, na EDT). */
    void snapshot(Optional<ExecutionController.Snapshot> s) {
        snap = s;
        ExecutionId id = s.map(x -> x.record().id()).orElse(null);
        if (id != null && !id.equals(current)) {
            current = id;
            currentState = s.get().record().state();
            reloadHistory();
            if (follow || selected == null) select(id);
        } else if (id != null && s.get().record().state() != currentState) {
            currentState = s.get().record().state();
            reloadHistory(); // o rótulo da execução na lista acompanha o estado do supervisor
        }
        if (id != null && id.equals(selected)) {
            record = s.get().record();
            renderSummary();
        }
    }

    // ------------------------------------------------------------------------------------------- dados

    private void reloadHistory() {
        data.history(listing -> {
            List<ExecutionRecord> list = listing.records();
            // falha com a lista já mostrada: mantém o que estava (só avisa); nunca troca por "nenhuma execução"
            boolean keep = listing.failed() && list.isEmpty() && runs.getItemCount() > 0;
            historyUnreadable = listing.unreadable();
            historyState = listing.failed() ? HistoryState.FAILED : list.isEmpty() ? HistoryState.EMPTY : HistoryState.LOADED;
            if (!keep) {
                updatingRuns = true;
                try {
                    runs.removeAllItems();
                    ExecutionRecord pick = null;
                    for (ExecutionRecord r : list) {
                        runs.addItem(r);
                        if (r.id().equals(selected)) pick = r;
                    }
                    if (pick != null) runs.setSelectedItem(pick);
                } finally {
                    updatingRuns = false;
                }
            }
            renderRuns();
            if (selected == null && !list.isEmpty()) select(list.get(0).id());
            else renderSummary();
        });
    }

    /** A lista de execuções nunca fica em branco: carregando, nenhuma execução ou falha de leitura são ditos. */
    private void renderRuns() {
        boolean empty = runs.getItemCount() == 0;
        String placeholder = switch (historyState) {
            case LOADING -> "Carregando histórico…";
            case EMPTY, LOADED -> "Nenhuma execução registrada";
            case FAILED -> "Histórico indisponível";
        };
        runs.setVisible(!empty);
        runsEmpty.setVisible(empty);
        runsEmpty.setText(placeholder);
        runsEmpty.getAccessibleContext().setAccessibleName("Execução acompanhada: " + placeholder);
        runs.getAccessibleContext().setAccessibleDescription(empty ? placeholder : runs.getItemCount() + " execução(ões) no histórico");
        runs.repaint();
    }

    private void select(ExecutionId id) {
        if (id.equals(selected)) return;
        selected = id;
        all.clear();
        txIndex.clear();
        late.clear();
        maxOccurred = null;
        lastSeq = 0;
        health = null;
        record = null;
        unavailable = false;
        readFailed = false;
        completeness = ActivityView.completeness(null, List.of());
        model.setRows(List.of());
        dirty = true;
        for (int i = 0; i < runs.getItemCount(); i++) {
            if (runs.getItemAt(i).id().equals(id)) {
                updatingRuns = true;
                runs.setSelectedIndex(i);
                updatingRuns = false;
            }
        }
        renderSummary();
        renderDetail();
        tick();
    }

    /** Marca que há eventos novos (o serviço avisou); o próximo ciclo busca. Pode vir de qualquer linha. */
    void changed(ExecutionId id) {
        if (id.equals(selected)) dirty = true;
    }

    private void tick() {
        if (selected != null && inFlight && System.nanoTime() - inFlightSince > LOST_REPLY_MS * 1_000_000L) {
            inFlight = false; // resposta perdida: a resposta atrasada, se vier, é descartada pelo número do pedido
            readFailed = true;
            retryAt = System.nanoTime();
            renderSummary();
        }
        if (selected == null || inFlight) return;
        if (readFailed && System.nanoTime() - retryAt < 0) return;
        boolean live = health == null || health.live();
        if (!dirty && !live && !readFailed) return;
        dirty = false;
        inFlight = true;
        inFlightSince = System.nanoTime();
        long mine = ++request;
        ExecutionId asked = selected;
        data.view(asked, lastSeq, v -> {
            if (mine != request) return; // resposta de um pedido já dado como perdido
            inFlight = false;
            if (!asked.equals(selected)) return; // a pessoa trocou de execução enquanto a leitura acontecia
            if (v.failed()) { // falha de leitura: avisa e tenta de novo; o que já está na tela continua
                readFailed = true;
                retryAt = System.nanoTime() + RETRY_MS * 1_000_000L;
                renderSummary();
                return;
            }
            readFailed = false;
            if (v.view().isEmpty()) {
                unavailable = true;
                renderSummary();
                return;
            }
            apply(v.view().get());
        });
    }

    private void apply(ActivityView v) {
        boolean newer = false;
        ExecutionId id = v.record().id();
        if (snap.isEmpty() || !snap.get().record().id().equals(id) || !snap.get().running()) record = v.record();
        health = v.health();
        List<ActivityEvent> fresh = new ArrayList<>();
        for (ActivityEvent e : v.events()) {
            if (e.sequence() <= lastSeq) continue; // nunca duas vezes
            lastSeq = e.sequence();
            all.add(e);
            fresh.add(e);
            newer = true;
            if (e.transactionRef() != null) txIndex.putIfAbsent(e.transactionRef(), txIndex.size() + 1);
            if (e.occurred() != null) {
                if (maxOccurred != null && e.occurred().isBefore(maxOccurred.minus(Duration.ofSeconds(1)))) late.add(e.sequence());
                if (maxOccurred == null || e.occurred().isAfter(maxOccurred)) maxOccurred = e.occurred();
            }
        }
        completeness = ActivityView.completeness(health, all);
        if (newer) appendRows(fresh);
        renderSummary();
    }

    // ------------------------------------------------------------------------------------------- tabela

    private boolean matches(ActivityEvent e) {
        String lv = level.getSelectedValue();
        if (WARN_UP.equals(lv) && e.severity() == ActivityEvent.Level.INFO) return false;
        if (ERRORS.equals(lv) && e.severity() != ActivityEvent.Level.ERROR) return false;
        String src = source.getSelectedValue();
        if (src != null && !ALL_SOURCES.equals(src) && !ActivityText.source(e.source()).equals(src)) return false;
        String ph = phase.getSelectedValue();
        if (ph != null && !ALL_PHASES.equals(ph) && !ph.equals(e.phase())) return false;
        if (query.isEmpty()) return true;
        // só metadados permitidos (nunca há valores no envelope)
        for (String f : new String[] {e.code(), ActivityText.event(e.code()), e.stepId(), e.studioStepId(), e.action(), ActivityText.action(e.action()),
                e.status(), e.errorCode(), e.transactionRef(), tx(e.transactionRef()), e.phase()}) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(query)) return true;
        }
        return false;
    }

    private void appendRows(List<ActivityEvent> fresh) {
        if (BY_OCCURRENCE.equals(order.getSelectedValue())) {
            refilter();
            return;
        }
        boolean atBottom = atBottom();
        for (ActivityEvent e : fresh) if (matches(e)) model.addRow(e);
        updateFooter();
        if (atBottom) scrollToEnd();
    }

    private void refilter() {
        List<ActivityEvent> rows = new ArrayList<>();
        for (ActivityEvent e : all) if (matches(e)) rows.add(e);
        if (BY_OCCURRENCE.equals(order.getSelectedValue())) {
            rows.sort(Comparator.comparing((ActivityEvent e) -> e.occurred() != null ? e.occurred() : e.observed()).thenComparingLong(ActivityEvent::sequence));
        }
        boolean atBottom = atBottom();
        model.setRows(rows);
        updateFooter();
        if (atBottom) scrollToEnd();
    }

    private boolean atBottom() {
        JScrollBar bar = tableScroll.getVerticalScrollBar();
        return bar == null || bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4;
    }

    private void scrollToEnd() {
        int n = table.getRowCount();
        if (n > 0) table.scrollRectToVisible(table.getCellRect(n - 1, 0, true));
    }

    private void updateFooter() {
        String suffix = switch (completeness.integrity()) {
            case COMPLETE -> " · linha do tempo completa";
            case IN_PROGRESS -> " · em andamento";
            case PENDING -> health == null ? "" : " · coleta não encerrada";
            case INCOMPLETE -> " · linha do tempo INCOMPLETA";
        };
        footer.setText("Mostrando " + model.getRowCount() + " de " + all.size() + " eventos guardados" + suffix);
        timeline.setTitle(completeness.integrity() == ActivityView.Integrity.INCOMPLETE ? "Linha do tempo (incompleta)" : "Linha do tempo", null);
        export.setEnabled(!all.isEmpty());
    }

    // ------------------------------------------------------------------------------------------- resumo e detalhe

    private void renderSummary() {
        NekkoraStack s = NekkoraStack.vertical(Spacing.SPACE_4).align(NekkoraStack.Align.STRETCH);
        alerts.removeAll();
        historyAlerts();
        if (selected == null) table.setEmptyState(IconName.INFO, "Nenhuma execução para mostrar", "A linha do tempo aparece quando houver uma execução no histórico.");
        else if (all.isEmpty()) table.setEmptyState(IconName.INFO, "Sem eventos ainda", "Os eventos aparecem aqui assim que forem coletados.");
        else table.setEmptyState(IconName.INFO, "Sem eventos", "Nenhum evento corresponde à seleção.");
        if (selected == null) {
            s.add(new NekkoraParagraph(switch (historyState) {
                case LOADING -> "Carregando o histórico de execuções…";
                case EMPTY, LOADED -> "Nenhuma execução registrada nesta máquina. Elas aparecem aqui assim que forem iniciadas na aba \"Execução\"; "
                        + "o histórico continua disponível depois de reiniciar o Agent.";
                case FAILED -> "Nenhuma execução pode ser mostrada enquanto o histórico não for lido (ver o aviso abaixo).";
            }, TextTone.SECONDARY));
            summaryCard.setContent(s);
            updateFooter();
            revalidate();
            repaint();
            return;
        }
        if (unavailable) alerts.add(new NekkoraInlineAlert(Severity.WARNING, "Registro indisponível",
                "O registro desta execução não pôde ser lido (removido pela retenção ou ilegível)."));
        if (readFailed) alerts.add(new NekkoraInlineAlert(Severity.WARNING, "Falha ao ler a atividade",
                "A atividade desta execução não pôde ser lida agora; nova tentativa em " + RETRY_MS / 1000 + " s. Os eventos mostrados podem estar desatualizados."));
        // primeiro aviso da execução: uma linha do tempo parcial nunca parece completa
        if (completeness.integrity() == ActivityView.Integrity.INCOMPLETE) {
            StringBuilder why = new StringBuilder("Faltam eventos nesta linha do tempo: ");
            for (int i = 0; i < completeness.gaps().size(); i++) why.append(i == 0 ? "" : "; ").append(ActivityText.gap(completeness.gaps().get(i)));
            alerts.add(new NekkoraInlineAlert(Severity.WARNING, "Linha do tempo incompleta", why.append('.').toString()));
        }
        ExecutionRecord r = record;
        NekkoraKeyValueView kv = new NekkoraKeyValueView();
        if (r != null) {
            NekkoraStatusBadge b = new NekkoraStatusBadge(ExecutionDialog.status(r.state()), r.state().label());
            b.getAccessibleContext().setAccessibleName("Estado: " + r.state().label());
            kv.add("Estado (supervisor)", b);
            kv.addMono("Execução local", r.id().value());
            Instant start = r.started() != null ? r.started() : r.created();
            kv.add("Início", WHEN.format(start));
            kv.add("Tempo decorrido", ExecutionSetup.duration(elapsed(r)));
            // sucesso com código (ex.: adotada depois de reinício) é uma observação, não o motivo de uma falha
            if (r.code() != null) kv.add(r.state() == com.nekkora.agent.exec.ExecutionState.SUCCEEDED ? "Observação" : "Motivo técnico",
                    r.code().description() + " (" + r.code().code() + ")");
        }
        if (health != null && health.frameworkExecutionId() != null) kv.addMono("Execução do Framework (correlação)", health.frameworkExecutionId());
        ActivityView.Progress p = ActivityView.progress(all);
        boolean steps = health == null || health.frameworkExpected();
        boolean running = r != null && !r.state().terminal();
        if (steps) {
            // conjunto na ordem canônica do Framework (a ordem real está na linha do tempo)
            List<String> seen = PHASE_ORDER.stream().filter(p.phasesSeen()::contains).map(ActivityText::phase).toList();
            kv.add("Fases observadas", seen.isEmpty() ? "—" : String.join(" → ", seen));
            if (p.phase() != null) kv.add(running ? "Fase atual" : "Última fase", ActivityText.phase(p.phase()));
            if (p.currentStep() != null) {
                ActivityEvent st = p.currentStep();
                kv.addMono(running ? "Etapa atual" : "Última etapa", st.stepId() + (st.action() == null ? "" : " · " + ActivityText.action(st.action())));
            }
            kv.add("Transações", p.transactionsStarted() + " iniciadas · " + p.transactionsSucceeded() + " concluídas · " + p.transactionsFailed()
                    + " com falha · " + p.transactionsRejected() + " rejeitadas · " + p.transactionsSkipped() + " puladas");
        }
        if (p.lastFailure() != null) {
            ActivityEvent f = p.lastFailure();
            Optional<ActivityEvent> where = ActivityView.stepBefore(all, f);
            StringBuilder b = new StringBuilder(ActivityText.category(f.category())).append(": ").append(ActivityText.event(f.code()));
            where.ifPresent(w -> b.append(" · etapa ").append(w.stepId()));
            if (f.errorCode() != null) b.append(" · ").append(f.errorCode());
            if (f.status() != null) b.append(" · resultado ").append(f.status());
            if (f.attempt() != null) b.append(" · tentativa ").append(f.attempt());
            if (f.elapsedMs() != null) b.append(" · ").append(ActivityText.duration(f.elapsedMs()));
            kv.add("Última falha observada", b.toString());
        }
        kv.add("Coleta", collectionLabel());
        if (health != null) kv.add("Linha do tempo", ActivityText.integrity(completeness, all.size()));
        s.add(kv);
        summaryCard.setContent(s);
        if (health != null) healthAlerts(health, running);
        else if (r != null && !steps) stepsUnavailable();
        updateFooter();
        revalidate();
        repaint();
    }

    private String collectionLabel() {
        if (health == null) return "Carregando…";
        if (health.purged()) return "Removida pela retenção do histórico";
        if (health.completed() && health.pendingWrites() > 0) {
            return "Encerrada (" + all.size() + " eventos; " + health.pendingWrites() + " aguardando gravação no disco)";
        }
        if (health.completed()) return "Encerrada (" + all.size() + " eventos guardados)";
        return health.live() ? "Em tempo quase real (atualiza a cada " + REFRESH_MS + " ms)" : "Pendente (retomada na próxima abertura do Agent)";
    }

    private void healthAlerts(ActivityView.Health h, boolean running) {
        if (!h.frameworkExpected()) stepsUnavailable();
        switch (h.frameworkState()) {
            case WAITING -> {
                if (running) alerts.add(new NekkoraInlineAlert(Severity.INFO, "Aguardando eventos do Framework", "As etapas aparecem assim que o pacote as publicar."));
            }
            case UNAVAILABLE -> alerts.add(new NekkoraInlineAlert(Severity.WARNING, "Etapas indisponíveis", ActivityText.error("AGT-OBS-001")
                    + " O estado mostrado continua sendo o do supervisor."));
            case UNSAFE -> alerts.add(new NekkoraInlineAlert(Severity.WARNING, "Arquivo de eventos recusado", ActivityText.error("AGT-OBS-003")));
            case REPLACED -> alerts.add(new NekkoraInlineAlert(Severity.WARNING, "Arquivo de eventos trocado ou truncado", ActivityText.error("AGT-OBS-004")));
            default -> {
                // lendo normalmente ou não se aplica
            }
        }
        if (h.capped() || h.dropped() > 0) alerts.add(new NekkoraInlineAlert(Severity.WARNING, "Histórico limitado",
                h.dropped() + " evento(s) não guardado(s) (limite de 20.000 por execução). Este intervalo não poderá ser entregue a outro sistema."));
        if (h.invalid() > 0) alerts.add(new NekkoraInlineAlert(Severity.WARNING, "Eventos recusados",
                h.invalid() + " evento(s) inválido(s), incompleto(s) ou fora do contrato foram recusados e não aparecem na linha do tempo."));
        if (h.sequenceGap() || h.unreadableLines() > 0) alerts.add(new NekkoraInlineAlert(Severity.WARNING, "Lacuna no histórico",
                ActivityText.error("AGT-OBS-013") + (h.unreadableLines() > 0 ? " Linhas ilegíveis: " + h.unreadableLines() + "." : "")));
        if (h.storeFailed()) alerts.add(new NekkoraInlineAlert(Severity.WARNING, "Histórico não gravado", ActivityText.error("AGT-OBS-009")
                + " A parte não gravada aparece só nesta sessão e falta no histórico."));
        if (h.pendingWrites() > 0) alerts.add(new NekkoraInlineAlert(Severity.WARNING, "Gravação do histórico pendente",
                h.pendingWrites() + " evento(s) ainda não gravado(s): o arquivo do histórico está sem permissão de escrita no momento (outro programa"
                        + " pode estar com ele aberto). O Agent tenta de novo sozinho; a automação não é afetada. (AGT-OBS-009)"));
        if (h.purged()) alerts.add(new NekkoraInlineAlert(Severity.INFO, "Atividade removida", ActivityText.error("AGT-OBS-010")));
        if (h.unknownFields() + h.unknownCodes() > 0) alerts.add(new NekkoraInlineAlert(Severity.INFO, "Conteúdo desconhecido descartado",
                h.unknownFields() + " campo(s) e " + h.unknownCodes() + " código(s) de evento desconhecidos foram contados; nenhum valor foi guardado."));
        if (h.restarts() > 0) alerts.add(new NekkoraInlineAlert(Severity.INFO, "Coleta retomada",
                "A coleta foi retomada " + h.restarts() + " vez(es) depois de reinício do Agent, sem repetir eventos; contagens de recusas anteriores podem ser parciais."));
    }

    private void historyAlerts() {
        if (historyState == HistoryState.FAILED) alerts.add(new NekkoraInlineAlert(Severity.ERROR, "Não foi possível carregar o histórico",
                ActivityText.error("AGT-OBS-014") + (runs.getItemCount() > 0 ? " A lista mostrada pode estar incompleta." : "")
                        + " Use \"Atualizar lista\" para tentar de novo."));
        if (historyUnreadable > 0) alerts.add(new NekkoraInlineAlert(Severity.WARNING, "Registros de execução ilegíveis",
                historyUnreadable + " registro(s) de execução não puderam ser lidos e não aparecem na lista (AGT-OBS-014)."));
    }

    private void stepsUnavailable() {
        alerts.add(new NekkoraInlineAlert(Severity.INFO, "Etapas não disponíveis para este tipo de processo",
                "Este pacote não publica atividades estruturadas: só o ciclo de vida do supervisor é mostrado. Nada é deduzido da saída do processo."));
    }

    private long elapsed(ExecutionRecord r) {
        if (snap.isPresent() && snap.get().running() && snap.get().record().id().equals(r.id())) return snap.get().elapsedMillis();
        if (r.started() == null) return 0;
        Instant end = r.ended() != null ? r.ended() : Instant.now();
        return Duration.between(r.started(), end).toMillis();
    }

    private void renderDetail() {
        detail.removeAll();
        int row = table.getSelectedModelRow();
        if (row < 0 || row >= model.getRowCount()) {
            detail.add(new NekkoraParagraph("Selecione um evento da linha do tempo para ver todos os campos guardados.", TextTone.SECONDARY));
        } else {
            ActivityEvent e = model.getRow(row);
            NekkoraKeyValueView kv = new NekkoraKeyValueView();
            kv.addMono("Sequência", String.valueOf(e.sequence()));
            kv.add("Evento", ActivityText.event(e.code()));
            kv.addMono("Código do evento", e.code());
            kv.add("Origem · nível", ActivityText.source(e.source()) + " · " + ActivityText.level(e.severity()) + " · " + ActivityText.category(e.category()));
            if (e.occurred() != null) kv.addMono("Ocorrido (UTC)", e.occurred().toString() + (late.contains(e.sequence()) ? "  (chegou depois de eventos posteriores)" : ""));
            kv.addMono("Observado pelo Agent (UTC)", e.observed().toString());
            if (e.phase() != null) kv.addMono("Fase", e.phase());
            if (e.transactionRef() != null) kv.addMono("Transação", tx(e.transactionRef()) + " · " + e.transactionRef());
            if (e.stepId() != null) kv.addMono("Etapa (step_id)", e.stepId());
            if (e.studioStepId() != null) kv.addMono("Passo no Studio", e.studioStepId());
            if (e.action() != null) kv.add("Ação", ActivityText.action(e.action()) + " (" + e.action() + ")");
            if (e.attempt() != null) kv.addMono("Tentativa", String.valueOf(e.attempt()));
            if (e.elapsedMs() != null) kv.addMono("Duração", e.elapsedMs() + " ms");
            if (e.status() != null) kv.addMono(e.source() == ActivityEvent.Source.COLLECTOR ? "Origem afetada" : "Resultado",
                    e.source() == ActivityEvent.Source.COLLECTOR ? ActivityText.origin(e.status()) : e.status());
            if (e.errorCode() != null) kv.add("Código", ActivityText.error(e.errorCode()));
            if (!e.counts().isEmpty()) {
                StringBuilder c = new StringBuilder();
                e.counts().forEach((k, v) -> c.append(c.isEmpty() ? "" : " · ").append(k).append('=').append(v));
                kv.addMono("Contagens", c.toString());
            }
            if (e.frameworkExecutionId() != null) kv.addMono("Execução do Framework", e.frameworkExecutionId());
            if (e.sourcePosition() != null) kv.addMono("Posição na origem (byte)", String.valueOf(e.sourcePosition()));
            detail.add(kv);
        }
        detail.revalidate();
        detail.repaint();
    }

    // ------------------------------------------------------------------------------------------- formatação

    private String time(ActivityEvent e) {
        Instant t = e.occurred() != null ? e.occurred() : e.observed();
        return TIME.format(t) + (late.contains(e.sequence()) ? " ↺" : "");
    }

    private String tx(String ref) {
        if (ref == null) return "";
        Integer i = txIndex.get(ref);
        return i == null ? ref.substring(0, 8) : "T" + i;
    }

    private static String result(ActivityEvent e) {
        if (e.source() == ActivityEvent.Source.COLLECTOR) return e.errorCode() == null ? "" : e.errorCode();
        String s = e.status() == null ? "" : e.status();
        if (e.errorCode() != null) s = s.isEmpty() ? e.errorCode() : s + " · " + e.errorCode();
        return s;
    }

    private static Status status(ActivityEvent.Level l) {
        return switch (l) {
            case INFO -> Status.INFO;
            case WARNING -> Status.WARNING;
            case ERROR -> Status.FAILED;
        };
    }

    private static String runLabel(ExecutionRecord r) {
        Instant t = r.started() != null ? r.started() : r.created();
        return WHEN.format(t) + " · " + r.state().label() + " · " + r.id().value().substring(0, 11);
    }

    /** Rola a aba até a linha do tempo (atalho de navegação e capturas de evidência). */
    void revealTimeline() {
        tableScroll.scrollRectToVisible(new java.awt.Rectangle(0, 0, tableScroll.getWidth(), tableScroll.getHeight() + Scale.px(80)));
    }

    /** Volta ao topo (resumo). */
    void revealSummary() {
        summaryCard.scrollRectToVisible(new java.awt.Rectangle(0, 0, 1, 1));
    }

    // acesso para testes de desktop
    NekkoraTable table() {
        return table;
    }

    NekkoraTableModel<ActivityEvent> model() {
        return model;
    }

    List<ActivityEvent> events() {
        return List.copyOf(all);
    }

    ExecutionId selectedRun() {
        return selected;
    }

    ActivityView.Health health() {
        return health;
    }

    NekkoraText runsEmptyLabel() {
        return runsEmpty;
    }

    NekkoraComboBox<ExecutionRecord> runsBox() {
        return runs;
    }

    NekkoraButton reloadButton() {
        return reload;
    }

    NekkoraButton exportButton() {
        return export;
    }

    JPanel alertsPanel() {
        return alerts;
    }

    JPanel summaryPanel() {
        return summaryCard;
    }

    HistoryState historyState() {
        return historyState;
    }

    ActivityView.Completeness completeness() {
        return completeness;
    }

    NekkoraCaption footerLabel() {
        return footer;
    }

    NekkoraComboBox<String> levelBox() {
        return level;
    }
}
