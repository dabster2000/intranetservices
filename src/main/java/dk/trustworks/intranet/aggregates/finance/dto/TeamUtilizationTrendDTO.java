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
        /** Team utilization: billable / netAvailable * 100; null if no available hours */
        Double teamUtilizationPercent,
        /** Company-wide utilization for same month; null if no data */
        Double companyUtilizationPercent
) {}
