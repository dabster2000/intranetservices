package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backend DTO for Backlog Coverage (Months) KPI
 *
 * Measures how many months of revenue are covered by signed backlog.
 * Formula: Coverage (Months) = Total Backlog Revenue / Average Monthly Revenue
 *
 * Provides:
 * - Current backlog coverage in months
 * - Prior period comparison (year-over-year)
 * - Change percentage for trend analysis
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BacklogCoverageDTO {

    /**
     * Total signed backlog revenue (DKK) as of today
     * Sum of future committed revenue from active contracts
     */
    private double totalBacklogRevenue;

    /**
     * Average monthly revenue (DKK) over trailing 12 months
     * Used as baseline for coverage calculation
     */
    private double averageMonthlyRevenue;

    /**
     * Current backlog coverage in months
     * Formula: totalBacklogRevenue / averageMonthlyRevenue
     */
    private double coverageMonths;

    /**
     * Prior period backlog coverage (same calculation one year ago)
     * For year-over-year comparison
     */
    private double priorCoverageMonths;

    /**
     * Change in coverage from prior period (percentage)
     * Example: 5.2 months → 6.1 months = +17.3% change
     */
    private double coverageChangePct;
}
