package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.RecruitmentSlackFlagsResponse;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Module-level Slack facts for the recruitment surfaces (P13, Slack spec
 * §6.1) — one read endpoint reporting the literal companion toggles.
 * Consumed by the BFF's inbound middleware (the master-gate check reads
 * {@code interactivity} per request, no cache) and available to any
 * recruitment page that needs to know which Slack affordances exist.
 * <p>
 * Deliberately NO feature-flag guard and NO admin-bypass logic: this
 * endpoint <em>reports</em> the flags (booleans only, no configuration
 * detail leaks); enforcement lives where the features execute — the P9
 * {@code RecruitmentAiResource} precedent.
 */
@Path("/recruitment/slack")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentSlackResource {

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    /** The twelve recruitment.slack.* toggles, literally. */
    @GET
    @Path("/flags")
    public RecruitmentSlackFlagsResponse flags() {
        return new RecruitmentSlackFlagsResponse(
                slackFlags.isInteractivityEnabled(),
                slackFlags.isCardsEnabled(),
                slackFlags.isPartnerChannelsEnabled(),
                slackFlags.isReferEnabled(),
                slackFlags.isTriageActionsEnabled(),
                slackFlags.isCaptureEnabled(),
                slackFlags.isLookupEnabled(),
                slackFlags.isScorecardEnabled(),
                slackFlags.isAppHomeEnabled(),
                slackFlags.isMorningBriefEnabled(),
                slackFlags.isDpoDigestEnabled(),
                slackFlags.isAssistantEnabled());
    }
}
