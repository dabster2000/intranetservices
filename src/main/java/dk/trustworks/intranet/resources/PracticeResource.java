package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.model.Practice;
import dk.trustworks.intranet.model.PracticeLead;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.services.PracticeService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
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
 * Practice registry API (V418, spec §3.3; identifier semantics §4.5 from Part 2
 * Phase 3). Reads require {@code practices:read}; mutations additionally require
 * {@code practices:write}. Identity for audit lines is taken from the
 * {@code X-Requested-By} header via {@link RequestHeaderHolder}. Mutation bodies
 * are record DTOs so no entity is mass-assigned.
 * <p>
 * <b>Identifier resolution (Phase 3, §4.5):</b> every {@code {id}} path parameter
 * resolves as the practice <em>uuid</em> first (the canonical, stable API
 * identifier — codes become mutable in Phase 5) and falls back to the storage
 * code as a compatibility alias. The code alias is deprecated and is removed in
 * Phase 5; responses carry both {@code uuid} and {@code code}.
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

    /** Shared OpenAPI description for every {id} path parameter (§4.5). */
    private static final String ID_PARAM_DESCRIPTION =
            "Practice uuid (canonical identifier). The storage code (e.g. PM, SA) is "
            + "accepted as a deprecated compatibility alias until Phase 5 of the "
            + "practice data-model migration removes it — new integrations must send the uuid.";

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
    @Operation(summary = "List all practice registry rows",
            description = "Returns every registry row (including inactive and SEGMENT rows) ordered by "
                    + "sort order. Each row carries the canonical uuid identifier alongside the storage code.")
    public List<Practice> findAll() {
        return practiceService.findAll();
    }

    @POST
    @RolesAllowed({"practices:write"})
    @Operation(summary = "Create a practice registry row",
            description = "The uuid identifier is server-minted; the Location header uses the storage "
                    + "code alias during the deprecation window.")
    public Response create(CreatePracticeRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        log.infof("PracticeResource.create code=%s displayCode=%s updatedBy=%s",
                request.code(), request.displayCode(), requestHeaderHolder.getUserUuid());
        Practice created = practiceService.create(request.code(), request.displayCode(), request.name(),
                request.type(), request.active(), request.sortOrder());
        return Response.created(URI.create("/practices/" + created.getCode())).entity(created).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({"practices:write"})
    @Operation(summary = "Update a practice registry row")
    public Practice update(
            @Parameter(description = ID_PARAM_DESCRIPTION) @PathParam("id") String id,
            UpdatePracticeRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        Practice practice = resolveOr404(id);
        log.infof("PracticeResource.update id=%s code=%s updatedBy=%s",
                id, practice.getCode(), requestHeaderHolder.getUserUuid());
        return practiceService.update(practice.getCode(), request.name(), request.displayCode(),
                request.active(), request.sortOrder());
    }

    @GET
    @Path("/{id}/leads")
    @Operation(summary = "List a practice's leads",
            description = "Unknown identifiers return an empty list (established list-lookup contract), "
                    + "never 404.")
    public List<PracticeLead> findLeads(
            @Parameter(description = ID_PARAM_DESCRIPTION) @PathParam("id") String id) {
        return practiceService.findLeads(practiceService.resolveToCodeOrPassthrough(id));
    }

    @POST
    @Path("/{id}/leads")
    @RolesAllowed({"practices:write"})
    @Operation(summary = "Start a practice lead period")
    public Response startLead(
            @Parameter(description = ID_PARAM_DESCRIPTION) @PathParam("id") String id,
            StartLeadRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        Practice practice = resolveOr404(id);
        LocalDate startdate = parseDate(request.startdate(), "startdate");
        log.infof("PracticeResource.startLead id=%s code=%s useruuid=%s startdate=%s updatedBy=%s",
                id, practice.getCode(), request.useruuid(), startdate, requestHeaderHolder.getUserUuid());
        PracticeLead created = practiceService.startLead(practice.getCode(), request.useruuid(), startdate);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @PUT
    @Path("/{id}/leads/{uuid}")
    @RolesAllowed({"practices:write"})
    @Operation(summary = "End a practice lead period")
    public PracticeLead endLead(
            @Parameter(description = ID_PARAM_DESCRIPTION) @PathParam("id") String id,
            @PathParam("uuid") String uuid,
            EndLeadRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        Practice practice = resolveOr404(id);
        LocalDate enddate = parseDate(request.enddate(), "enddate");
        log.infof("PracticeResource.endLead id=%s code=%s uuid=%s enddate=%s updatedBy=%s",
                id, practice.getCode(), uuid, enddate, requestHeaderHolder.getUserUuid());
        return practiceService.endLead(practice.getCode(), uuid, enddate);
    }

    @GET
    @Path("/{id}/teams")
    @Operation(summary = "List teams assigned to a practice",
            description = "Unknown identifiers return an empty list (established list-lookup contract), "
                    + "never 404.")
    public List<Team> findTeams(
            @Parameter(description = ID_PARAM_DESCRIPTION) @PathParam("id") String id) {
        return practiceService.findTeams(practiceService.resolveToCodeOrPassthrough(id));
    }

    /** Uuid-first, code-fallback resolution (§4.5); 404 preserves the pre-Phase-3 unknown-row behavior. */
    private Practice resolveOr404(String id) {
        Practice practice = practiceService.resolveByIdOrCode(id);
        if (practice == null) throw new NotFoundException("Practice not found: " + id);
        return practice;
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
