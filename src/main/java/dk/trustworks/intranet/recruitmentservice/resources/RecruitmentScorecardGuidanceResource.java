package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.ScorecardGuidanceResponse;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardGuidanceCatalog;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.jbosslog.JBossLog;

/**
 * The interview framework as interviewer-facing coaching — what each scorecard
 * subject tests, the probes that surface it, and what a 1, 2, 3 and 4 look
 * like (spec §2.2).
 *
 * <h3>Why its own endpoint</h3>
 * Guidance is resolved by attribute code at render time rather than
 * snapshotted onto positions, so that sharpening an anchor reaches the
 * interview happening tomorrow on a position created last year. Two surfaces
 * consume it — the scorecard itself (hover help on each subject) and the
 * position editor (a preview of what interviewers will be asked) — so it is
 * served once and cached, not duplicated into every position payload.
 *
 * <h3>Security</h3>
 * Static reference content with no candidate, employee or position data in
 * it, but it describes how Trustworks hires and is therefore internal:
 * {@code recruitment:read}, behind the same
 * {@code recruitment.pipeline.enabled} flag gate as the position endpoints.
 */
@JBossLog
@Path("/recruitment/scorecard-guidance")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentScorecardGuidanceResource {

    private static final String ADMIN_WILDCARD = "admin:*";

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    ScopeContext scopeContext;

    /**
     * @return every standard subject in interview order, the legacy
     *         {@code CULTURE_FIT} entry that older positions still score on,
     *         and the note on how to run a six-subject sitting
     */
    @GET
    public ScorecardGuidanceResponse guidance() {
        enforceFlag();
        return new ScorecardGuidanceResponse(
                ScorecardGuidanceCatalog.standard(),
                ScorecardGuidanceCatalog.forCode(ScorecardGuidanceCatalog.CULTURE_FIT_LEGACY_CODE)
                        .stream().toList(),
                ScorecardGuidanceCatalog.USAGE_NOTE);
    }

    private void enforceFlag() {
        if (featureFlag.isPipelineEnabled()) {
            return;
        }
        if (scopeContext.hasScope(ADMIN_WILDCARD)) {
            return;
        }
        throw new NotFoundException("Resource not found");
    }
}
