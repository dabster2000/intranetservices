package dk.trustworks.intranet.aggregates.finance.dto.cxo;

/**
 * One quarter's values for a single industry segment.
 *
 * @param quarterKey          calendar quarter key, e.g. {@code "2025-Q4"}
 * @param actualRevenueDkk    invoiced net revenue for full ACTUAL quarters;
 *                            {@code null} for the current and future quarters
 * @param actualToDateDkk     invoiced net revenue accumulated so far — only set
 *                            on the current (in-progress) quarter; {@code null}
 *                            elsewhere
 * @param budgetRevenueDkk    contracted budget revenue (budget hours × rate) for
 *                            the quarter — present on every quarter: past
 *                            quarters serve as budget-accuracy ghosts, current
 *                            and future quarters are the forecast line
 * @param weightedPipelineDkk probability-weighted unwon pipeline — only set on
 *                            FORECAST quarters; {@code null} elsewhere
 */
public record IndustryTrendPointDTO(
        String quarterKey,
        Double actualRevenueDkk,
        Double actualToDateDkk,
        Double budgetRevenueDkk,
        Double weightedPipelineDkk
) {
}
