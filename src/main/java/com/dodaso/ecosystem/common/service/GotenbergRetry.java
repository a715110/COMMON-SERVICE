package com.dodaso.ecosystem.common.service;

import java.util.function.Predicate;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * Retry loop for the Gotenberg call: runs the call up to maxAttempts times,
 * pausing between attempts (initialBackoffMs, multiplied after each failure),
 * and gives up straight away on an error the caller classifies as permanent
 * (a corrupt or unsupported file will fail the same way every time, so
 * retrying it only delays the FAILED status).
 *
 * beforeAttempt runs ahead of every attempt, including the first; the
 * conversion service uses it to mark the row PROCESSING and bump attempt_count,
 * so the count shown to users matches the real number of tries.
 *
 * The last exception is rethrown unchanged, so the caller's existing
 * markFailed handling records the real cause.
 */
@Slf4j
final class GotenbergRetry {

    /** Sleep hook so tests do not have to wait. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private GotenbergRetry() {
    }

    static <T> T execute(final Supplier<T> call, final Runnable beforeAttempt, final int maxAttempts,
                         final long initialBackoffMs, final double backoffMultiplier,
                         final Predicate<Throwable> isTransient, final Sleeper sleeper) {
        final int attempts = Math.max(1, maxAttempts);
        long delay = Math.max(0, initialBackoffMs);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            beforeAttempt.run();
            try {
                return call.get();
            } catch (final RuntimeException ex) {
                last = ex;
                if (attempt == attempts || !isTransient.test(ex)) {
                    throw ex;
                }
                log.warn("Gotenberg attempt {}/{} failed with a transient error ({}); retrying in {} ms",
                        attempt, attempts, ex.getMessage(), delay);
                try {
                    sleeper.sleep(delay);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
                delay = Math.round(delay * Math.max(1.0, backoffMultiplier));
            }
        }
        throw last; // not reached: the loop returns or throws
    }
}
