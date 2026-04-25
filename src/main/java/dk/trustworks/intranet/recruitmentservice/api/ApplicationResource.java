package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.api.dto.ApplicationCreateRequest;
import dk.trustworks.intranet.recruitmentservice.api.dto.ApplicationResponse;
import dk.trustworks.intranet.recruitmentservice.api.dto.ApplicationScreeningDecisionRequest;
import dk.trustworks.intranet.recruitmentservice.api.dto.ApplicationTransitionRequest;
import dk.trustworks.intranet.recruitmentservice.application.ApplicationService;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationStage;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

@Path("/api/recruitment/applications")
@RequestScoped
@Produces("application/json")
@Consumes("application/json")
@RolesAllowed({"recruitment:read"})
public class ApplicationResource {

    @Inject ApplicationService service;
    @Inject RequestHeaderHolder header;
    @Inject ScopeContext scope;

    @POST
    @RolesAllowed({"recruitment:write"})
    public Response create(@Valid ApplicationCreateRequest req) {
        var a = service.create(req.candidateUuid(), req.roleUuid(),
                req.applicationType(), req.referrerUserUuid(), header.getUserUuid());
        return Response.status(201).entity(ApplicationResponse.from(a)).build();
    }

    @GET
    @Path("/{uuid}")
    public ApplicationResponse find(@PathParam("uuid") String uuid) {
        return ApplicationResponse.from(service.find(uuid));
    }

    @POST
    @Path("/{uuid}/transitions")
    @RolesAllowed({"recruitment:write", "recruitment:offer", "recruitment:admin"})
    public ApplicationResponse transition(@PathParam("uuid") String uuid, @Valid ApplicationTransitionRequest req) {
        boolean offerTransition = req.toStage() == ApplicationStage.OFFER || req.toStage() == ApplicationStage.ACCEPTED;
        if (offerTransition && !scope.hasAnyScope("recruitment:offer", "recruitment:admin")) {
            throw new ForbiddenException("OFFER/ACCEPTED transitions require recruitment:offer or recruitment:admin");
        }
        if (!offerTransition && !scope.hasAnyScope("recruitment:write", "recruitment:admin")) {
            throw new ForbiddenException("Application transition requires recruitment:write or recruitment:admin");
        }
        var a = service.transition(uuid, req.toStage(), req.reason(), header.getUserUuid());
        return ApplicationResponse.from(a);
    }

    @POST
    @Path("/{uuid}/withdraw")
    @RolesAllowed({"recruitment:write"})
    public ApplicationResponse withdraw(@PathParam("uuid") String uuid, ApplicationTransitionRequest body) {
        var reason = body == null ? null : body.reason();
        return ApplicationResponse.from(service.withdraw(uuid, reason, header.getUserUuid()));
    }

    @POST
    @Path("/{uuid}/screening-decision")
    @RolesAllowed({"recruitment:write"})
    public ApplicationResponse screeningDecision(@PathParam("uuid") String uuid,
                                                  @Valid ApplicationScreeningDecisionRequest req) {
        return ApplicationResponse.from(service.recordScreeningDecision(uuid, req.outcome(),
                req.overrideReason(), header.getUserUuid()));
    }
}
