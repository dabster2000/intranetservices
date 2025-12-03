package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Average Project Margin (TTM) KPI.
 * Returns gross margin percentage across all projects and year-over-year comparison.
 *
 * Avg Project Margin = ((Total Revenue - Total Cost) / Total Revenue) * 100
 * - Based on fact_project_financials view
 * - Uses V118 deduplication (GROUP BY project_id, month_key)
 *
 * Use Case: Measures average profitability across project portfolio.
 * Higher percentage = better cost management and project margins.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AvgProjectMarginDTO {
    /**
     * Current TTM average project margin percentage (0-100)
     */
    private double currentTTMPercent;

    /**
     * Prior year TTM average project margin percentage (0-100)
     */
    private double priorTTMPercent;

    /**
     * Year-over-year change in percentage points (NOT percentage)
     * Example: 25% → 28% = +3.0 points
     */
    private double yoyChangePoints;

    /**
     * 12-month sparkline showing monthly average project margins
     * Array[0] = oldest month (12 months ago)
     * Array[11] = most recent month
     */
    private double[] sparklineData;
}
