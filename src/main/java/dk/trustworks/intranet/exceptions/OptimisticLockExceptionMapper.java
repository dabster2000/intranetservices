package dk.trustworks.intranet.exceptions;

import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps Hibernate/JPA optimistic-locking failures to a consistent JSON 409 response.
 * Triggered when two callers update the same @Version-protected entity concurrently.
 */
@Provider
public class OptimisticLockExceptionMapper implements ExceptionMapper<OptimisticLockException> {

    public static final String STALE_WRITE_MESSAGE =
            "This expense was just updated by someone else. Please refresh and try again.";

    @Override
    public Response toResponse(OptimisticLockException exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(new ErrorResponse(
                        STALE_WRITE_MESSAGE,
                        Response.Status.CONFLICT.getStatusCode()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
