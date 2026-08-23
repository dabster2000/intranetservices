package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.model.InterviewResource;
import dk.trustworks.intranet.recruitmentservice.model.enums.InterviewResourceCategory;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentPositionAccess;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.InterviewResourceService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The interview resources library API (V480): shared interview guides,
 * cases and assessment templates plus per-position pins.
 * <p>
 * Read endpoints carry {@code recruitment:read}; the BFF exposes them to
 * every signed-in employee (any consultant can be pulled in as an
 * interviewer). Write endpoints carry {@code recruitment:write}; the BFF
 * gates them to HR/recruitment roles. Explicit validation — {@code @Valid}
 * is inert in this repo.
 */
@JBossLog
@RequestScoped
@Path("/recruitment/interview-resources")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InterviewResourceResource {

    @Inject
    InterviewResourceService interviewResourceService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentVisibility visibility;

    // ---- DTOs ------------------------------------------------------------------

    /** Upload/create request — file travels base64 like template uploads. */
    public record CreateRequest(String title, String category, String description,
                                String filename, String contentType, String fileContent) {
    }

    public record UpdateRequest(String title, String category, String description) {
    }

    public record PinsRequest(Set<String> resourceUuids) {
    }

    // ---- Library ---------------------------------------------------------------

    @GET
    @RolesAllowed({"recruitment:read"})
    public List<InterviewResource> list() {
        return interviewResourceService.listActive();
    }

    @POST
    @RolesAllowed({"recruitment:write"})
    public Response create(CreateRequest request) {
        requireRecruiterTier();
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(request.fileContent() == null ? "" : request.fileContent());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"INVALID_FILE_CONTENT\"}")
                    .build();
        }
        InterviewResource resource = interviewResourceService.create(
                request.title(), parseCategory(request.category()), request.description(),
                request.filename(), request.contentType(), bytes);
        return Response.status(Response.Status.CREATED).entity(resource).build();
    }

    @PUT
    @Path("/{uuid}")
    @RolesAllowed({"recruitment:write"})
    public InterviewResource update(@PathParam("uuid") String uuid, UpdateRequest request) {
        requireRecruiterTier();
        return interviewResourceService.update(uuid, request.title(),
                request.category() == null ? null : parseCategory(request.category()),
                request.description());
    }

    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"recruitment:write"})
    public Response delete(@PathParam("uuid") String uuid) {
        requireRecruiterTier();
        interviewResourceService.softDelete(uuid);
        return Response.noContent().build();
    }

    @GET
    @Path("/{uuid}/download")
    @RolesAllowed({"recruitment:read"})
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@PathParam("uuid") String uuid) {
        InterviewResourceService.Download download = interviewResourceService.download(uuid);
        return Response.ok(download.bytes())
                .type(download.resource().getContentType())
                .header("Content-Disposition",
                        "attachment; filename=\"" + sanitizeFilename(download.resource().getOriginalFilename()) + "\"")
                .header("Content-Length", download.bytes().length)
                .build();
    }

    // ---- Position pins ---------------------------------------------------------

    @GET
    @Path("/pins/{positionUuid}")
    @RolesAllowed({"recruitment:read"})
    public List<InterviewResource> pinned(@PathParam("positionUuid") String positionUuid) {
        return interviewResourceService.pinnedForPosition(positionUuid);
    }

    @PUT
    @Path("/pins/{positionUuid}")
    @RolesAllowed({"recruitment:write"})
    public Response replacePins(@PathParam("positionUuid") String positionUuid, PinsRequest request) {
        RecruitmentPositionAccess.requireDecidablePosition(
                visibility, actor(), positionUuid);
        interviewResourceService.replacePins(positionUuid,
                request.resourceUuids() == null ? Set.of() : request.resourceUuids());
        return Response.noContent().build();
    }

    private void requireRecruiterTier() {
        if (!visibility.isRecruiterTier(actor())) {
            throw new WebApplicationException(
                    "Managing the shared interview-resource library is reserved for recruitment",
                    Response.Status.FORBIDDEN);
        }
    }

    private String actor() {
        String actor = requestHeaderHolder.getUserUuid();
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By is required", Response.Status.FORBIDDEN);
        }
        return actor;
    }

    // ---- Helpers ---------------------------------------------------------------

    private static InterviewResourceCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return InterviewResourceCategory.OTHER;
        }
        try {
            return InterviewResourceCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"INVALID_CATEGORY\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
    }

    /** Strip quote/control characters so the header cannot be broken. */
    private static String sanitizeFilename(String filename) {
        return filename.replaceAll("[\\r\\n\"\\\\]", "_");
    }
}
