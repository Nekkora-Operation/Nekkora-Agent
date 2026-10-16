package com.nekkora.agent.diagnostics;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.core.ErrorJournal;
import com.nekkora.agent.core.SafeText;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Logs técnicos locais com rotação e limite total explícito (decisão D-07).
 *
 * <ul>
 *   <li>Arquivos: {@code agent.log} e {@code agent.1.log} … {@code agent.3.log}; cada um com no
 *       máximo {@link #MAX_FILE_BYTES}; total máximo {@link #MAX_TOTAL_BYTES} (1 MiB).</li>
 *   <li>Cada linha tem horário UTC, nível, evento e campos {@code chave=valor} com valores filtrados
 *       por {@link SafeText}; linha limitada a {@link #MAX_LINE_CHARS}. Não há texto livre: caminhos,
 *       endereços, nomes e mensagens de exceção não têm como entrar (de exceção, só o nome da classe).</li>
 *   <li>A gravação acontece em uma única thread própria (nunca na EDT), com fila limitada; excesso é
 *       descartado e contado.</li>
 *   <li>Falha ao abrir, gravar ou rotacionar: o registro em arquivo é suspenso nesta sessão, o código
 *       vai para o diagnóstico e não há nova tentativa (sem laço nem crescimento).</li>
 * </ul>
 */
public final class AgentLog implements AutoCloseable {

    public static final long MAX_FILE_BYTES = 256 * 1024;
    public static final int MAX_FILES = 4;
    public static final long MAX_TOTAL_BYTES = MAX_FILE_BYTES * MAX_FILES;
    public static final int MAX_LINE_CHARS = 1024;
    static final int QUEUE_CAPACITY = 512;
    static final String CURRENT = "agent.log";
    private static final Pattern OURS = Pattern.compile("agent(\\.[0-9]+)?\\.log");

    /** Situação do registro em arquivo. */
    public enum State {
        ACTIVE("ATIVOS"),
        UNAVAILABLE("INDISPONIVEIS"),
        DISABLED("DESATIVADOS");

        private final String diagnosticValue;

        State(String diagnosticValue) {
            this.diagnosticValue = diagnosticValue;
        }

        public String diagnosticValue() {
            return diagnosticValue;
        }
    }

    /** Par chave/valor de uma linha. A chave é um identificador fixo do código. */
    public record Field(String key, String value) {
        public Field {
            if (!key.matches("[a-z][a-z0-9_]{0,31}")) throw new IllegalArgumentException("chave inválida");
            value = SafeText.token(value, 64);
        }

        public static Field of(String key, Object value) {
            return new Field(key, String.valueOf(value));
        }

        public static Field code(ErrorCode code) {
            return new Field("code", code.code());
        }

        /** Somente o nome simples da classe: a mensagem da exceção pode conter caminhos ou dados. */
        public static Field exception(Throwable t) {
            return new Field("exception", t == null ? "none" : t.getClass().getSimpleName());
        }
    }

    private final Path dir;
    private final Clock clock;
    private final ErrorJournal journal;
    private final ThreadPoolExecutor writer;
    private final AtomicLong dropped = new AtomicLong();
    private volatile State state;
    private OutputStream out;
    private long size;

    private AgentLog(Path dir, Clock clock, ErrorJournal journal, State initial) {
        this.dir = dir;
        this.clock = Objects.requireNonNull(clock);
        this.journal = Objects.requireNonNull(journal);
        this.state = initial;
        if (initial == State.ACTIVE) {
            this.writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), r -> {
                Thread t = new Thread(r, "nekkora-agent-log");
                t.setDaemon(true);
                return t;
            }, (r, ex) -> dropped.incrementAndGet());
        } else {
            this.writer = null;
        }
    }

    /** Abre (ou cria) os logs em {@code dir}. Nunca lança: em falha devolve um log indisponível. */
    public static AgentLog open(Path dir, Clock clock, ErrorJournal journal) {
        AgentLog log = new AgentLog(dir, clock, journal, State.ACTIVE);
        log.writer.execute(log::openCurrent);
        return log;
    }

    /** Sem arquivo (por exemplo, a segunda instância, que não toca nos logs da primeira). */
    public static AgentLog disabled(Clock clock, ErrorJournal journal) {
        return new AgentLog(null, clock, journal, State.DISABLED);
    }

    public State state() {
        return state;
    }

    public void info(LogEvent event, Field... fields) {
        submit("INFO", event, fields);
    }

    public void warn(LogEvent event, Field... fields) {
        submit("WARN", event, fields);
    }

    /** Registra o evento e o código no diário de erros (que alimenta o diagnóstico). */
    public void error(LogEvent event, ErrorCode code, Field... fields) {
        journal.record(code);
        submit("ERROR", event, withCode(code, fields));
    }

    /** Condição degradada esperável (bandeja ausente, nome da máquina ausente): nível WARN, código no diário. */
    public void warn(LogEvent event, ErrorCode code, Field... fields) {
        journal.record(code);
        submit("WARN", event, withCode(code, fields));
    }

    private static Field[] withCode(ErrorCode code, Field... fields) {
        Field[] all = new Field[fields.length + 1];
        all[0] = Field.code(code);
        System.arraycopy(fields, 0, all, 1, fields.length);
        return all;
    }

    private void submit(String level, LogEvent event, Field... fields) {
        if (state != State.ACTIVE) return;
        StringBuilder b = new StringBuilder(128)
                .append(clock.instant()).append(' ').append(level).append(' ').append(event.name());
        for (Field f : fields) b.append(' ').append(f.key()).append('=').append(f.value());
        String line = b.length() > MAX_LINE_CHARS ? b.substring(0, MAX_LINE_CHARS) : b.toString();
        writer.execute(() -> append(line));
    }

    // ----- thread do log -----

    private void openCurrent() {
        try {
            Files.createDirectories(dir);
            removeForeignRotations();
            Path cur = dir.resolve(CURRENT);
            size = Files.exists(cur) ? Files.size(cur) : 0;
            out = Files.newOutputStream(cur, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | SecurityException e) {
            fail(ErrorCode.LOG_UNAVAILABLE);
        }
    }

    /** Remove arquivos de rotação além do limite (por exemplo, deixados por outra configuração). */
    private void removeForeignRotations() throws IOException {
        try (var files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String n = p.getFileName().toString();
                if (!OURS.matcher(n).matches() || n.equals(CURRENT)) continue;
                int idx = Integer.parseInt(n.substring(6, n.length() - 4));
                if (idx >= MAX_FILES || idx < 1) Files.deleteIfExists(p);
            }
        } catch (NumberFormatException e) {
            // nome fora do padrão numérico: ignorado
        }
    }

    private void append(String line) {
        if (state != State.ACTIVE || out == null) return;
        long lost = dropped.getAndSet(0);
        if (lost > 0) {
            journal.record(ErrorCode.LOG_EVENTS_DROPPED);
            write(clock.instant() + " WARN " + LogEvent.LOG_EVENTS_DROPPED.name() + " count=" + lost);
        }
        write(line);
    }

    private void write(String line) {
        if (state != State.ACTIVE) return;
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            if (size + bytes.length > MAX_FILE_BYTES && !rotate()) return;
            out.write(bytes);
            out.flush();
            size += bytes.length;
        } catch (IOException e) {
            fail(ErrorCode.LOG_UNAVAILABLE);
        }
    }

    private boolean rotate() {
        try {
            out.close();
            out = null;
            Files.deleteIfExists(dir.resolve(name(MAX_FILES - 1)));
            for (int i = MAX_FILES - 2; i >= 0; i--) {
                Path from = dir.resolve(name(i));
                if (Files.exists(from)) Files.move(from, dir.resolve(name(i + 1)), StandardCopyOption.REPLACE_EXISTING);
            }
            out = Files.newOutputStream(dir.resolve(CURRENT), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            size = 0;
            return true;
        } catch (IOException | SecurityException e) {
            fail(ErrorCode.LOG_ROTATION_FAILED);
            return false;
        }
    }

    static String name(int index) {
        return index == 0 ? CURRENT : "agent." + index + ".log";
    }

    private void fail(ErrorCode code) {
        state = State.UNAVAILABLE;
        journal.record(code);
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // já em falha
            }
            out = null;
        }
    }

    /** Espera a fila esvaziar (testes e encerramento). */
    public void flush(long timeoutMillis) {
        if (writer == null || writer.isShutdown()) return;
        try {
            writer.submit(() -> {}).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // fila cheia ou encerrada: segue
        }
    }

    @Override
    public void close() {
        if (writer == null) return;
        flush(2000);
        writer.execute(() -> {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // encerrando
                }
                out = null;
            }
        });
        writer.shutdown();
        try {
            writer.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
