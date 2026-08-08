package dk.trustworks.intranet.exceptions;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.jbosslog.JBossLog;
import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLIntegrityConstraintViolationException;

/**
 * Maps a database-level integrity-constraint violation (e.g. a duplicate unique key such as
 * {@code uq_userstatus_user_date}) to a clean <b>409 Conflict</b> instead of a generic 500.
 * <p>
 * This is the <b>Hibernate</b> {@link org.hibernate.exception.ConstraintViolationException}
 * (a real DB constraint such as a duplicate/unique-key or foreign-key violation), which is a
 * <i>different class</i> from {@code jakarta.validation.ConstraintViolationException} (Bean
 * Validation, mapped to 400 by {@link ConstraintViolationExceptionMapper}). Without this mapper
 * such failures fall through to {@link GenericExceptionMapper} and surface as 500.
 * <p>
 * Also maps transient InnoDB lock contention — a lock-wait timeout (MariaDB 1205, surfaced as
 * {@link LockTimeoutException}) or a deadlock victim (MariaDB 1213, surfaced as
 * {@link jakarta.persistence.PessimisticLockException} / {@link org.hibernate.PessimisticLockException})
 * — to <b>409 Conflict</b> with a retriable message. These are inherent under concurrent writes
 * (the row was held by another transaction longer than {@code innodb_lock_wait_timeout}); the
 * client's correct move is to retry, so they must not surface as 500. No server-side retry is
 * attempted: each additional attempt can block up to the full lock-wait timeout again.
 * <p>
 * Quarkus/Hibernate may throw the Hibernate exception directly or wrap it inside a
 * {@link PersistenceException}; this mapper handles both shapes. The constraint name and SQL state
 * are logged server-side; the client gets a clean, generic message that does not leak schema details.
 */
@Provider
@JBossLog
public class DatabaseConstraintViolationExceptionMapper implements ExceptionMapper<PersistenceException> {

    static final String MESSAGE =
            "The request conflicts with existing data — a database uniqueness constraint was violated "
                    + "(a matching record may already exist).";

    static final String LOCK_CONTENTION_MESSAGE =
            "The record is currently locked by another operation. Please try again in a moment.";

