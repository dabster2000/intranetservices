package dk.trustworks.intranet.recruitmentservice.model.exception;

import dk.trustworks.intranet.exceptions.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps {@link BusinessRuleViolation} from the recruitment domain aggregates
 * to HTTP {@code 409 Conflict} per the project's REST conventions: state
 * machine guards (e.g. declining a non-ACTIVE candidate, allocating a
 * revision on a CLOSED dossier) are conflicts of state, not invalid input.
 */
@Provider
public class BusinessRuleViolationExceptionMapper implements ExceptionMapper<BusinessRuleViolation> {

    @Override
    public Response toResponse(BusinessRuleViolation exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(new ErrorResponse(exception.getMessage(), 409))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
