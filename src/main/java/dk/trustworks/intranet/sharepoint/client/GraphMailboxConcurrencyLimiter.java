package dk.trustworks.intranet.sharepoint.client;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A per-mailbox permit gate in front of Microsoft Graph calls that address
 * one mailbox in the URL ({@code /users/{mailbox}/...}).
 * <p>
 * Microsoft caps <strong>MailboxConcurrency</strong> at 4 concurrent
 * requests per application per mailbox; past that Graph answers 429
 * {@code ApplicationThrottled}. The cap is enforced tenant-side across
 * everything our app registration does, so it cannot be respected by
 * anything request-scoped — the free/busy sweep is sequential <em>within</em>
 * a request, and production still breached the cap on 2026-08-15 because
 * several concurrent requests each pinned their whole sweep onto the same
 * caller mailbox.
 * <p>
 * Hence: application-scoped, keyed by mailbox (case-insensitively — Graph
 * echoes addresses with arbitrary casing), and deliberately set BELOW the
 * tenant limit. The default of 2 leaves headroom for the honest reason that
 * this semaphore is per JVM: with more than one ECS task behind the load
 * balancer the effective tenant-side concurrency is
 * {@code tasks × permits}, and 2 keeps a two-task deployment exactly at the
 * limit rather than over it. Raising
 * {@code dk.trustworks.graph.mailbox-concurrency} without first counting the
 * running tasks re-opens the incident.
 * <p>
 * This is a safety net, not the primary remedy — spreading calls across
 * mailboxes so no single one is hammered is what actually keeps us under the
 * cap. The limiter exists so that a caller which fails to spread cannot
 * silently melt a mailbox.
 */
@ApplicationScoped
public class GraphMailboxConcurrencyLimiter {

    /** Permits per mailbox. Below Microsoft's tenant-side cap of 4 — see the class note. */
    @ConfigProperty(name = "dk.trustworks.graph.mailbox-concurrency", defaultValue = "2")
    int permitsPerMailbox;

    /**
     * How long a caller waits for a permit before giving up. Short on
     * purpose: a getSchedule batch that cannot start promptly is better
     * reported as unresolved than queued behind the ALB's 60s idle timeout.
     * <p>
     * This is a PER-ATTEMPT bound. A caller that probes many times per
     * request must not simply pay it repeatedly — {@code
     * RecruitmentCalendarService} spends one sweep-wide blocking budget via
     * {@link #tryAcquire(String, long)} instead.
     */
    @ConfigProperty(name = "dk.trustworks.graph.mailbox-concurrency.wait-ms", defaultValue = "1000")
    long permitWaitMillis;

    private final Map<String, Semaphore> permitsByMailbox = new ConcurrentHashMap<>();

    /** Unit-test constructor: fixed permits, fixed wait, no config injection. */
    public GraphMailboxConcurrencyLimiter() {
    }

    public GraphMailboxConcurrencyLimiter(int permitsPerMailbox, long permitWaitMillis) {
        this.permitsPerMailbox = permitsPerMailbox;
        this.permitWaitMillis = permitWaitMillis;
    }

    /** The configured per-attempt wait, for callers that budget their own. */
    public long permitWaitMillis() {
        return permitWaitMillis;
    }

    /** Claim a permit, waiting up to the configured {@link #permitWaitMillis()}. */
    public boolean tryAcquire(String mailbox) {
        return tryAcquire(mailbox, permitWaitMillis);
    }

    /**
     * Try to claim the right to make one Graph call against {@code mailbox},
     * waiting no longer than {@code waitMillis}.
     * <p>
     * The explicit wait exists so a caller that makes MANY probes per request
     * can spend one shared time budget across all of them, rather than paying
     * the per-probe wait once per probe. A {@code waitMillis} of zero still
     * attempts the acquire — it just does not block — so an exhausted budget
     * degrades to "take a permit if one is free", never to "skip the call".
     *
     * @return true when a permit was taken — the caller MUST
     *         {@link #release(String)} it in a {@code finally}; false when
     *         the wait elapsed, which the caller must treat as "not
     *         attempted, availability unknown", never as "free"
     */
    public boolean tryAcquire(String mailbox, long waitMillis) {
        if (permitsPerMailbox <= 0) {
            return false;
        }
        try {
            return semaphore(mailbox).tryAcquire(Math.max(0L, waitMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Preserve the interrupt for whoever owns this thread's lifecycle;
            // for us it is simply a permit we did not get.
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Return a permit taken by {@link #tryAcquire(String)}. */
    public void release(String mailbox) {
        Semaphore semaphore = permitsByMailbox.get(key(mailbox));
        if (semaphore != null) {
            semaphore.release();
        }
    }

    private Semaphore semaphore(String mailbox) {
        return permitsByMailbox.computeIfAbsent(key(mailbox),
                k -> new Semaphore(permitsPerMailbox, true));
    }

    private static String key(String mailbox) {
        return mailbox == null ? "" : mailbox.toLowerCase(Locale.ROOT);
    }
}
