package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Scorecard;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Thin Panache wrapper for scorecard aggregate queries.
 *
 * <p>Extracted as a collaborator so {@link InterviewLifecycleService} can be
 * unit-tested with Mockito without standing up the persistence layer.
 */
@ApplicationScoped
public class ScorecardQuery {

    public long submittedCount(String interviewUuid) {
        return Scorecard.count(
            "interviewUuid = ?1 and submittedAt is not null", interviewUuid);
    }

    /**
     * Counts scorecards submitted by interviewers who are flagged as required scorers
     * for the given interview. Used to gate SCORECARD_ROUNDUP auto-fire and round-up
     * transitions — non-required scorers' submissions must not satisfy the threshold.
     */
    public long submittedByRequiredCount(String interviewUuid) {
        return Scorecard.count(
            "interviewUuid = ?1 and submittedAt is not null and interviewerUserUuid in "
          + "(select p.userUuid from InterviewParticipant p where p.interviewUuid = ?1 and p.isRequiredScorer = true)",
            interviewUuid);
    }
}
