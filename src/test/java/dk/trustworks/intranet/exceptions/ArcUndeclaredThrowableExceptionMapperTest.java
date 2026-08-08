package dk.trustworks.intranet.exceptions;

import io.quarkus.arc.ArcUndeclaredThrowableException;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.RollbackException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit test (no DB / no {@code @QuarkusTest}).
 *
 * <p>Covers the mapper that exists because JAX-RS selects an {@code ExceptionMapper} by the
 * <em>runtime class of the thrown object</em>. A deadlock raised during Hibernate's flush inside
 * {@code beforeCompletion} is thrown after the resource method has returned, as a
 * <em>checked</em> {@link RollbackException} that Arc rewraps as
 * {@link ArcUndeclaredThrowableException}. That type is not a {@link PersistenceException}, so
 * {@link DatabaseConstraintViolationExceptionMapper} was never selected and
 * {@code GenericExceptionMapper} answered 500 — invoice 77ee648a, 2026-08-07 08:46:22.811Z.
 */
class ArcUndeclaredThrowableExceptionMapperTest {

    /**
     * The mapper delegates to the injected {@link DatabaseConstraintViolationExceptionMapper} for
     * the non-lock {@link PersistenceException} case; a real instance is fine, it has no state.
     */
    private ArcUndeclaredThrowableExceptionMapper mapper() {
        ArcUndeclaredThrowableExceptionMapper m = new ArcUndeclaredThrowableExceptionMapper();
        m.persistenceMapper = new DatabaseConstraintViolationExceptionMapper();
        return m;
    }

    /** The exact 1213 shape MariaDB produces, as Hibernate wraps it. */
    private static org.hibernate.exception.LockAcquisitionException deadlock() {
        return new org.hibernate.exception.LockAcquisitionException(
                "could not execute statement",
                new SQLException(
                        "Deadlock found when trying to get lock; try restarting transaction",
                        "40001", 1213));
    }

    /**
     * The production shape: {@code RollbackException} is CHECKED and carries no cause constructor,
     * so Narayana sets it with {@code initCause} — {@code commitAndDisassociate:1339}.
     */
    private static RollbackException rollbackCausedBy(Throwable cause) {
        RollbackException e = new RollbackException("ARJUNA016053: Could not commit transaction.");
        e.initCause(cause);
        return e;
    }

    @Test
    void commitTimeDeadlockWrappedByArcMapsTo409() {
        ArcUndeclaredThrowableException thrown = new ArcUndeclaredThrowableException(
                rollbackCausedBy(new PersistenceException("could not execute statement", deadlock())));

        Response r = mapper().toResponse(thrown);

        assertEquals(409, r.getStatus(),
                "A commit-phase deadlock must surface as a retriable 409, never a 500");
        assertInstanceOf(ErrorResponse.class, r.getEntity());
        assertEquals(DatabaseConstraintViolationExceptionMapper.LOCK_CONTENTION_MESSAGE,
                ((ErrorResponse) r.getEntity()).error());
        assertEquals(409, ((ErrorResponse) r.getEntity()).status());
    }

    /**
     * S3.3 — the suppressed variant. Narayana attaches every deferred {@code beforeCompletion}
     * failure with {@code addSuppressed} ({@code TransactionImple.addSuppressedThrowables:222-228}),
     * and on the {@code H_MIXED} / {@code H_HAZARD} / invalid-state branches it never calls
     * {@code initCause} — so suppressed is the ONLY carrier there. A cause-only walk returns 500.
     */
    @Test
    void commitTimeDeadlockCarriedOnlyAsSuppressedMapsTo409() {
        RollbackException rollback = new RollbackException("ARJUNA016053: Could not commit transaction.");
        rollback.addSuppressed(new PersistenceException("could not execute statement", deadlock()));
        // No initCause: this is the shape the heuristic branches produce.

        Response r = mapper().toResponse(new ArcUndeclaredThrowableException(rollback));

        assertEquals(409, r.getStatus(),
                "The predicate must walk getSuppressed(), not just getCause()");
        assertEquals(DatabaseConstraintViolationExceptionMapper.LOCK_CONTENTION_MESSAGE,
                ((ErrorResponse) r.getEntity()).error());
    }

