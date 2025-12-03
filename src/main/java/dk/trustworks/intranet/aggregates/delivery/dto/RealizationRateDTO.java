package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Realization Rate (TTM) KPI.
 * Returns realization rate percentage and year-over-year comparison.
 *
 * Realization Rate = (Billed Value / Expected Value) * 100
 * - Billed Value = SUM(workduration × actual_rate) where rate > 0
 * - Expected Value = SUM(workduration × contract_rate)
 *
 * Use Case: Measures billing efficiency - how much of potential value is actually billed to clients.
 * Higher percentage = better realization of billable hours.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RealizationRateDTO {
    /**
     * Current TTM realization rate percentage (0-100+)
     */
    private double currentTTMPercent;

    /**
     * Prior year TTM realization rate percentage (0-100+)
     */
    private double priorTTMPercent;

    /**
     * Year-over-year change in percentage points (NOT percentage)
     * Example: 85% → 90% = +5.0 points
     */
    private double yoyChangePoints;

    /**
     * 12-month sparkline showing monthly realization rates
     * Array[0] = oldest month (12 months ago)
     * Array[11] = most recent month
     */
    private double[] sparklineData;
}
