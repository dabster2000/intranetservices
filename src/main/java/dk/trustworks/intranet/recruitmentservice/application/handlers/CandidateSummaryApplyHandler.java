package dk.trustworks.intranet.recruitmentservice.application.handlers;

import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Apply-handler for {@link AiArtifactKind#CANDIDATE_SUMMARY}.
 *
 * <p>Per spec §9.2, candidate summaries are <strong>purely advisory</strong>:
 * accepting the artifact records the review state, but no candidate field is
 * patched. This no-op handler exists solely so the dispatch flow in
 * {@link dk.trustworks.intranet.recruitmentservice.application.AiArtifactService}
 * remains uniform across all artifact kinds.
 */
@ApplicationScoped
public class CandidateSummaryApplyHandler implements AiArtifactApplyHandler {

    @Override
    public boolean handles(AiArtifactKind kind) {
        return kind == AiArtifactKind.CANDIDATE_SUMMARY;
    }

    @Override
    public void apply(AiArtifact artifact, String overrideJson) {
        // Advisory artifact — intentionally no entity patch.
    }
}
