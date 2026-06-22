package dk.trustworks.intranet.expenseservice.remote;

/**
 * Thrown by {@link EconomicsErrorMapper} for HTTP 429 (Too Many Requests) from
 * e-conomic. It deliberately extends {@link RuntimeException} so every existing
 * e-conomic caller that does {@code catch (Exception | RuntimeException)} keeps
 * behaving exactly as before — only {@code ExpenseSyncBatchlet} catches this
 * specific type to drive its retry/backoff/circuit-breaker policy.
 *
 * <p>{@code retryAfterSeconds} carries the value of the {@code Retry-After}
 * response header when e-conomic sends it as an integer number of seconds, or
 * {@code null} when absent or in HTTP-date form (the caller falls back to
 * exponential backoff in that case).
 */
public class EconomicsRateLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long retryAfterSeconds;

    public EconomicsRateLimitException(String message, Long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** @return Retry-After in seconds, or {@code null} if not provided / not numeric. */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
