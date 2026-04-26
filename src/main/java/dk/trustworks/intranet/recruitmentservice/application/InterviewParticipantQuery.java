package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Thin Panache wrapper for participant aggregate queries.
 *
 * <p>Extracted as a collaborator so {@link InterviewLifecycleService} can be
 * unit-tested with Mockito without standing up the persistence layer.
 */
@ApplicationScoped
public class InterviewParticipantQuery {

    public long requiredScorerCount(String interviewUuid) {
        return InterviewParticipant.count(
            "interviewUuid = ?1 and isRequiredScorer = true", interviewUuid);
    }
}
