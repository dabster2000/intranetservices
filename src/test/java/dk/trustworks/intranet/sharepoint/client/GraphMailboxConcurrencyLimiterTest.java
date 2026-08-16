package dk.trustworks.intranet.sharepoint.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-mailbox permit gate that keeps us under Microsoft's
 * MailboxConcurrency cap of 4 concurrent requests per app per mailbox
 * (production 2026-08-15).
 * <p>
 * Plain unit test — no Quarkus boot, DB-free tier.
 */
class GraphMailboxConcurrencyLimiterTest {

    @Test
    void permitsAreScopedPerMailbox_notGlobal() throws Exception {
        GraphMailboxConcurrencyLimiter limiter = new GraphMailboxConcurrencyLimiter(1, 20);

        assertTrue(limiter.tryAcquire("a@trustworks.dk"));
        // A different mailbox has its own budget — one busy mailbox must not
        // stall the whole sweep.
        assertTrue(limiter.tryAcquire("b@trustworks.dk"));
        // ...but the same mailbox is now at its cap, from any thread.
        assertFalse(acquireOnAnotherThread(limiter, "a@trustworks.dk"));

        limiter.release("a@trustworks.dk");
        assertTrue(limiter.tryAcquire("a@trustworks.dk"), "the permit came back");
    }

    @Test
    void mailboxKeyIsCaseInsensitive() throws Exception {
        // Graph echoes addresses with arbitrary casing; two spellings of one
        // mailbox must contend for the same permit or the cap means nothing.
        GraphMailboxConcurrencyLimiter limiter = new GraphMailboxConcurrencyLimiter(1, 20);

        assertTrue(limiter.tryAcquire("Adam.Hoppe@trustworks.dk"));
        assertFalse(acquireOnAnotherThread(limiter, "adam.hoppe@trustworks.dk"));
    }

    @Test
    void zeroPermits_neverAdmits() {
        // The saturated case callers must treat as "unknown", never "free".
        assertFalse(new GraphMailboxConcurrencyLimiter(0, 1).tryAcquire("a@trustworks.dk"));
    }

    @Test
    void releasingAMailboxNeverAcquired_isHarmless() {
        // The caller releases in a finally; a probe that never got a permit
        // must not corrupt the budget for the next one.
        GraphMailboxConcurrencyLimiter limiter = new GraphMailboxConcurrencyLimiter(1, 20);
        limiter.release("never-seen@trustworks.dk");
        assertTrue(limiter.tryAcquire("never-seen@trustworks.dk"));
    }

    /** tryAcquire on the SAME thread would not block on a reentrant claim —
     * the cap is about concurrent callers, so contention needs a real one. */
    private static boolean acquireOnAnotherThread(GraphMailboxConcurrencyLimiter limiter,
                                                  String mailbox) throws Exception {
        AtomicBoolean acquired = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            acquired.set(limiter.tryAcquire(mailbox));
            done.countDown();
        });
        thread.start();
        assertTrue(done.await(5, TimeUnit.SECONDS), "the limiter must time out, not hang");
        thread.join();
        return acquired.get();
    }
}
