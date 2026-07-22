package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One unsolicited applicant awaiting routing on
 * {@code GET /recruitment/candidates/triage-queue} (ATS plan §P6, the P5
 * carry-over): a public-form candidate with no application yet. The
 * desired practice comes from the candidate's {@code source_detail} (P5
 * keys); {@link #answers} are the candidate-scoped form answers.
 */
public record TriageQueueCandidate(
        String uuid,
        String firstName,
        String lastName,
        String email,
        LocalDateTime createdAt,
        String desiredPracticeUuid,
        String desiredPracticeName,
        List<TriageQueueAnswer> answers
) {
}
