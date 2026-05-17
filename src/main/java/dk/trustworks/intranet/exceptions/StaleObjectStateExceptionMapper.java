package dk.trustworks.intranet.exceptions;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.hibernate.StaleObjectStateException;

/**
 * Maps Hibernate's StaleObjectStateException (raw subclass thrown before
 * JPA unwrapping) to the same JSON 409 response as OptimisticLockException.
 */
@Provider
public class StaleObjectStateExceptionMapper implements ExceptionMapper<StaleObjectStateException> {

    @Override
    public Response toResponse(StaleObjectStateException exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(new ErrorResponse(
                        OptimisticLockExceptionMapper.STALE_WRITE_MESSAGE,
                        Response.Status.CONFLICT.getStatusCode()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
