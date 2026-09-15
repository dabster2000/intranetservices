package dk.trustworks.intranet.aggregates.crm.calendar.resources;

import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarCandidateService;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarCandidateService.CandidateDTO;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarCandidateService.ReviewDTO;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarCandidateService.ReviewRequest;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.UUID;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Path("/account-calendar-candidates")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed("accounts:read")
public class CalendarCandidateResource {
    @Inject CalendarCandidateService candidates;
    @Inject RequestHeaderHolder headers;

    @GET
    @Path("/{clientUuid}")
    public List<CandidateDTO> read(@PathParam("clientUuid") String clientUuid) {
        return candidates.forClient(clientUuid);
    }

    @POST
    @Path("/{clientUuid}/{candidateUuid}/review")
    @RolesAllowed("accounts:write")
    public ReviewDTO review(@PathParam("clientUuid") String clientUuid,
                            @PathParam("candidateUuid") String candidateUuid, ReviewRequest request) {
        ReviewDTO result = candidates.review(clientUuid, candidateUuid, request, requireActor());
        if ("STARRED".equals(result.status())) {
            try {
                candidates.requestFullReadForCandidate(clientUuid, candidateUuid);
            } catch (RuntimeException failure) {
                // The review already committed. A derived refresh failure must not claim
                // that the human's star failed; the weekly full read remains a fallback.
                log.warnf("Calendar review recovery request failed: client=%s candidate=%s code=REFRESH_FAILED",
                        clientUuid, candidateUuid);
            }
        }
        return result;
    }

    private String requireActor() {
        if (headers.isImpersonated()) throw new WebApplicationException("Review as yourself", 403);
        String actor = headers.getUserUuid();
        try { UUID.fromString(actor == null ? "" : actor.trim()); }
        catch (IllegalArgumentException e) { throw new WebApplicationException("A valid X-Requested-By is required", 400); }
        return actor.trim();
    }
}
