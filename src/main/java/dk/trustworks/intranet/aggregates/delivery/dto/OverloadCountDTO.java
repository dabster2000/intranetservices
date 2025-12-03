package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Overload Count (> 95% Utilization) KPI.
 * Represents count of consultants currently overloaded with high utilization.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverloadCountDTO {

    /**
     * Current overload count for last 4 weeks.
     * Count of users where (billable_hours / net_available_hours * 100) > 95%
     */
    private int currentOverloadCount;

    /**
     * Prior overload count for 4-week period before current.
     * Same calculation, offset by 4 weeks.
     */
    private int priorOverloadCount;

    /**
     * Absolute change in overload count.
     * Calculation: currentOverloadCount - priorOverloadCount
     * Example: If current is 12 and prior is 8, change is +4
     */
    private int change;
}
