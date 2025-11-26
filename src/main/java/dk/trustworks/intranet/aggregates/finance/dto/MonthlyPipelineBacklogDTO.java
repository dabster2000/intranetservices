package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Chart C: Pipeline, Backlog & Target trend data.
 * Represents monthly aggregated data for forward-looking coverage analysis.
 *
 * @see dk.trustworks.intranet.aggregates.finance.services.CxoFinanceService#getPipelineBacklogTrend
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MonthlyPipelineBacklogDTO {

    /**
     * Month key in YYYYMM format (e.g., "202512" for December 2025)
     */
    private String monthKey;

    /**
     * Calendar year (e.g., 2025)
     */
    private int year;

    /**
     * Month number 1-12 (1 = January, 12 = December)
     */
    private int monthNumber;

    /**
     * Human-readable month label (e.g., "Dec 2025")
     */
    private String monthLabel;

    /**
     * Weighted pipeline value in DKK.
     * Calculated as: SUM(expected_revenue_dkk * probability_pct / 100)
     * Only includes open opportunities (excludes WON, LOST stages)
     */
    private double weightedPipelineDkk;

    /**
     * Signed backlog value in DKK.
     * Represents revenue from active contracts that is signed but not yet delivered.
     */
    private double backlogDkk;

    /**
     * Revenue target/budget value in DKK.
     * Monthly budget target from fact_revenue_budget view.
     * Note: Budget data has no client-level granularity.
     */
    private double targetDkk;

    /**
     * Booked coverage percentage: (backlog / target) * 100
     * Null if target is zero.
     */
    private Double bookedCoveragePct;

    /**
     * Total coverage percentage: ((backlog + pipeline) / target) * 100
     * Null if target is zero.
     */
    private Double totalCoveragePct;
}
