package dk.trustworks.intranet.cvtool.service;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Retries a single CV Tool call when it fails for a reason that is about the
 * transport rather than the request.
 *
 * <p>Why this exists: the nightly sync's very first call — {@code getAllEmployees()}
 * — intermittently stalls until the client's 30s read-timeout fires, and because
 * that call gates everything else, one stalled socket costs the whole night's sync
 * for all ~130 employees. Observed twice in seven nights (2026-08-07 and 2026-08-11),
 * both failing at almost exactly 30s. A healthy list fetch returns in ~1.3s, so this
 * is not a call that is "slightly too slow" — it either answers quickly or hangs, and
 * a second attempt against a by-then-warm gateway is the cheapest fix available.
 *
 * <p><b>One instance per run, not per application.</b> The retry budget below is
 * mutable state that must reset every night; sharing an instance across runs would
 * exhaust the budget permanently and silently disable retrying.
 *
 * <p>The sleep is delegated to an injectable {@link Sleeper} so the policy is
 * unit-testable without real waiting — same shape as
 * {@code EconomicsRetryExecutor}, which is deliberately not reused here: it is
 * package-private to expenseservice and keys entirely off e-conomic's
 * {@code EconomicsRateLimitException} and its {@code Retry-After} header, neither
 * of which exists on this path.
 */
@JBossLog
public class CvToolRetryExecutor {

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;

        /** Real sleeper for production wiring. */
        Sleeper REAL = Thread::sleep;
    }

    /**
     * Statuses that mean "the gateway could not serve this right now", as opposed to
     * "this request is wrong". 401/403/404 are excluded on purpose — a bad
     * subscription key does not heal on attempt two, and retrying it just turns a
     * clear auth failure into a slow one. 500 is excluded too: an application error
     * upstream is usually deterministic, so hammering it three times buys nothing.
     */
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(408, 429, 502, 503, 504);

    /** Cap the shift so a pathological attempt count cannot overflow. */
    private static final int MAX_BACKOFF_EXPONENT = 5;

    private static final long BASE_BACKOFF_MILLIS = 2000L;

    /** Defensive bound on cause-chain walking — a self-referencing cause must not hang the job. */
    private static final int MAX_CAUSE_DEPTH = 20;

    private final Sleeper sleeper;
    private final int maxAttempts;
    private int retriesRemaining;
    private int attemptsOfLastCall;

    /**
     * @param sleeper        how to wait between attempts
     * @param maxAttempts    total attempts for any single call, including the first
     * @param retryBudget    how many retries this whole run may spend, across every call
     */
    CvToolRetryExecutor(Sleeper sleeper, int maxAttempts, int retryBudget) {
        this.sleeper = sleeper;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retriesRemaining = Math.max(0, retryBudget);
    }

    /**
     * Runs {@code call}, retrying transient transport failures with backoff.
     *
     * <p>The last failure is rethrown unchanged once attempts or the run-wide budget
     * are exhausted, so the caller still sees the original exception and can wrap it
     * with its own context.
     *
     * @param what short label for logging, e.g. {@code "employee list"}
     */
    <T> T call(String what, Supplier<T> call) {
        int attempt = 1;
        while (true) {
            try {
                T result = call.get();
                attemptsOfLastCall = attempt;
                return result;
            } catch (RuntimeException e) {
                attemptsOfLastCall = attempt;
                if (attempt >= maxAttempts || retriesRemaining <= 0 || !isTransient(e)) {
                    throw e;
                }
                long backoff = backoffMillis(attempt);
                retriesRemaining--;
                // WARN, not DEBUG: a retry that succeeds leaves the job green, so this
                // line is the only trace that the gateway is degrading. Two of these a
                // night is the early warning that the timeout itself needs revisiting.
                log.warnf("CV Tool %s failed on attempt %d/%d (%s) — retrying in %dms, %d retries left this run",
                        what, attempt, maxAttempts, rootCause(e), backoff, retriesRemaining);
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e; // abandon retries on interrupt; surface the original failure
                }
                attempt++;
            }
        }
    }

    /**
     * How many attempts the most recent {@link #call} actually made.
     *
     * <p>Must be reported rather than assumed: most failures are rethrown on the
     * <em>first</em> attempt, because a 401/403/404/500 is deliberately not retried.
     * Printing the configured maximum instead would claim a retry ladder ran when it
     * did not — and would contradict the elapsed time on the same line, since three
     * attempts cannot fit inside the 6s of backoff they require. This message is the
     * one an on-call reader sees first, so it has to be true.
     *
     * <p>Safe as mutable state because the executor is per-run and the sync is
     * single-threaded: the caller reads this immediately after the call it describes.
     */
    int attemptsOfLastCall() {
        return attemptsOfLastCall;
    }

    /**
     * True when the failure is about the connection rather than the request.
     *
     * <p>{@link ProcessingException} is what RESTEasy raises when no response ever
     * arrived — it wraps {@code SocketTimeoutException}, {@code ConnectException} and
     * friends. That is the exact shape of the production failure this class exists for.
     */
    static boolean isTransient(Throwable t) {
        if (t instanceof ProcessingException) return true;
        if (t instanceof WebApplicationException wae) {
            Response response = wae.getResponse();
            return response != null && RETRYABLE_STATUSES.contains(response.getStatus());
        }
        return false;
    }

    /** Backoff before the next attempt: 2s, 4s, 8s … */
    static long backoffMillis(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), MAX_BACKOFF_EXPONENT);
        return BASE_BACKOFF_MILLIS * (1L << exponent);
    }

    /**
     * Deepest cause in the chain, rendered as {@code SimpleName: message}.
     *
     * <p>This is what makes a failure readable in one look. The real cause sits two
     * levels down — {@code CvToolSyncException} → {@code ProcessingException} →
     * {@code SocketTimeoutException} — so it lands under the <em>second</em>
     * "Caused by:" of a RESTEasy-heavy stack trace, which is exactly the part that
     * gets scrolled past or truncated in CloudWatch. Hoisting it into the message
     * puts "Read timed out" on the first line of the error.
     *
     * <p>Public because the batchlet logs it too; the retry machinery itself stays
     * package-private so only {@link CvToolSyncService} drives it.
     */
    public static String rootCause(Throwable t) {
        if (t == null) return "unknown cause";
        Throwable cause = t;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable next = cause.getCause();
            if (next == null || next == cause) break;
            cause = next;
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
