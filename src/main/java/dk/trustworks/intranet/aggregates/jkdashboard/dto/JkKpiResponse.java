package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * KPI response for the JK Team Dashboard header bar.
 * Contains 8 headline metrics plus an optional previous FY snapshot for YoY comparison.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JkKpiResponse {
    private int activeJkCount;
    private double totalSalaryCost;
    private double totalClientHours;
    private double billingCoveragePercent;
    private double revenuePerJkHour;
    private double revenueLeakageDkk;
    private int unassignedJkCount;
    private double teamGrowthPercent;
    /** Nullable — populated only when YoY comparison is computed */
    private JkKpiResponse previousFy;
}
