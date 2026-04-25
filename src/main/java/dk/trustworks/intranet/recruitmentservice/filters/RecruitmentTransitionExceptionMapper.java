package dk.trustworks.intranet.recruitmentservice.filters;

import dk.trustworks.intranet.recruitmentservice.api.dto.RecruitmentTransitionError;
import dk.trustworks.intranet.recruitmentservice.domain.statemachines.InvalidTransitionException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class RecruitmentTransitionExceptionMapper implements ExceptionMapper<InvalidTransitionException> {

    @Override
    public Response toResponse(InvalidTransitionException ex) {
        return Response.status(409)
                .type(MediaType.APPLICATION_JSON)
                .entity(new RecruitmentTransitionError(ex.getMessage(), 409, ex.allowedTransitions()))
                .build();
    }
}
