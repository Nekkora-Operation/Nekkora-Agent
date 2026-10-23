package com.nekkora.agent.obs;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionController;
import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.obs.ActivityEvent.Category;
import com.nekkora.agent.obs.ActivityEvent.Level;
import com.nekkora.agent.obs.ActivityEvent.Source;
import com.nekkora.agent.obs.ActivityView.FrameworkState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Observabilidade local (OBS-001): UMA linha de fundo, de prioridade reduzida, que a cada {@link #DEFAULT_PERIOD} lê
 * o que há de novo nas execuções abertas (no máximo {@link #BUDGET} bytes por origem por rodada) e avisa a interface
 * — que só busca os eventos novos (por sequência) e redesenha no seu próprio ritmo. Não depende do Framework, não
 * altera o {@link ExecutionController} e não toca no processo: qualquer erro aqui é de telemetria.
 *
 * <p>Na abertura do Agent, toda execução cuja coleta não terminou (inclusive as anteriores à OBS-001, sem
 * {@code atividade.jsonl}) é retomada a partir das posições gravadas. Retenção: o limite por execução do
 * {@link ActivityLog} e uma cota TOTAL de {@link #MAX_TOTAL_BYTES} — acima dela, a atividade detalhada das execuções
 * terminadas mais antigas é substituída por um único {@code HISTORY_PURGED} (o registro do supervisor fica).
 */
public final class ActivityService implements AutoCloseable {

    /** Algo novo foi gravado na atividade da execução (chamado na linha de fundo: a interface reagenda para si). */
    public interface Listener {
        void appended(ExecutionId id);
    }

    public static final Duration DEFAULT_PERIOD = Duration.ofMillis(500);
    public static final long MAX_TOTAL_BYTES = 96L * 1024 * 1024;
    static final int BUDGET = 256 * 1024;

    private final RunStore store;
    private final Clock clock;
    private final Duration period;
    private final long maxTotal;
    private final Map<ExecutionId, ActivityCollector> open = new ConcurrentHashMap<>();
    private final Map<ExecutionId, ActivityView.Health> health = new ConcurrentHashMap<>();
    private final Set<ExecutionId> pending = ConcurrentHashMap.newKeySet();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService exec;

    public ActivityService(RunStore store, Clock clock) {
        this(store, clock, DEFAULT_PERIOD, MAX_TOTAL_BYTES);
    }

    ActivityService(RunStore store, Clock clock, Duration period, long maxTotal) {
        this.store = store;
        this.clock = clock;
        this.period = period;
        this.maxTotal = maxTotal;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    /** Ouvinte para o supervisor: cada execução que ele noticia passa a ser coletada (barato; nunca bloqueia). */
    public ExecutionController.Listener controllerListener() {
        return snap -> track(snap.record().id());
    }

    /** Começa a coleta em segundo plano e a retomada das execuções com coleta pendente. */
    public synchronized void start() {
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nekkora-agent-atividade");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        exec.execute(this::catchUp);
        exec.scheduleWithFixedDelay(this::tick, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void track(ExecutionId id) {
        if (!open.containsKey(id)) pending.add(id);
    }

    /** Execuções com coleta pendente (registro presente e sem fechamento no fim do {@code atividade.jsonl}). */
    void catchUp() {
        try {
            for (ExecutionRecord r : store.all()) {
                if (!ActivityLog.closedOnDisk(store.dir(r.id()))) pending.add(r.id());
            }
        } catch (RuntimeException e) {
            // a coleta segue com o que for noticiado pelo supervisor
        }
    }

    /** Uma rodada (público no pacote para os testes conduzirem sem esperar o agendamento). */
    void tick() {
        try {
            for (ExecutionId id : List.copyOf(pending)) {
                pending.remove(id);
                if (open.containsKey(id)) continue;
                ActivityCollector.open(store, id, clock).ifPresent(c -> {
                    health.put(id, c.health(!c.completed()));
                    if (!c.completed()) open.put(id, c);
                });
            }
            boolean closed = false;
            for (Map.Entry<ExecutionId, ActivityCollector> e : List.copyOf(open.entrySet())) {
                ActivityCollector c = e.getValue();
                boolean changed;
                try {
                    changed = c.poll(BUDGET);
                } catch (RuntimeException ex) {
                    changed = false; // falha do leitor: nunca derruba a coleta das demais nem a automação
                }
                health.put(e.getKey(), c.health(!c.completed()));
                if (c.completed()) {
                    open.remove(e.getKey());
                    closed = true;
                }
                if (changed) notifyListeners(e.getKey());
            }
            if (closed) enforceQuota();
        } catch (RuntimeException e) {
            // a linha de fundo continua viva
        }
    }

    private void notifyListeners(ExecutionId id) {
        for (Listener l : listeners) {
            try {
                l.appended(id);
            } catch (RuntimeException e) {
                // a interface não derruba a coleta
            }
        }
    }

    /** Execuções conhecidas (do registro do supervisor), da mais nova para a mais antiga. Fora da EDT. */
    public RunStore.Listing history() {
        return store.list();
    }

    /**
     * Eventos de atividade depois da sequência {@code after} (0 = todos) e a saúde da coleta. Execução aberta: da
     * memória da coleta; terminada: lida do disco (sem alterar nada). Fora da EDT.
     */
    public Optional<ActivityView> view(ExecutionId id, long after) {
        Optional<ExecutionRecord> rec = store.record(id);
        if (rec.isEmpty()) return Optional.empty();
        ActivityCollector c = open.get(id);
        if (c != null) {
            ActivityView.Health h = health.get(id);
            return Optional.of(new ActivityView(rec.get(), c.log().since(after), h != null ? h : c.health(true)));
        }
        ActivityLog l = ActivityLog.read(store.dir(id), id.value());
        boolean live = pending.contains(id) || !l.has("COLLECTION_COMPLETED") && !l.has("HISTORY_PURGED") && !rec.get().state().terminal();
        return Optional.of(new ActivityView(rec.get(), l.since(after), stored(rec.get(), l, live)));
    }

    /** Saúde reconstruída do que está gravado (o resumo final traz as contagens da coleta). */
    static ActivityView.Health stored(ExecutionRecord r, ActivityLog l, boolean live) {
        List<ActivityEvent> all = l.all();
        Map<String, Long> summary = Map.of();
        FrameworkState fs = r.kind() == ExecutionPackage.Kind.STUDIO_PYTHON ? FrameworkState.WAITING : FrameworkState.NOT_APPLICABLE;
        String fw = null;
        boolean trusted = false;
        boolean frameworkSeen = false;
        for (ActivityEvent e : all) {
            if ("COLLECTION_COMPLETED".equals(e.code())) summary = e.counts();
            if (e.source() == Source.FRAMEWORK) {
                frameworkSeen = true;
                if (fw == null) fw = e.frameworkExecutionId();
                if (e.studioStepId() != null) trusted = true;
            }
            if (e.source() == Source.COLLECTOR && ActivityCollector.FRAMEWORK_ORIGIN.equals(e.status())) {
                switch (e.code()) {
                    case "SOURCE_UNSAFE" -> fs = FrameworkState.UNSAFE;
                    case "SOURCE_REPLACED" -> fs = FrameworkState.REPLACED;
                    case "SOURCE_UNAVAILABLE" -> fs = FrameworkState.UNAVAILABLE;
                    // voltou a ser legível: a leitura continua da mesma posição (nada perdido)
                    case "SOURCE_AVAILABLE" -> fs = FrameworkState.READING;
                    default -> {
                        // demais avisos não mudam o estado da origem
                    }
                }
            }
        }
        if (frameworkSeen && fs == FrameworkState.WAITING) fs = FrameworkState.READING;
        boolean completed = l.has("COLLECTION_COMPLETED");
        if (completed && fs == FrameworkState.WAITING) fs = r.started() == null ? FrameworkState.NOT_APPLICABLE : FrameworkState.UNAVAILABLE;
        return new ActivityView.Health(live, completed, r.kind() == ExecutionPackage.Kind.STUDIO_PYTHON, fs, Math.max(l.dropped(), summary.getOrDefault("dropped", 0L)),
                summary.getOrDefault("invalid", (long) l.count("EVENT_INVALID")), summary.getOrDefault("excluded_fields", 0L),
                summary.getOrDefault("unknown_fields", 0L), summary.getOrDefault("unknown_codes", 0L), l.capped(), l.sequenceGap(), l.unreadable(),
                l.has("HISTORY_PURGED"), l.storeFailed(), Math.toIntExact(Math.min(Integer.MAX_VALUE, summary.getOrDefault("restarts", 0L))), fw, trusted);
    }

    // ------------------------------------------------------------------------------------------- retenção

    /** Cota total: mantém a atividade das execuções mais novas; das mais antigas terminadas fica só o aviso. */
    void enforceQuota() {
        long total = 0;
        boolean newest = true; // a execução mais recente nunca perde o detalhe, mesmo sozinha acima da cota
        for (ExecutionRecord r : store.all()) {
            Path f = store.dir(r.id()).resolve(ActivityLog.FILE);
            long size;
            try {
                size = Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) ? Files.size(f) : 0;
            } catch (IOException e) {
                continue;
            }
            total += size;
            if (!newest && total > maxTotal && r.state().terminal() && !open.containsKey(r.id()) && size > 4096) purge(r.id());
            newest = false;
        }
    }

    private void purge(ExecutionId id) {
        Path dir = store.dir(id);
        Path f = dir.resolve(ActivityLog.FILE);
        try {
            long last = ActivityLog.lastSequence(dir);
            Instant now = clock.instant();
            ActivityEvent e = ActivityEvent.of(id.value(), now, Source.COLLECTOR, "HISTORY_PURGED", Level.INFO, Category.TELEMETRY)
                    .error(ErrorCode.OBS_HISTORY_PURGED.code()).count("purged_until_sequence", last).build().withSequence(last + 1);
            Path tmp = dir.resolve(ActivityLog.FILE + ".tmp");
            Files.writeString(tmp, ActivityCodec.encode(e) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ex) {
            // fica para a próxima verificação
        }
    }

    @Override
    public synchronized void close() {
        if (exec == null) return;
        exec.shutdownNow();
        try {
            exec.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
