package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Bench FTE Count (< 50% Utilization) KPI.
 * Represents count of consultants currently on the bench with low utilization.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BenchCountDTO {

    /**
     * Current bench count for last 4 weeks.
     * Count of users where (billable_hours / net_available_hours * 100) < 50%
     */
    private int currentBenchCount;

    /**
     * Prior bench count for 4-week period before current.
     * Same calculation, offset by 4 weeks.
     */
    private int priorBenchCount;

    /**
     * Absolute change in bench count.
     * Calculation: currentBenchCount - priorBenchCount
     * Example: If current is 5 and prior is 8, change is -3
     */
    private int change;
}
