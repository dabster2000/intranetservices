package dk.trustworks.intranet.exceptions;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class InconsistantDataExceptionMapper implements ExceptionMapper<InconsistantDataException> {

    @Override
    public Response toResponse(InconsistantDataException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(exception.getMessage())
                .build();
    }
}