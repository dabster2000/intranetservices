package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.remote.EconomicsRateLimitException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the per-item retry/backoff policy without DB or network: a transient 429
 * is retried and can succeed; Retry-After drives the delay when present;
 * exhausted retries rethrow; the injected sleeper is used (no real sleeping).
 */
class EconomicsRetryExecutorTest {

    /** Records requested sleeps instead of actually sleeping. */
    static final class RecordingSleeper implements EconomicsRetryExecutor.Sleeper {
        final List<Long> sleeps = new ArrayList<>();
        @Override public void sleep(long millis) { sleeps.add(millis); }
    }

    @Test
    void retries_then_succeeds_on_second_attempt() {
        RecordingSleeper sleeper = new RecordingSleeper();
        EconomicsRetryExecutor exec = new EconomicsRetryExecutor(3, sleeper);
        AtomicInteger calls = new AtomicInteger();

        String result = exec.executeWithRetry(() -> {
            if (calls.getAndIncrement() == 0) {
                throw new EconomicsRateLimitException("429", null);
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(2, calls.get());            // 1 failure + 1 success
        assertEquals(List.of(1000L), sleeper.sleeps); // first backoff = 1s
    }

    @Test
    void computes_exponential_backoff_when_no_retry_after() {
        assertEquals(1000L, EconomicsRetryExecutor.computeBackoffMillis(1, null));
        assertEquals(2000L, EconomicsRetryExecutor.computeBackoffMillis(2, null));
        assertEquals(4000L, EconomicsRetryExecutor.computeBackoffMillis(3, null));
    }

    @Test
    void honors_retry_after_seconds() {
        assertEquals(5000L, EconomicsRetryExecutor.computeBackoffMillis(1, 5L));
        assertEquals(5000L, EconomicsRetryExecutor.computeBackoffMillis(3, 5L));
        assertEquals(1000L, EconomicsRetryExecutor.computeBackoffMillis(1, 0L)); // 0 => fall back to backoff
    }

    @Test
    void rethrows_after_retries_exhausted() {
        RecordingSleeper sleeper = new RecordingSleeper();
        EconomicsRetryExecutor exec = new EconomicsRetryExecutor(3, sleeper);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(EconomicsRateLimitException.class, () ->
                exec.executeWithRetry(() -> {
                    calls.incrementAndGet();
                    throw new EconomicsRateLimitException("429", null);
                }));

        assertEquals(4, calls.get());            // 1 initial + 3 retries
        assertEquals(3, sleeper.sleeps.size());  // slept before each retry
    }
}
