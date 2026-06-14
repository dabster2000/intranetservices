package dk.trustworks.intranet.exceptions;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.jbosslog.JBossLog;
import org.hibernate.exception.ConstraintViolationException;

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
 * The constraint name and SQL state are logged server-side; the client gets a clean, generic
 * message that does not leak schema details.
 */
@Provider
@JBossLog
public class DatabaseConstraintViolationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    static final String MESSAGE =
            "The request conflicts with existing data — a database uniqueness constraint was violated "
                    + "(a matching record may already exist).";

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        String sqlState = exception.getSQLException() != null ? exception.getSQLException().getSQLState() : "?";
        log.warnf("DB constraint violation → 409: constraint=%s sqlState=%s — %s",
                exception.getConstraintName(), sqlState, exception.getMessage());
        return Response.status(Response.Status.CONFLICT)
                .entity(new ErrorResponse(MESSAGE, Response.Status.CONFLICT.getStatusCode()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
