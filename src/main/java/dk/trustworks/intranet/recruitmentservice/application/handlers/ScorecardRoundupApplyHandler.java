package dk.trustworks.intranet.recruitmentservice.application.handlers;

import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Apply-handler for {@link AiArtifactKind#SCORECARD_ROUNDUP}: <em>advisory only</em>.
 *
 * <p>The artifact's {@code output} JSON (consensus, dissent, risks, recommendation,
 * summaryParagraph) is exposed via the artifact GET endpoint and surfaced in the
 * round-up review UI. The team lead reads it and copies the relevant fields
 * manually into the round-up form ({@code POST /interviews/{uuid}/round-up}) — the
 * write path remains a deliberate human action.
 *
 * <p>This handler exists so the apply-handler dispatch in
 * {@link dk.trustworks.intranet.recruitmentservice.application.AiArtifactService}
 * has a CDI bean for SCORECARD_ROUNDUP (matching the established Slice 2 pattern
 * for CANDIDATE_SUMMARY which is also advisory). Registering an explicit no-op
 * handler is preferable to silently falling through, because it makes the
 * "advisory" intent visible at code-review time.
 */
@ApplicationScoped
public class ScorecardRoundupApplyHandler implements AiArtifactApplyHandler {

    @Override
    public boolean handles(AiArtifactKind kind) {
        return kind == AiArtifactKind.SCORECARD_ROUNDUP;
    }

    @Override
    public void apply(AiArtifact artifact, String overrideJson) {
        // Advisory only — no domain mutation. The round-up summary is exposed via
        // the artifact endpoint; the team lead copies it manually into the round-up
        // form (UI ergonomics).
    }
}