    @Override
    public Response toResponse(PersistenceException exception) {
        ConstraintViolationException constraintViolation = findCause(exception, ConstraintViolationException.class);
        SQLIntegrityConstraintViolationException sqlIntegrity = findCause(exception, SQLIntegrityConstraintViolationException.class);

        if (constraintViolation == null && sqlIntegrity == null) {
            if (isTransientLockContention(exception)) {
                log.warnf("DB lock contention -> 409: %s", exception.toString());
                return Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorResponse(LOCK_CONTENTION_MESSAGE, Response.Status.CONFLICT.getStatusCode()))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
            log.error("Unhandled persistence exception in REST resource", exception);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Internal server error", 500))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String constraintName = constraintViolation != null ? constraintViolation.getConstraintName() : "?";
        String sqlState = "?";
        if (constraintViolation != null && constraintViolation.getSQLException() != null) {
            sqlState = constraintViolation.getSQLException().getSQLState();
        } else if (sqlIntegrity != null) {
            sqlState = sqlIntegrity.getSQLState();
        }

        log.warnf("DB constraint violation -> 409: constraint=%s sqlState=%s - %s",
                constraintName, sqlState, exception.getMessage());
        return Response.status(Response.Status.CONFLICT)
                .entity(new ErrorResponse(MESSAGE, Response.Status.CONFLICT.getStatusCode()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /** MariaDB/MySQL {@code ER_LOCK_WAIT_TIMEOUT} — SQLSTATE HY000. */
    private static final int MARIADB_LOCK_WAIT_TIMEOUT = 1205;
    /** MariaDB/MySQL {@code ER_LOCK_DEADLOCK} — SQLSTATE 40001 (serialization failure). */
    private static final int MARIADB_DEADLOCK = 1213;
    /** SQLSTATE class for a serialization failure; MariaDB uses it for 1213. */
    private static final String SQLSTATE_SERIALIZATION_FAILURE = "40001";

    /**
     * True for the lock-contention family only: jakarta {@link LockTimeoutException} (MariaDB 1205)
     * and {@link jakarta.persistence.PessimisticLockException}, plus Hibernate's
     * {@link org.hibernate.PessimisticLockException} hierarchy, which covers
     * {@code org.hibernate.exception.LockAcquisitionException} (deadlock, MariaDB 1213) and
     * {@code org.hibernate.exception.LockTimeoutException} as subclasses.
     *
     * <p><b>This is the single definition of the 1205/1213 predicate.</b>
     * {@link ArcUndeclaredThrowableExceptionMapper} calls it rather than carrying its own copy —
     * two definitions of "is this a deadlock" drift, and the one that drifts is the one that
     * decides between a retriable 409 and an opaque 500.
     *
     * <p>Deliberately shape-independent, because the wrapping is not under our control.
     * {@link #findCause} walks {@code getCause()} <em>and</em> {@code getSuppressed()}, and each node
     * is additionally tested by SQLSTATE, by MariaDB vendor code, and by message text. Any one of
     * those alone would be enough on the chain Narayana builds today; together they survive a
     * rewrap.
     *
     * <p>{@code public} rather than package-private only because the one bounded retry in the
     * system — {@code InvoiceFinalizationOrchestrator.bookDraft}'s Tx2 replay — lives in another
     * package and must decide "retriable lock conflict?" with the <em>same</em> predicate the
     * mappers use. A second copy there is exactly the drift this method exists to prevent.
     */
    public static boolean isTransientLockContention(Throwable throwable) {
        return findMatch(throwable, DatabaseConstraintViolationExceptionMapper::isLockContentionNode) != null;
    }

    /** Tests one node of the throwable graph — no traversal, so {@link #findMatch} owns the walk. */
    private static boolean isLockContentionNode(Throwable t) {
        if (t instanceof LockTimeoutException
                || t instanceof jakarta.persistence.PessimisticLockException
                || t instanceof org.hibernate.PessimisticLockException) {
            return true;
        }
        if (t instanceof java.sql.SQLException sql) {
            if (SQLSTATE_SERIALIZATION_FAILURE.equals(sql.getSQLState())) return true;
            int code = sql.getErrorCode();
            if (code == MARIADB_DEADLOCK || code == MARIADB_LOCK_WAIT_TIMEOUT) return true;
        }
        // Last resort: the driver's own wording. Reached when the exception has been rewrapped as a
        // plain RuntimeException and neither the typed exception nor the SQLException survived.
        String message = t.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("deadlock found when trying to get lock")
                || lower.contains("lock wait timeout exceeded");
    }

    /**
     * Finds the first throwable in the graph assignable to {@code type}.
     *
     * <p>Package-private so {@link ArcUndeclaredThrowableExceptionMapper} reuses this traversal
     * instead of duplicating it.
     */
    static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable found = findMatch(throwable, type::isInstance);
        return found == null ? null : type.cast(found);
    }

    /**
     * Breadth-first walk of {@code getCause()} <b>and</b> {@code getSuppressed()}.
     *
     * <p>Suppressed is not optional. Narayana's {@code TransactionImple.commitAndDisassociate}
     * attaches every deferred {@code beforeCompletion} failure to the thrown exception with
     * {@code addSuppressed} (narayana-jta 7.3.4.Final, {@code addSuppressedThrowables:222-228}). On
     * the {@code ABORTED} branch it <em>also</em> calls {@code initCause}, so a cause-only walk
     * happens to find it — but the {@code H_MIXED} / {@code H_HAZARD} / invalid-state branches
     * construct their exception through {@code addSuppressedThrowables} with <b>no</b>
     * {@code initCause} at all, and there the suppressed array is the only carrier.
     *
     * <p>Bounded by a node budget and an identity-based visited set: a throwable graph may contain
     * cycles ({@code e.initCause(e)}) and, unlike a plain cause chain, may fan out.
     */
    private static Throwable findMatch(Throwable throwable, java.util.function.Predicate<Throwable> test) {
        if (throwable == null) return null;
        java.util.Set<Throwable> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Deque<Throwable> queue = new java.util.ArrayDeque<>();
        queue.add(throwable);
        seen.add(throwable);
        for (int visited = 0; !queue.isEmpty() && visited < MAX_GRAPH_NODES; visited++) {
            Throwable current = queue.poll();
            if (test.test(current)) return current;
            Throwable cause = current.getCause();
            if (cause != null && seen.add(cause)) queue.add(cause);
            // getSuppressed() is final on Throwable, but Mockito's inline mock maker intercepts it
            // anyway and its default answer returns NULL for array types — and Objenesis-created
            // mocks never ran the constructor that initialises the backing field. Real throwables
            // never return null here; mocked ones (which several mapper tests pass in) do.
            Throwable[] suppressed = current.getSuppressed();
            if (suppressed == null) continue;
            for (Throwable s : suppressed) {
                if (s != null && seen.add(s)) queue.add(s);
            }
        }
        return null;
    }

    /**
     * Node budget for {@link #findMatch}. The previous cause-only walk used a depth of 8; a graph
     * that also follows suppressed fans out, so the budget counts nodes rather than depth.
     */
    private static final int MAX_GRAPH_NODES = 32;
}
