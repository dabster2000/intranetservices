package dk.trustworks.intranet.aggregates.invoice.services;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps {@link EanPrerequisitesNotMet} to HTTP 400 with the structured
 * {@link dk.trustworks.intranet.aggregates.invoice.economics.EanPrerequisiteErrorDto}
 * as the JSON body. This lets the UI render per-check failure messages.
 *
 * <p>SPEC-INV-001 section 4.3, section 12.5.
 */
@Provider
public class EanPrerequisitesNotMetMapper implements ExceptionMapper<EanPrerequisitesNotMet> {

    @Override
    public Response toResponse(EanPrerequisitesNotMet ex) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ex.getErrorDto())
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
