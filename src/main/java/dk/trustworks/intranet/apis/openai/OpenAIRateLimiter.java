package dk.trustworks.intranet.apis.openai;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class OpenAIRateLimiter {

    @ConfigProperty(name = "openai.rate.max-concurrency", defaultValue = "1")
    int maxConcurrency;

    @ConfigProperty(name = "openai.rate.requests-per-second", defaultValue = "1.5")
    double requestsPerSecond;

    private Semaphore semaphore;
    private final AtomicLong nextAvailableAtMs = new AtomicLong(0);
    private long minIntervalMs;

    @PostConstruct
    void init() {
        if (maxConcurrency < 1) maxConcurrency = 1;
        semaphore = new Semaphore(maxConcurrency, true);
        // e.g. 1.5 RPS => ~667 ms
        minIntervalMs = (long) Math.max(1, Math.floor(1000.0 / Math.max(0.0001, requestsPerSecond)));
    }

    public <T> T callThrottled(CheckedSupplier<T> supplier) throws Exception {
        semaphore.acquire();
        try {
            pace();
            return supplier.get();
        } finally {
            semaphore.release();
        }
    }

    private void pace() {
        while (true) {
            long now = System.currentTimeMillis();
            long due = nextAvailableAtMs.get();
            long wait = due - now;
            if (wait <= 0) {
                long next = now + minIntervalMs;
                if (nextAvailableAtMs.compareAndSet(due, next)) return;
            } else {
                try { TimeUnit.MILLISECONDS.sleep(Math.min(wait, 100)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> { T get() throws Exception; }
}
