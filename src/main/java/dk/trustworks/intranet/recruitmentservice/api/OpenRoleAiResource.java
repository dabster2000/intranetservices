package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.api.dto.AiArtifactResponse;
import dk.trustworks.intranet.recruitmentservice.api.dto.OpenRoleAiBriefRequest;
import dk.trustworks.intranet.recruitmentservice.application.AiArtifactService;
import dk.trustworks.intranet.recruitmentservice.application.RecruitmentRecordAccessService;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST endpoints for AI artifacts attached to an {@link OpenRole} (spec §6.2).
 *
 * <p>Currently exposes a single trigger:
 * <ul>
 *   <li>{@code POST /api/recruitment/roles/{uuid}/ai/role-brief} — enqueue a
 *       {@link AiArtifactKind#ROLE_BRIEF} generation. Returns 202 Accepted with an
 *       {@link AiArtifactResponse} DTO (state is GENERATING until the worker fulfils it).</li>
 * </ul>
 *
 * <p>Idempotency is handled by {@link AiArtifactService#requestArtifact} via the input
 * digest: composing the same {@code (subject_kind, subject_uuid, kind, input_digest)}
 * tuple returns the existing row instead of creating a duplicate, so callers can safely
 * retry without producing fan-out outbox entries.
 *
 * <p>Access control mirrors {@link OpenRoleResource}: the caller must hold
 * {@code recruitment:write} <em>and</em> pass the record-level
 * {@link RecruitmentRecordAccessService#canSeeOpenRole(OpenRole, String)} check. We
 * deliberately collapse "role not found" and "role not visible" to 404 to avoid leaking
 * the existence of roles outside the caller's scope (see commit {@code c388184}).
 */
@Path("/api/recruitment/roles/{uuid}/ai")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OpenRoleAiResource {

    @Inject AiArtifactService artifacts;
    @Inject RecruitmentRecordAccessService recordAccess;
    @Inject RequestHeaderHolder header;

    @POST
    @Path("/role-brief")
    @RolesAllowed({"recruitment:write"})
    public Response triggerRoleBrief(@PathParam("uuid") String roleUuid,
                                     OpenRoleAiBriefRequest req) {
        String actor = header.getUserUuid();
        OpenRole role = OpenRole.findById(roleUuid);
        if (role == null || !recordAccess.canSeeOpenRole(role, actor)) {
            throw new NotFoundException("OpenRole " + roleUuid);
        }

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("title", role.title);
        inputs.put("practice", role.practice);
        inputs.put("careerLevelUuid", role.careerLevelUuid);
        inputs.put("hiringCategory", role.hiringCategory);
        inputs.put("hiringSource", role.hiringSource);
        inputs.put("hiringReason", role.hiringReason);
        inputs.put("targetStartDate", role.targetStartDate == null ? null : role.targetStartDate.toString());
        if (req != null && req.extraInputs() != null) {
            inputs.put("extraInputs", req.extraInputs());
        }

        AiArtifact artifact = artifacts.requestArtifact(
                AiSubjectKind.ROLE, roleUuid, AiArtifactKind.ROLE_BRIEF, inputs, actor);
        return Response.status(Response.Status.ACCEPTED).entity(AiArtifactResponse.from(artifact)).build();
    }
}
