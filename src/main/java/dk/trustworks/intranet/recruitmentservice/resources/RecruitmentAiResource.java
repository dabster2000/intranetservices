package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.RecruitmentAiFlagsResponse;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentAiFeatureFlag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Module-level AI facts for the recruitment UI (P9, contract §6.1) — one
 * read endpoint reporting the literal companion toggles so every
 * recruitment page can decide which AI affordances to render.
 * <p>
 * Deliberately NO feature-flag guard and NO admin-bypass logic: this
 * endpoint <em>reports</em> the flags (booleans only, no configuration
 * detail leaks); the flag guards live on the feature endpoints
 * themselves.
 */
@Path("/recruitment/ai")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentAiResource {

    @Inject
    RecruitmentAiFeatureFlag aiFlags;

    /** The six recruitment.ai.* toggles, literally. */
    @GET
    @Path("/flags")
    public RecruitmentAiFlagsResponse flags() {
        return new RecruitmentAiFlagsResponse(
                aiFlags.isIntakeEnabled(),
                aiFlags.isBriefEnabled(),
                aiFlags.isReferralTriageEnabled(),
                aiFlags.isEmailComposerEnabled(),
                aiFlags.isWeeklyFunnelDigestEnabled(),
                aiFlags.isRejectionPatternsDigestEnabled());
    }
}
