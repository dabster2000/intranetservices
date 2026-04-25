package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.api.dto.ApplicationResponse;
import dk.trustworks.intranet.recruitmentservice.api.dto.OpenRoleAssignmentRequest;
import dk.trustworks.intranet.recruitmentservice.api.dto.OpenRoleCreateRequest;
import dk.trustworks.intranet.recruitmentservice.api.dto.OpenRolePatchRequest;
import dk.trustworks.intranet.recruitmentservice.api.dto.OpenRoleResponse;
import dk.trustworks.intranet.recruitmentservice.api.dto.OpenRoleTransitionRequest;
import dk.trustworks.intranet.recruitmentservice.api.dto.RoleAssignmentResponse;
import dk.trustworks.intranet.recruitmentservice.application.ApplicationService;
import dk.trustworks.intranet.recruitmentservice.application.OpenRoleService;
import dk.trustworks.intranet.recruitmentservice.application.RecruitmentRecordAccessService;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RoleStatus;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/recruitment/roles")
@RequestScoped
@Produces("application/json")
@Consumes("application/json")
@RolesAllowed({"recruitment:read"})
public class OpenRoleResource {

    @Inject OpenRoleService service;
    @Inject RecruitmentRecordAccessService recordAccess;
    @Inject ApplicationService applicationService;
    @Inject RequestHeaderHolder header;

    @GET
    public List<OpenRoleResponse> list(
            @QueryParam("status") RoleStatus status,
            @QueryParam("practice") String practice,
            @QueryParam("team") String team,
            @QueryParam("owner") String owner,
            @QueryParam("hiringCategory") String hiringCategory,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size) {
        return service.list(status, practice, team, owner, hiringCategory, page, size,
                        recordAccess.openRolePredicate(header.getUserUuid())).stream()
                .map(OpenRoleResponse::from).toList();
    }

    @POST
    @RolesAllowed({"recruitment:write"})
    public Response create(@Valid OpenRoleCreateRequest req) {
        OpenRole r = OpenRole.withFreshUuid();
        r.title = req.title();
        r.hiringCategory = req.hiringCategory();
        r.pipelineKind = req.hiringCategory().pipelineKind();
        r.practice = req.practice();
        r.careerLevelUuid = req.careerLevelUuid();
        r.companyUuid = req.companyUuid();
        r.teamUuid = req.teamUuid();
        r.functionArea = req.functionArea();
        r.hiringSource = req.hiringSource();
        r.hiringReason = req.hiringReason();
        r.targetStartDate = req.targetStartDate();
        r.expectedAllocation = req.expectedAllocation();
        r.expectedRateBand = req.expectedRateBand();
        r.salaryMin = req.salaryMin();
        r.salaryMax = req.salaryMax();
        r.priority = req.priority();
        OpenRole created = service.create(r, header.getUserUuid());
        return Response.status(201).entity(OpenRoleResponse.from(created)).build();
    }

    @GET
    @Path("/{uuid}")
    public OpenRoleResponse find(@PathParam("uuid") String uuid) {
        OpenRole role = service.find(uuid);
        if (!recordAccess.canSeeOpenRole(role, header.getUserUuid())) {
            throw new NotFoundException("OpenRole " + uuid);
        }
        return OpenRoleResponse.from(role);
    }

    @PATCH
    @Path("/{uuid}")
    @RolesAllowed({"recruitment:write"})
    public OpenRoleResponse patch(@PathParam("uuid") String uuid, OpenRolePatchRequest req) {
        OpenRole existing = service.find(uuid);
        if (!recordAccess.canSeeOpenRole(existing, header.getUserUuid())) {
            throw new NotFoundException("OpenRole " + uuid);
        }
        return OpenRoleResponse.from(service.patch(uuid, req, header.getUserUuid()));
    }

    @POST
    @Path("/{uuid}/transitions")
    @RolesAllowed({"recruitment:write"})
    public OpenRoleResponse transition(@PathParam("uuid") String uuid, @Valid OpenRoleTransitionRequest req) {
        OpenRole existing = service.find(uuid);
        if (!recordAccess.canSeeOpenRole(existing, header.getUserUuid())) {
            throw new NotFoundException("OpenRole " + uuid);
        }
        return OpenRoleResponse.from(
                service.transition(uuid, req.toStatus(), req.reason(), header.getUserUuid()));
    }

    @POST
    @Path("/{uuid}/assignments")
    @RolesAllowed({"recruitment:write"})
    public Response assign(@PathParam("uuid") String uuid, @Valid OpenRoleAssignmentRequest req) {
        OpenRole existing = service.find(uuid);
        if (!recordAccess.canSeeOpenRole(existing, header.getUserUuid())) {
            throw new NotFoundException("OpenRole " + uuid);
        }
        var a = service.addAssignment(uuid, req.userUuid(), req.responsibilityKind(), header.getUserUuid());
        return Response.status(201).entity(RoleAssignmentResponse.from(a)).build();
    }

    @DELETE
    @Path("/{uuid}/assignments/{userUuid}")
    @RolesAllowed({"recruitment:write"})
    public Response unassign(@PathParam("uuid") String uuid, @PathParam("userUuid") String userUuid) {
        OpenRole existing = service.find(uuid);
        if (!recordAccess.canSeeOpenRole(existing, header.getUserUuid())) {
            throw new NotFoundException("OpenRole " + uuid);
        }
        service.removeAssignment(uuid, userUuid);
        return Response.noContent().build();
    }

    @GET
    @Path("/{uuid}/applications")
    public List<ApplicationResponse> applications(@PathParam("uuid") String roleUuid) {
        return applicationService.listForRole(roleUuid).stream()
                .filter(recordAccess.applicationPredicate(header.getUserUuid()))
                .map(ApplicationResponse::from).toList();
    }
}
