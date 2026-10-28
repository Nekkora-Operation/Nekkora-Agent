package com.nekkora.agent.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * Espera entre tentativas depois de uma falha sem resposta ou de indisponibilidade: crescente ({@link #FIRST} dobrando
 * até {@link #MAX}), com aleatoriedade (entre metade e o total, para várias máquinas não voltarem juntas) e nunca antes
 * do {@code Retry-After} pedido pela Platform (limitado a {@link #MAX_RETRY_AFTER}). Um sucesso zera a contagem.
 */
final class Backoff {
    static final Duration FIRST = Duration.ofSeconds(2);
    static final Duration MAX = Duration.ofMinutes(5);
    static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

    private final DoubleSupplier random;
    private int failures;
    private Instant notBefore = Instant.MIN;

    Backoff(DoubleSupplier random) {
        this.random = random;
    }

    synchronized boolean ready(Instant now) {
        return !now.isBefore(notBefore);
    }

    synchronized Instant notBefore() {
        return notBefore;
    }

    synchronized int failures() {
        return failures;
    }

    /** Falha: próxima tentativa só depois da espera (e do {@code Retry-After}, se maior). Devolve a espera aplicada. */
    synchronized Duration failed(Instant now, Optional<Long> retryAfterSeconds) {
        failures = Math.min(failures + 1, 30);
        Duration base = FIRST.multipliedBy(1L << Math.min(failures - 1, 20));
        if (base.compareTo(MAX) > 0) base = MAX;
        long ms = base.toMillis();
        Duration wait = Duration.ofMillis(ms / 2 + (long) (random.getAsDouble() * (ms - ms / 2)));
        if (retryAfterSeconds.isPresent()) {
            Duration ra = Duration.ofSeconds(Math.min(retryAfterSeconds.get(), MAX_RETRY_AFTER.toSeconds()));
            if (ra.compareTo(wait) > 0) wait = ra;
        }
        notBefore = now.plus(wait);
        return wait;
    }

    synchronized void succeeded() {
        failures = 0;
        notBefore = Instant.MIN;
    }
}
