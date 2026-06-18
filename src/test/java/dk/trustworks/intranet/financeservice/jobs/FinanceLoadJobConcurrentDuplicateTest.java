package dk.trustworks.intranet.financeservice.jobs;

import org.junit.jupiter.api.Test;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FinanceLoadJob#isConcurrentDuplicate(Throwable)} — the classifier that
 * lets the nightly economics reload tolerate a concurrent peer run (e.g. the draining OLD task
 * re-firing the 21:00 schedule during an ECS-Express deploy bake).
 *
 * <p>A uq_fd_logical_key 1062 collision means a peer already inserted the rows, so the job skips
 * that (company, period) quietly instead of alerting. A genuine, non-duplicate failure must NOT
 * be classified as a duplicate — it has to fall through to the Slack alert. Pure logic, no DB/CDI.
 */
class FinanceLoadJobConcurrentDuplicateTest {

    /** The exact MariaDB 1062 wrapping seen in prod: a commit-time violation surfaces deeply nested. */
    @Test
    void wrappedUqFdLogicalKeyCollision_isConcurrentDuplicate() {
        SQLIntegrityConstraintViolationException sql = new SQLIntegrityConstraintViolationException(
                "Duplicate entry '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3-BOOKED-0-1001-1-2250-119' "
                        + "for key 'uq_fd_logical_key'", "23000", 1062);
        Throwable wrapped = new RuntimeException("ARJUNA transaction rolled back",
                new RuntimeException("could not execute statement", sql));

        assertTrue(FinanceLoadJob.isConcurrentDuplicate(wrapped));
    }

    /** Constraint name alone (locale-independent) is enough, even without the English "Duplicate entry". */
    @Test
    void constraintNameInMessage_isConcurrentDuplicate() {
        Throwable e = new RuntimeException("INSERT failed on constraint uq_fd_logical_key");
        assertTrue(FinanceLoadJob.isConcurrentDuplicate(e));
    }

    /** "Duplicate entry" (MariaDB 1062 text) is the secondary signal. */
    @Test
    void duplicateEntryText_isConcurrentDuplicate() {
        Throwable e = new RuntimeException("Duplicate entry 'x' for key 'PRIMARY'");
        assertTrue(FinanceLoadJob.isConcurrentDuplicate(e));
    }

    /** An e-conomic / network failure must still alert — not be swallowed as a duplicate. */
    @Test
    void unrelatedFailure_isNotConcurrentDuplicate() {
        Throwable e = new RuntimeException("e-conomic API returned 500",
                new IllegalStateException("read timed out"));
        assertFalse(FinanceLoadJob.isConcurrentDuplicate(e));
    }

    /** A genuine, non-duplicate integrity error has no duplicate marker and must fall through to alert. */
    @Test
    void nonDuplicateIntegrityError_isNotConcurrentDuplicate() {
        Throwable e = new SQLIntegrityConstraintViolationException(
                "Column 'amount' cannot be null", "23000", 1048);
        assertFalse(FinanceLoadJob.isConcurrentDuplicate(e));
    }

    /** Null messages in the cause chain must not NPE. */
    @Test
    void nullMessageInChain_isSafeAndNotDuplicate() {
        Throwable e = new RuntimeException((String) null, new NullPointerException());
        assertFalse(FinanceLoadJob.isConcurrentDuplicate(e));
    }
}
