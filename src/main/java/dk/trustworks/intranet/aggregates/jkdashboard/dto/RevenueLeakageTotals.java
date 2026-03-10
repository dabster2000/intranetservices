package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fiscal year totals for revenue leakage categories.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RevenueLeakageTotals {
    private double directlyBilled;
    private double merged;
    private double trulyLost;
    private double uncertain;
    private double billingCoveragePercent;
}
