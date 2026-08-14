package dk.trustworks.intranet.recruitmentservice.services;

import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The F10 regression (2026-08-14): submitting the availability worker
 * from an after-commit hook via a {@code ManagedExecutor} propagated
 * the COMPLETED transaction context onto the worker thread —
 * {@code QuarkusTransaction.isActive()} answered true there, and the
 * §P9 M1 guard in {@code AvailabilityExtractionService} threw on every
 * single message, killing Phase 12 outright. Only a live container can
 * see this (the guard needs a real transaction context), which is why
 * the DB-free tier shipped it green.
 * <p>
 * This pins the fix's exact contract: a task submitted through
 * {@link SchedulingAsyncRunner} from INSIDE a transaction runs (a) only
 * after that transaction committed, (b) with NO transaction associated,
 * and (c) with an active request context for Panache reads.
 */
@QuarkusTest
class SchedulingAsyncRunnerContextTest {

    @Inject
    SchedulingAsyncRunner runner;

    @Test
    void afterCommitTask_seesNoTransaction_andALiveRequestContext() throws Exception {
        AtomicBoolean transactionActiveInTask = new AtomicBoolean(true);
        AtomicBoolean requestContextActiveInTask = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        QuarkusTransaction.requiringNew().run(() ->
                runner.submitAfterCommit(() -> {
                    transactionActiveInTask.set(QuarkusTransaction.isActive());
                    requestContextActiveInTask.set(
                            Arc.container().requestContext().isActive());
                    done.countDown();
                }));

        assertTrue(done.await(10, TimeUnit.SECONDS), "the after-commit task must run");
        assertFalse(transactionActiveInTask.get(),
                "the worker must not inherit the committed transaction (F10: the "
                        + "extraction guard fires on exactly this)");
        assertTrue(requestContextActiveInTask.get(),
                "the worker needs a fresh request context for Panache reads");
    }

    @Test
    void withoutATransaction_theTaskStillRuns() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        runner.submitAfterCommit(done::countDown);
        assertTrue(done.await(10, TimeUnit.SECONDS),
                "direct (non-transactional) callers must not lose the task");
    }
}
