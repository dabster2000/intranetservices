package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.ScorecardGuidance;

import java.util.List;

/**
 * Response of {@code GET /recruitment/scorecard-guidance} — the interview
 * framework as coaching, for the scorecard's hover help and the position
 * editor's subject picker.
 *
 * @param subjects  the standard subjects in interview order; the offer list
 *                  when building a template
 * @param legacy    subjects no longer offered but still live on positions
 *                  snapshotted before they were retired ({@code CULTURE_FIT}),
 *                  so in-flight interviews keep their help text
 * @param usageNote how to run a sitting without turning it into a checklist
 */
public record ScorecardGuidanceResponse(
        List<ScorecardGuidance> subjects,
        List<ScorecardGuidance> legacy,
        String usageNote
) {
}
