package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.ScorecardRecommendation;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One visible scorecard (P11) — only ever returned to viewers the blind
 * rule admits (own card, or all cards once unlocked). Notes are read from
 * the {@code SCORECARD_SUBMITTED} event's pii at serve time (they are not
 * a table column).
 */
public record ScorecardResponse(
        String uuid,
        String interviewUuid,
        String interviewerUuid,
        String interviewerName,
        Map<String, Integer> scores,
        ScorecardRecommendation recommendation,
        LocalDateTime submittedAt,
        String notes
) {
}
