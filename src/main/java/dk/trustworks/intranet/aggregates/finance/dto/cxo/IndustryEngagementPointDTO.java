package dk.trustworks.intranet.aggregates.finance.dto.cxo;

/**
 * One quarter's engagement values for a single industry segment.
 *
 * @param quarterKey          calendar quarter key, e.g. {@code "2026-Q2"}
 * @param avgRunningMonths    average running length (months from engagement
 *                            start through the quarter, capped at the quarter
 *                            end) of the engagements active in the quarter;
 *                            {@code null} when none are active
 * @param activeEngagements   consultant×client engagements active in the quarter
 * @param startedEngagements  engagements whose first active month falls in the
 *                            quarter
 * @param endedEngagements    engagements whose last active month falls in the
 *                            quarter and that have been silent long enough to
 *                            count as ended (recent quarters understate this
 *                            because the silence window has not elapsed yet)
 */
public record IndustryEngagementPointDTO(
        String quarterKey,
        Double avgRunningMonths,
        int activeEngagements,
        int startedEngagements,
        int endedEngagements
) {}
