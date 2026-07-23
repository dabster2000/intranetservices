package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * The debrief view of one application (P11, spec §5.3): every non-cancelled
 * ROUND interview with its blind-filtered scorecards, round order. The
 * side-by-side comparison unlocks per interview when all scorecards are in
 * (or after the decision, for owner/recruiter).
 */
public record DebriefResponse(
        String applicationUuid,
        List<DebriefEntry> rounds
) {

    /** One round: the interview plus its blind-filtered scorecard view. */
    public record DebriefEntry(
            InterviewResponse interview,
            InterviewScorecardsResponse scorecards
    ) {
    }
}
