package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * The blind-filtered scorecard view of one interview (P11, spec §5.3):
 * progress counters are always visible; {@code scorecards} contains only
 * what the blind rule admits for THIS viewer — their own card before
 * unlock, everything after (own submitted / all in / decision made).
 */
public record InterviewScorecardsResponse(
        String interviewUuid,
        boolean unlocked,
        boolean ownSubmitted,
        int submittedCount,
        int expectedCount,
        List<InterviewResponse.InterviewerInfo> interviewers,
        List<ScorecardResponse> scorecards
) {
}
