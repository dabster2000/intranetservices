package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.model.Practice;
import dk.trustworks.intranet.model.PracticeLead;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.services.PracticeService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Practice registry API (V418, spec §3.3). Reads require {@code practices:read};
 * mutations additionally require {@code practices:write}. Identity for audit
 * lines is taken from the {@code X-Requested-By} header via
 * {@link RequestHeaderHolder}. Mutation bodies are record DTOs so no entity is
 * mass-assigned.
 * <p>
 * {@code GET /practices} intentionally returns ALL registry rows (including
 * inactive and SEGMENT rows) ordered by sort order — the frontend filters to
 * {@code type='PRACTICE' && active} for pickers.
 */
@Tag(name = "practice", description = "Practice registry")
@JBossLog
@Path("/practices")
@RequestScoped
@RolesAllowed({"practices:read"})
@SecurityRequirement(name = "jwt")
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class PracticeResource {

    @Inject
    PracticeService practiceService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    public record CreatePracticeRequest(String code, String displayCode, String name, String type,
                                        Boolean active, Integer sortOrder) {
    }

    public record UpdatePracticeRequest(String name, String displayCode, Boolean active, Integer sortOrder) {
    }

    public record StartLeadRequest(String useruuid, String startdate) {
    }

    public record EndLeadRequest(String enddate) {
    }

    @GET
    public List<Practice> findAll() {
        return practiceService.findAll();
    }

    @POST
    @RolesAllowed({"practices:write"})
    public Response create(CreatePracticeRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        log.infof("PracticeResource.create code=%s displayCode=%s updatedBy=%s",
                request.code(), request.displayCode(), requestHeaderHolder.getUserUuid());
        Practice created = practiceService.create(request.code(), request.displayCode(), request.name(),
                request.type(), request.active(), request.sortOrder());
        return Response.created(URI.create("/practices/" + created.getCode())).entity(created).build();
    }

    @PUT
    @Path("/{code}")
    @RolesAllowed({"practices:write"})
    public Practice update(@PathParam("code") String code, UpdatePracticeRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        log.infof("PracticeResource.update code=%s updatedBy=%s", code, requestHeaderHolder.getUserUuid());
        return practiceService.update(code, request.name(), request.displayCode(), request.active(), request.sortOrder());
    }

    @GET
    @Path("/{code}/leads")
    public List<PracticeLead> findLeads(@PathParam("code") String code) {
        return practiceService.findLeads(code);
    }

    @POST
    @Path("/{code}/leads")
    @RolesAllowed({"practices:write"})
    public Response startLead(@PathParam("code") String code, StartLeadRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        LocalDate startdate = parseDate(request.startdate(), "startdate");
        log.infof("PracticeResource.startLead code=%s useruuid=%s startdate=%s updatedBy=%s",
                code, request.useruuid(), startdate, requestHeaderHolder.getUserUuid());
        PracticeLead created = practiceService.startLead(code, request.useruuid(), startdate);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @PUT
    @Path("/{code}/leads/{uuid}")
    @RolesAllowed({"practices:write"})
    public PracticeLead endLead(@PathParam("code") String code, @PathParam("uuid") String uuid, EndLeadRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        LocalDate enddate = parseDate(request.enddate(), "enddate");
        log.infof("PracticeResource.endLead code=%s uuid=%s enddate=%s updatedBy=%s",
                code, uuid, enddate, requestHeaderHolder.getUserUuid());
        return practiceService.endLead(code, uuid, enddate);
    }

    @GET
    @Path("/{code}/teams")
    public List<Team> findTeams(@PathParam("code") String code) {
        return practiceService.findTeams(code);
    }

    private static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) throw new BadRequestException(field + " is required");
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(field + " must be an ISO date (yyyy-MM-dd)");
        }
    }
}
