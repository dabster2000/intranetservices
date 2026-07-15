package dk.trustworks.intranet.aggregates.practices.resources;

import dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Keeps contribution availability failures on a closed public wire contract.
 * The exception itself accepts only the three documented codes, and this mapper
 * deliberately serializes no exception message, cause, stack, or upstream field.
 */
@Provider
public final class ContributionUnavailableExceptionMapper
        implements ExceptionMapper<ContributionUnavailableException> {

    @Override
    public Response toResponse(ContributionUnavailableException exception) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ContributionUnavailableResponse(exception.getMessage()))
                .build();
    }

    public record ContributionUnavailableResponse(String code) {
    }
}
