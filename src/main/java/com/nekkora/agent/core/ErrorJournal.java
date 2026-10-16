package com.nekkora.agent.core;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Códigos de erro ocorridos nesta sessão, para o diagnóstico. Limitado pelo próprio catálogo:
 * uma entrada por código, com contagem e horário da última ocorrência.
 */
public final class ErrorJournal {

    /** Ocorrências de um código. */
    public record Entry(ErrorCode code, long count, Instant last) {}

    private final Clock clock;
    private final Map<ErrorCode, Entry> entries = new EnumMap<>(ErrorCode.class);

    public ErrorJournal(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void record(ErrorCode code) {
        Objects.requireNonNull(code);
        Entry old = entries.get(code);
        entries.put(code, new Entry(code, old == null ? 1 : old.count() + 1, clock.instant()));
    }

    /** Fotografia na ordem do catálogo. */
    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    public synchronized boolean contains(ErrorCode code) {
        return entries.containsKey(code);
    }
}
