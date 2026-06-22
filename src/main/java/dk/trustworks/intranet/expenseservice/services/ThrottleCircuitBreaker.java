package dk.trustworks.intranet.expenseservice.services;

/**
 * Counts CONSECUTIVE throttled items in a single batch run and trips once the
 * streak reaches {@code threshold}. Any non-throttled item resets the streak,
 * so isolated 429s never abort the run — only sustained throttling does.
 * Single-threaded use within one batch run; no synchronization needed.
 */
class ThrottleCircuitBreaker {

    private final int threshold;
    private int consecutiveThrottled = 0;

    ThrottleCircuitBreaker(int threshold) {
        this.threshold = threshold;
    }

    void recordThrottled() {
        consecutiveThrottled++;
    }

    void reset() {
        consecutiveThrottled = 0;
    }

    boolean isTripped() {
        return consecutiveThrottled >= threshold;
    }

    int getConsecutiveThrottled() {
        return consecutiveThrottled;
    }
}
