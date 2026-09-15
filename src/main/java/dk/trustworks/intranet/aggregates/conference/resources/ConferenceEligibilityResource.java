package dk.trustworks.intranet.aggregates.conference.resources;

import dk.trustworks.intranet.aggregates.conference.services.ConferenceRecipientResolver;
import dk.trustworks.intranet.aggregates.conference.services.ConferenceUnsubscribeService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/knowledge/conferences/{conferenceUuid}/message-eligibility")
@RequestScoped
@RolesAllowed("conference:read")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ConferenceEligibilityResource {
    @Inject ConferenceRecipientResolver resolver;
    @Inject ConferenceUnsubscribeService service;

    @POST
    public Response eligibility(@PathParam("conferenceUuid") String conferenceUuid, EligibilityRequest request) {
        if (request == null) throw ConferenceUnsubscribeService.invalid("INVALID_RECIPIENT_SELECTION");
        var recipients = resolver.resolve(conferenceUuid, request.participantUuids());
        var suppressions = service.suppressionTimesFresh(conferenceUuid);
        int excluded = (int) recipients.stream().filter(r -> suppressions.containsKey(r.normalizedEmail())).count();
        return Response.ok(new EligibilityResponse(request.participantUuids().size(), recipients.size(),
                recipients.size() - excluded, excluded)).header("Cache-Control", "no-store").build();
    }

    public record EligibilityRequest(List<String> participantUuids) {}
    public record EligibilityResponse(int selectedParticipantCount, int distinctRecipientCount,
                                      int eligibleRecipientCount, int excludedUnsubscribedCount) {}
}
