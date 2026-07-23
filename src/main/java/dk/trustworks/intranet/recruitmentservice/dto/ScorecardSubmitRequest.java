package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.ScorecardRecommendation;

import java.util.Map;

/**
 * Submit the caller's own blind scorecard (P11). Scores must cover exactly
 * the position's scorecard-template attribute codes, each 1..4. Free-text
 * notes go to the {@code SCORECARD_SUBMITTED} event's {@code pii} — never
 * to a table column (spec §4.1).
 *
 * @param scores         attribute code → 1..4, all template codes required
 * @param recommendation required overall recommendation
 * @param notes          optional free text, ≤ 2000 chars (event pii only)
 */
public record ScorecardSubmitRequest(
        Map<String, Integer> scores,
        ScorecardRecommendation recommendation,
        String notes
) {
}
