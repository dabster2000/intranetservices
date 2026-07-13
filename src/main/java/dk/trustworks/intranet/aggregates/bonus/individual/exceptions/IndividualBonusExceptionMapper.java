package dk.trustworks.intranet.aggregates.bonus.individual.exceptions;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class IndividualBonusExceptionMapper implements ExceptionMapper<IndividualBonusException> {

    @Override
    public Response toResponse(IndividualBonusException exception) {
        return Response.status(exception.status())
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new IndividualBonusErrorResponse(
                        exception.getMessage(), exception.status(), exception.code(), exception.field(),
                        exception.earningMonth(), exception.payMonth(), exception.manualAction(),
                        exception.expected(), exception.actual(),
                        exception.violations().isEmpty() ? null : exception.violations()))
                .build();
    }
}
