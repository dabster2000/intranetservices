package dk.trustworks.intranet.exceptions;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class InconsistantDataExceptionMapper implements ExceptionMapper<InconsistantDataException> {

    @Override
    public Response toResponse(InconsistantDataException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(exception.getMessage(), 400))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}