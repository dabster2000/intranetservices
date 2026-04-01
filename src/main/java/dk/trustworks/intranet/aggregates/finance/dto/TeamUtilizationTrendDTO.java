package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * Monthly utilization data point for a team's utilization trend chart.
 * Includes the team's utilization and the company-wide average for comparison.
 */
public record TeamUtilizationTrendDTO(
        /** Month key in format YYYYMM (e.g., "202501") */
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        /** Team billable hours for the month */
        double billableHours,
        /** Team net available hours for the month */
        double netAvailableHours,
        /** Gross utilization: billable / grossAvailable * 100; null if no gross available hours */
        Double grossUtilizationPercent,
        /** Net/actual utilization: billable / netAvailable * 100; null if no net available hours */
        Double teamUtilizationPercent,
        /** Budget utilization: budgetHours / netAvailable * 100; null if no budget data */
        Double budgetUtilizationPercent,
        /** Contract fulfillment: billable / budgetHours * 100; null if no budget data */
        Double contractFulfillmentPercent,
        /** Company-wide utilization for same month; null if no data */
        Double companyUtilizationPercent
) {}
