package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.api.dto.RecruitmentStatusDTO;
import dk.trustworks.intranet.recruitmentservice.application.RecruitmentStatusService;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RecruitmentStatusValue;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ScopeKind;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import java.util.List;

@Path("/api/recruitment/status")
@RequestScoped
@Produces("application/json")
@Consumes("application/json")
@RolesAllowed({"recruitment:read"})
public class RecruitmentStatusResource {

    @Inject RecruitmentStatusService service;
    @Inject RequestHeaderHolder header;

    public record StatusUpsertRequest(RecruitmentStatusValue status, String reason) {}

    @GET
    public List<RecruitmentStatusDTO> list() {
        return service.listAll().stream().map(RecruitmentStatusDTO::from).toList();
    }

    @PUT
    @Path("/{scopeKind}/{scopeId}")
    @RolesAllowed({"recruitment:write", "recruitment:admin"})
    public RecruitmentStatusDTO upsert(
            @PathParam("scopeKind") ScopeKind kind,
            @PathParam("scopeId") String scopeId,
            StatusUpsertRequest body) {
        return RecruitmentStatusDTO.from(
                service.upsert(kind, scopeId, body.status(), body.reason(), header.getUserUuid()));
    }
}
