package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.api.dto.AiArtifactResponse;
import dk.trustworks.intranet.recruitmentservice.application.AiArtifactService;
import dk.trustworks.intranet.recruitmentservice.application.InterviewService;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Per-interview AI trigger endpoints. The endpoints wire the scope contract here in
 * Phase E; the actual {@link AiArtifactService} support for {@code INTERVIEW_KIT} and
 * {@code SCORECARD_ROUNDUP} kinds (prompt + schema + flags) lands in Phase F (Task 28).
 * Until then these endpoints will surface 503 from {@code ensureKindEnabled}, which is
 * the intended behaviour while the AI pipeline is gated off.
 */
@Path("/api/recruitment/interviews/{uuid}/ai")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class InterviewAiResource {

    @Inject AiArtifactService aiArtifactService;
    @Inject InterviewService interviewService;
    @Inject RequestHeaderHolder header;

    @POST
    @Path("/kit")
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public Response triggerKit(@PathParam("uuid") String uuid) {
        String actor = header.getUserUuid();
        interviewService.findByIdOrThrow(uuid, actor); // throws 404 if not visible
        var artifact = aiArtifactService.requestArtifact(
            AiSubjectKind.INTERVIEW,
            uuid,
            AiArtifactKind.INTERVIEW_KIT,
            Map.of("interviewUuid", uuid),
            actor);
        return Response.accepted().entity(AiArtifactResponse.from(artifact)).build();
    }

    @POST
    @Path("/round-up-summary")
    @RolesAllowed({"recruitment:write"})
    public Response triggerRoundUpSummary(@PathParam("uuid") String uuid) {
        String actor = header.getUserUuid();
        interviewService.findByIdOrThrow(uuid, actor); // throws 404 if not visible
        var artifact = aiArtifactService.requestArtifact(
            AiSubjectKind.INTERVIEW,
            uuid,
            AiArtifactKind.SCORECARD_ROUNDUP,
            Map.of("interviewUuid", uuid),
            actor);
        return Response.accepted().entity(AiArtifactResponse.from(artifact)).build();
    }
}
