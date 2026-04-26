package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.api.dto.*;
import dk.trustworks.intranet.recruitmentservice.application.ScorecardService;
import dk.trustworks.intranet.recruitmentservice.application.SubmitScorecardCommand;
import dk.trustworks.intranet.recruitmentservice.domain.entities.*;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/recruitment/interviews/{interviewUuid}/scorecards")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ScorecardResource {

    @Inject ScorecardService scorecardService;
    @Inject ScopeContext scopeContext;
    @Inject RequestHeaderHolder header;

    @POST
    @RolesAllowed({"recruitment:interview"})
    public Response submit(@PathParam("interviewUuid") String interviewUuid,
                           @Valid SubmitScorecardRequest req) {
        String actor = header.getUserUuid();
        var cmd = new SubmitScorecardCommand(
            req.practiceSkillFit(), req.careerLevelFit(),
            req.consultingCommunication(), req.clientFacingMaturity(),
            req.cultureValueFit(), req.deliveryTrackPotential(),
            req.recommendation(), req.notes(), req.privateNotes(), req.concerns());
        Scorecard sc = scorecardService.submit(interviewUuid, cmd, actor);
        return Response.created(java.net.URI.create(
            "/api/recruitment/interviews/" + interviewUuid + "/scorecards/" + sc.uuid))
            .entity(ScorecardResponse.from(sc, List.of())).build();
    }

    @GET
    @RolesAllowed({"recruitment:interview", "recruitment:admin"})
    public Response list(@PathParam("interviewUuid") String interviewUuid) {
        String actor = header.getUserUuid();
        boolean admin = scopeContext.hasAnyScope("recruitment:admin");
        var result = scorecardService.listForInterview(interviewUuid, actor, admin);
        if (result.requiresSubmission()) {
            return Response.status(403)
                .entity(Map.of("error", "submit-first", "errorCode", "requires_submission",
                    "status", 403))
                .build();
        }
        var ownDto = result.ownScorecard() == null ? null
            : ScorecardResponse.from(result.ownScorecard(),
                ScorecardAmendment.<ScorecardAmendment>list("scorecardUuid", result.ownScorecard().uuid)
                    .stream().map(ScorecardAmendmentResponse::from).toList());
        var othersDto = result.others().stream()
            .map(s -> ScorecardResponse.from(s,
                ScorecardAmendment.<ScorecardAmendment>list("scorecardUuid", s.uuid)
                    .stream().map(ScorecardAmendmentResponse::from).toList()))
            .toList();
        // Use HashMap to allow null values (Map.of rejects null ownScorecard).
        Map<String, Object> body = new HashMap<>();
        body.put("ownScorecard", ownDto);
        body.put("others", othersDto);
        return Response.ok(body).build();
    }

    @POST
    @Path("/{scorecardUuid}/amendments")
    @RolesAllowed({"recruitment:interview"})
    public Response amend(@PathParam("interviewUuid") String interviewUuid,
                          @PathParam("scorecardUuid") String scorecardUuid,
                          @Valid AmendScorecardRequest req) {
        String actor = header.getUserUuid();
        ScorecardAmendment a = scorecardService.amend(scorecardUuid, req.body(), actor);
        return Response.created(java.net.URI.create(
            "/api/recruitment/interviews/" + interviewUuid + "/scorecards/" + scorecardUuid + "/amendments/" + a.uuid))
            .entity(ScorecardAmendmentResponse.from(a)).build();
    }

    @POST
    @Path("/{scorecardUuid}/reopen")
    @RolesAllowed({"recruitment:admin"})
    public Response reopen(@PathParam("interviewUuid") String interviewUuid,
                           @PathParam("scorecardUuid") String scorecardUuid,
                           @Valid ReopenScorecardRequest req) {
        String actor = header.getUserUuid();
        Scorecard sc = scorecardService.reopen(scorecardUuid, req.reason(), actor);
        return Response.ok(ScorecardResponse.from(sc, List.of())).build();
    }
}
