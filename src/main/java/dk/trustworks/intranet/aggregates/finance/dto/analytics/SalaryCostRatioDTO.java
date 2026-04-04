package dk.trustworks.intranet.aggregates.finance.dto.analytics;

/**
 * Monthly salary-to-revenue ratio data point.
 * Used by the salary-cost-ratio endpoint for trailing 18-month analysis.
 */
public record SalaryCostRatioDTO(
        /** Month key in YYYYMM format (e.g., "202601"). */
        String monthKey,
        /** Calendar year. */
        int year,
        /** Calendar month (1-12). */
        int monthNumber,
        /** Display label (e.g., "Jan 2025"). */
        String monthLabel,
        /** Total consultant salary for the month in DKK. */
        double totalSalaryDkk,
        /** Total net revenue for the month in DKK. */
        double totalRevenueDkk,
        /** Salary as percentage of revenue, null if revenue is zero. */
        Double salaryRatioPct
) {}
