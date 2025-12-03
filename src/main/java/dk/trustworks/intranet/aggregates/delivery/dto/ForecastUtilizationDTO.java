package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Forecast Utilization (Next 8 Weeks) KPI.
 * Represents forecasted utilization metrics for the next 8-week period.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ForecastUtilizationDTO {

    /**
     * Current forecast utilization percentage for next 8 weeks.
     * Calculation: (forecast billable hours / capacity hours) * 100
     * Rounded to 1 decimal place.
     */
    private double currentForecastPercent;

    /**
     * Prior 8-week forecast utilization percentage.
     * Same 8-week window from prior period (8-16 weeks ago).
     * Rounded to 1 decimal place.
     */
    private double priorForecastPercent;

    /**
     * Change in forecast utilization in percentage points.
     * Calculation: currentForecastPercent - priorForecastPercent
     * Example: If current is 80.5% and prior is 75.0%, change is +5.5 points
     * Rounded to 1 decimal place.
     */
    private double changePoints;

    /**
     * 8-week sparkline data showing weekly forecast utilization percentages.
     * Array of 8 values, week 1 (next week) first, ending at week 8.
     * Each value is rounded to 1 decimal place.
     */
    private double[] sparklineData;
}
