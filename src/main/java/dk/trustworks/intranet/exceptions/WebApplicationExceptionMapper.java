package dk.trustworks.intranet.exceptions;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps all WebApplicationException (and subclasses like BadRequestException,
 * NotFoundException, etc.) to a consistent JSON error response.
 */
@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

    @Override
    public Response toResponse(WebApplicationException exception) {
        int status = exception.getResponse().getStatus();
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            Response.StatusType statusInfo = Response.Status.fromStatusCode(status);
            message = statusInfo != null ? statusInfo.getReasonPhrase() : "Unknown error";
        }

        return Response.status(status)
                .entity(new ErrorResponse(message, status))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
