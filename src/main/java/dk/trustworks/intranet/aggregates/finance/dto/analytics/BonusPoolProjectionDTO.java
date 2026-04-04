package dk.trustworks.intranet.aggregates.finance.dto.analytics;

import java.util.List;

/**
 * Bonus pool projection over a fiscal year.
 * Accumulates 8% of eligible salary month-by-month.
 */
public record BonusPoolProjectionDTO(
        /** Monthly bonus pool data points in chronological order. */
        List<BonusPoolMonthDTO> months,
        /** Current fiscal year being projected. */
        int currentFiscalYear,
        /** Projected total bonus pool in DKK at end of fiscal year. */
        double projectedTotalPoolDkk
) {
    /** A single month's bonus pool contribution and accumulation. */
    public record BonusPoolMonthDTO(
            String monthKey,
            int year,
            int monthNumber,
            String monthLabel,
            int fiscalYear,
            int fiscalMonthNumber,
            double totalEligibleSalaryDkk,
            double accumulatedBonusPoolDkk,
            double monthlyContributionDkk,
            int headcount
    ) {}
}
