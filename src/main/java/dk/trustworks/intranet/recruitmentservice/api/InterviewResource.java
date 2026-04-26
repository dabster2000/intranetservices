package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.api.dto.*;
import dk.trustworks.intranet.recruitmentservice.application.*;
import dk.trustworks.intranet.recruitmentservice.domain.entities.*;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/recruitment/interviews")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class InterviewResource {

    @Inject InterviewService interviewService;
    @Inject RequestHeaderHolder header;

    @GET
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public Response list(@QueryParam("assigned_to_me") boolean assignedToMe,
                         @QueryParam("awaiting_evaluation") boolean awaitingEvaluation,
                         @QueryParam("upcoming") boolean upcoming,
                         @QueryParam("applicationUuid") String applicationUuid,
                         @QueryParam("roleUuid") String roleUuid,
                         @QueryParam("candidateUuid") String candidateUuid) {
        String actor = header.getUserUuid();
        var filter = new InterviewFilter(assignedToMe, awaitingEvaluation, upcoming,
            applicationUuid, roleUuid, candidateUuid, actor);
        List<Interview> ivs = interviewService.list(filter, actor);
        var responses = ivs.stream()
            .map(iv -> InterviewResponse.from(iv,
                InterviewParticipant.<InterviewParticipant>list("interviewUuid", iv.uuid)))
            .toList();
        return Response.ok(responses).build();
    }

    @POST
    @RolesAllowed({"recruitment:write"})
    public Response schedule(@Valid ScheduleInterviewRequest req) {
        String actor = header.getUserUuid();
        var participants = req.participants().stream()
            .map(p -> new ScheduleInterviewCommand.Participant(p.userUuid(), p.roleInInterview(), p.isRequiredScorer()))
            .toList();
        var cmd = new ScheduleInterviewCommand(
            req.applicationUuid(), req.roundType(), req.scheduledAt(), req.durationMinutes(),
            participants, req.prepNotes());
        Interview iv = interviewService.schedule(cmd, actor);
        var resp = InterviewResponse.from(iv,
            InterviewParticipant.<InterviewParticipant>list("interviewUuid", iv.uuid));
        return Response.created(java.net.URI.create("/api/recruitment/interviews/" + iv.uuid))
            .entity(resp).build();
    }

    @GET
    @Path("/{uuid}")
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public Response get(@PathParam("uuid") String uuid) {
        String actor = header.getUserUuid();
        Interview iv = interviewService.findByIdOrThrow(uuid, actor);
        var resp = InterviewResponse.from(iv,
            InterviewParticipant.<InterviewParticipant>list("interviewUuid", iv.uuid));
        return Response.ok(resp).build();
    }

    @PATCH
    @Path("/{uuid}")
    @RolesAllowed({"recruitment:write"})
    public Response patch(@PathParam("uuid") String uuid, @Valid UpdateInterviewRequest req) {
        String actor = header.getUserUuid();
        Interview iv;
        if (req.cancelReason() != null) {
            iv = interviewService.cancel(uuid, req.cancelReason(), actor);
        } else if (req.scheduledAt() != null) {
            iv = interviewService.reschedule(uuid, req.scheduledAt(), req.durationMinutes(), actor);
        } else {
            // Other fields (prepNotes, markHeld) — no-op for 3a; reserved for later patches.
            iv = interviewService.findByIdOrThrow(uuid, actor);
        }
        var resp = InterviewResponse.from(iv,
            InterviewParticipant.<InterviewParticipant>list("interviewUuid", iv.uuid));
        return Response.ok(resp).build();
    }

    @POST
    @Path("/{uuid}/round-up")
    @RolesAllowed({"recruitment:write"})
    public Response roundUp(@PathParam("uuid") String uuid, @Valid RoundUpRequest req) {
        String actor = header.getUserUuid();
        Interview iv = interviewService.recordRoundUp(uuid, req.decision(), req.summary(), actor);
        var resp = InterviewResponse.from(iv,
            InterviewParticipant.<InterviewParticipant>list("interviewUuid", iv.uuid));
        return Response.ok(resp).build();
    }
}