    /**
     * S3.3 — shape-independence. If the typed Hibernate exception does not survive the rewrap, the
     * SQLSTATE / vendor-code fallback must still classify it. This is the case the plan refused to
     * bet against, because the exact chain was not verified when the fix was designed.
     */
    @Test
    void deadlockRecognisedFromSqlStateAloneWhenTheTypedExceptionIsGone() {
        RuntimeException opaque = new RuntimeException("wrapped by something we do not control",
                new SQLException("Deadlock found when trying to get lock", "40001", 1213));

        Response r = mapper().toResponse(new ArcUndeclaredThrowableException(opaque));

        assertEquals(409, r.getStatus(), "SQLSTATE 40001 / vendor 1213 alone must be enough");
    }

    /** 1205 lock-wait timeout carries SQLSTATE HY000, so only the vendor code identifies it. */
    @Test
    void lockWaitTimeoutRecognisedFromVendorCodeAlone() {
        RuntimeException opaque = new RuntimeException("commit failed",
                new SQLException("Lock wait timeout exceeded; try restarting transaction",
                        "HY000", 1205));

        Response r = mapper().toResponse(new ArcUndeclaredThrowableException(opaque));

        assertEquals(409, r.getStatus(), "MariaDB 1205 must map to the same retriable 409");
    }

    /**
     * <b>The important one.</b> The mapper is registered for every CDI-intercepted endpoint in the
     * API, so this is the evidence it cannot change any currently-working path: anything that is
     * not lock contention and not a PersistenceException keeps the byte-identical 500 that
     * {@code GenericExceptionMapper} produces today.
     */
    @Test
    void unrelatedWrappedExceptionKeepsGeneric500Shape() {
        ArcUndeclaredThrowableException thrown = new ArcUndeclaredThrowableException(
                new java.io.IOException("connection reset by peer"));

        Response r = mapper().toResponse(thrown);

        assertEquals(500, r.getStatus());
        assertInstanceOf(ErrorResponse.class, r.getEntity());
        ErrorResponse body = (ErrorResponse) r.getEntity();
        assertEquals("Internal server error", body.error(), "The 500 body must not change");
        assertEquals(500, body.status());
    }

    /** A wrapped PersistenceException that is neither a constraint violation nor lock contention. */
    @Test
    void unrecognisedPersistenceExceptionStillReturns500() {
        Response r = mapper().toResponse(new ArcUndeclaredThrowableException(
                new PersistenceException("entity not found in persistence context")));

        assertEquals(500, r.getStatus());
        assertEquals("Internal server error", ((ErrorResponse) r.getEntity()).error());
    }

    /** A cyclic cause graph must terminate rather than spin. */
    @Test
    void cyclicCauseGraphTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.addSuppressed(b);   // a -> b -> a

        Response r = mapper().toResponse(new ArcUndeclaredThrowableException(b));

        assertEquals(500, r.getStatus());
    }

    /**
     * S3.2 — there is exactly ONE definition of the 1205/1213 predicate, and it is the one the
     * mappers and the bookDraft Tx2 retry all call. If this ever fails, a second copy has appeared.
     */
    @Test
    void thePredicateIsSharedAndPubliclyReachable() throws Exception {
        java.lang.reflect.Method m = DatabaseConstraintViolationExceptionMapper.class
                .getDeclaredMethod("isTransientLockContention", Throwable.class);
        assertTrue(java.lang.reflect.Modifier.isStatic(m.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPublic(m.getModifiers()),
                "InvoiceFinalizationOrchestrator's Tx2 retry lives in another package and must use "
                        + "this exact predicate rather than a copy");

        assertTrue(DatabaseConstraintViolationExceptionMapper.isTransientLockContention(
                new ArcUndeclaredThrowableException(rollbackCausedBy(deadlock()))));
        assertFalse(DatabaseConstraintViolationExceptionMapper.isTransientLockContention(
                new IllegalStateException("nothing to do with locks")));
    }
}
