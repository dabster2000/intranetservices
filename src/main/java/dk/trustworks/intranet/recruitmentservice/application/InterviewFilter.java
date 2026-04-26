package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Scorecard;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;

import java.time.LocalDateTime;

/**
 * In-memory filter applied to {@link Interview} rows after record-level visibility
 * is enforced. Tracks the actor UUID so {@code assignedToMe} filtering can issue
 * the participant lookup.
 */
public record InterviewFilter(
        boolean assignedToMe,
        boolean awaitingEvaluation,
        boolean upcoming,
        String applicationUuid,
        String roleUuid,
        String candidateUuid,
        String actorUuid
) {
    public boolean matches(Interview iv) {
        if (applicationUuid != null && !applicationUuid.equals(iv.applicationUuid)) return false;
        if (assignedToMe) {
            long hits = InterviewParticipant.count(
                    "interviewUuid = ?1 and userUuid = ?2", iv.uuid, actorUuid);
            if (hits == 0) return false;
        }
        if (awaitingEvaluation) {
            if (iv.status != InterviewStatus.HELD) return false;
            long required = InterviewParticipant.count(
                    "interviewUuid = ?1 and isRequiredScorer = true", iv.uuid);
            long submitted = Scorecard.count(
                    "interviewUuid = ?1 and submittedAt is not null", iv.uuid);
            if (submitted >= required) return false;
        }
        if (upcoming) {
            if (iv.status != InterviewStatus.SCHEDULED) return false;
            if (iv.scheduledAt == null) return false;
            LocalDateTime horizon = LocalDateTime.now().plusDays(14);
            if (iv.scheduledAt.isAfter(horizon)) return false;
        }
        return true;
    }
}
