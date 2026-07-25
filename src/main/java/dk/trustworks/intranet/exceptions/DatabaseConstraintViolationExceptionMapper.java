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

    /**
     * True for the lock-contention family only: jakarta {@link LockTimeoutException} (MariaDB 1205)
     * and {@link jakarta.persistence.PessimisticLockException}, plus Hibernate's
     * {@link org.hibernate.PessimisticLockException} hierarchy, which covers
     * {@code org.hibernate.exception.LockAcquisitionException} (deadlock, MariaDB 1213) and
     * {@code org.hibernate.exception.LockTimeoutException} as subclasses.
     */
    private static boolean isTransientLockContention(Throwable throwable) {
        return findCause(throwable, LockTimeoutException.class) != null
                || findCause(throwable, jakarta.persistence.PessimisticLockException.class) != null
                || findCause(throwable, org.hibernate.PessimisticLockException.class) != null;
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
