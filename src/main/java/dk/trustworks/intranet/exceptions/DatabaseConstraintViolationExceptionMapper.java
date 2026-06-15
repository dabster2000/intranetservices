package dk.trustworks.intranet.exceptions;

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

    @Override
    public Response toResponse(PersistenceException exception) {
        ConstraintViolationException constraintViolation = findCause(exception, ConstraintViolationException.class);
        SQLIntegrityConstraintViolationException sqlIntegrity = findCause(exception, SQLIntegrityConstraintViolationException.class);

        if (constraintViolation == null && sqlIntegrity == null) {
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
