package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.remote.EconomicsRateLimitException;

import java.util.function.Supplier;

/**
 * Wraps a single e-conomic call so a transient HTTP 429 is retried with backoff
 * instead of immediately failing. Stateless across calls — each
 * {@link #executeWithRetry} is independent — so one instance is safely reused
 * for every item in a batch run.
 *
 * <p>The sleep is delegated to an injectable {@link Sleeper} so the policy is
 * unit-testable without real waiting.
 */
class EconomicsRetryExecutor {

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;

        /** Real sleeper for production wiring. */
        Sleeper REAL = Thread::sleep;
    }

    private final int maxRetries;
    private final Sleeper sleeper;

    EconomicsRetryExecutor(int maxRetries, Sleeper sleeper) {
        this.maxRetries = maxRetries;
        this.sleeper = sleeper;
    }

    /**
     * Run {@code call}; on {@link EconomicsRateLimitException}, sleep and retry up
     * to {@code maxRetries} times. If still throttled after the last retry, the
     * latest {@link EconomicsRateLimitException} is rethrown so the caller can
     * count the item as throttled.
     */
    <T> T executeWithRetry(Supplier<T> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (EconomicsRateLimitException rle) {
                attempt++;
                if (attempt > maxRetries) {
                    throw rle;
                }
                long backoff = computeBackoffMillis(attempt, rle.getRetryAfterSeconds());
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw rle; // abandon retries on interrupt; surface as throttled
                }
            }
        }
    }

    // Caps exponential backoff at 2^16 s (~65 s) so a pathological maxRetries cannot overflow the shift.
    private static final int MAX_BACKOFF_EXPONENT = 16;

    /**
     * Delay before the next attempt: the server's {@code Retry-After} (seconds)
     * when present and positive ({@code > 0}), otherwise exponential backoff
     * {@code 1s, 2s, 4s, …}. A {@code Retry-After: 0} (or absent header) falls
     * back to the exponential {@code 1s, 2s, 4s, …} backoff — a zero-millisecond
     * retry against a throttling tenant is never appropriate. The exponent is
     * capped so a pathological {@code maxRetries} cannot overflow.
     */
    static long computeBackoffMillis(int attempt, Long retryAfterSeconds) {
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            return retryAfterSeconds * 1000L;
        }
        int exponent = Math.min(Math.max(attempt - 1, 0), MAX_BACKOFF_EXPONENT);
        return 1000L * (1L << exponent);
    }
}
