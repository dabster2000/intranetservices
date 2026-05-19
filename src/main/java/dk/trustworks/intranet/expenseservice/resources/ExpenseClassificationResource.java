package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.ExpenseClassificationDTOs;
import dk.trustworks.intranet.expenseservice.services.ExpenseClassificationService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/expenses/classification")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExpenseClassificationResource {

    @Inject ExpenseClassificationService service;
    @Inject RequestHeaderHolder header;

    @GET
    @Path("/tree")
    @RolesAllowed({"expenses:read"})
    public ExpenseClassificationDTOs.TreeResponse tree() {
        return service.getActiveTree();
    }

    @POST
    @Path("/analyze")
    @RolesAllowed({"expenses:write"})
    public ExpenseClassificationDTOs.AnalyzeResponse analyze(
            @QueryParam("useruuid") String useruuid,
            ExpenseClassificationDTOs.AnalyzeRequest request
    ) {
        return service.analyze(resolveUser(useruuid), request);
    }

    @POST
    @Path("/resolve")
    @RolesAllowed({"expenses:write"})
    public ExpenseClassificationDTOs.ResolveResponse resolve(
            @QueryParam("useruuid") String useruuid,
            ExpenseClassificationDTOs.ResolveRequest request
    ) {
        return service.resolve(resolveUser(useruuid), request);
    }

    private String resolveUser(String fallback) {
        String actor = header.getUserUuid();
        if (actor != null && !actor.isBlank()) return actor;
        if (fallback != null && !fallback.isBlank()) return fallback;
        throw new BadRequestException("useruuid is required");
    }
}
