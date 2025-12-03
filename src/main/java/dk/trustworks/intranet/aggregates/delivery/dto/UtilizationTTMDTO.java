package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Company Billable Utilization (TTM) KPI.
 * Represents company-wide utilization metrics over a trailing 12-month window.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UtilizationTTMDTO {

    /**
     * Current trailing 12-month utilization percentage.
     * Calculation: (total billable hours / total net available hours) * 100
     * Rounded to 1 decimal place.
     */
    private double currentTTMPercent;

    /**
     * Prior year trailing 12-month utilization percentage.
     * Same 12-month window, offset by 1 year.
     * Rounded to 1 decimal place.
     */
    private double priorTTMPercent;

    /**
     * Year-over-year change in percentage points (NOT percentage change).
     * Calculation: currentTTMPercent - priorTTMPercent
     * Example: If current is 75.5% and prior is 72.0%, change is +3.5 points
     * Rounded to 1 decimal place.
     */
    private double yoyChangePoints;

    /**
     * 12-month sparkline data showing monthly utilization percentages.
     * Array of 12 values, oldest month first, ending at the query date.
     * Each value is rounded to 1 decimal place.
     */
    private double[] sparklineData;
}
