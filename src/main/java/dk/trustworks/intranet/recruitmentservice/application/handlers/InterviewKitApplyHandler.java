package dk.trustworks.intranet.recruitmentservice.application.handlers;

import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Apply-handler for {@link AiArtifactKind#INTERVIEW_KIT}: when a reviewed
 * interview-kit artifact is accepted (or edited+overridden), stamp the artifact
 * UUID onto {@link Interview#interviewKitArtifactUuid} <em>only if</em> the
 * pointer is currently null. Once an interview is bound to a kit it stays bound
 * — recruiters can regenerate kits, but the first accepted one wins to keep the
 * scorecard form pointing at a stable rubric.
 *
 * <p>The artifact's {@code output} JSON (questions, rubric, weights) stays in
 * the AI artifact row; the Interview row only carries the pointer. The
 * frontend renders the kit by fetching the artifact via
 * {@code GET /api/recruitment/ai-artifacts/{uuid}} and projecting through
 * {@link dk.trustworks.intranet.recruitmentservice.api.dto.InterviewKitOutput}.
 */
@ApplicationScoped
public class InterviewKitApplyHandler implements AiArtifactApplyHandler {

    @Override
    public boolean handles(AiArtifactKind kind) {
        return kind == AiArtifactKind.INTERVIEW_KIT;
    }

    @Override
    @Transactional
    public void apply(AiArtifact artifact, String overrideJson) {
        if (artifact == null || artifact.subjectUuid == null) return;
        Interview iv = Interview.findById(artifact.subjectUuid);
        if (iv == null) return;
        if (iv.interviewKitArtifactUuid == null) {
            iv.interviewKitArtifactUuid = artifact.uuid;
        }
    }
}
