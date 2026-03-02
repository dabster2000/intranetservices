package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for company-wide break-even utilization aggregate.
 * All utilization values are ratios between 0.0 and 1.0.
 * Null values indicate missing data (zero hours or zero rate).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BreakEvenCompanyWideDTO {

    /**
     * Company-wide break-even utilization at 0% margin.
     * Formula: weighted_monthly_cost / (weighted_net_hours * weighted_billing_rate)
     * Null when hours or billing rate data is unavailable.
     */
    private Double breakEvenUtilization;

    /**
     * Company-wide break-even utilization at 15% margin target.
     * Formula: weighted_monthly_cost / (0.85 * weighted_net_hours * weighted_billing_rate)
     * Null when hours or billing rate data is unavailable.
     */
    private Double breakEvenUtilization15Margin;

    /**
     * Company-wide break-even utilization at 20% margin target.
     * Formula: weighted_monthly_cost / (0.80 * weighted_net_hours * weighted_billing_rate)
     * Null when hours or billing rate data is unavailable.
     */
    private Double breakEvenUtilization20Margin;

    /**
     * Company-wide actual utilization ratio (TTM).
     * Weighted average across career levels: SUM(billable_hours * count) / SUM(net_hours * count)
     */
    private double actualUtilization;

    /**
     * Weighted average billing rate across all consultants (DKK/hour).
     * Weighted by actual billable hours per career level.
     */
    private double avgBillingRate;

    /**
     * Weighted average monthly fully-loaded cost per FTE (DKK).
     * Weighted by consultant count per career level.
     */
    private double avgMonthlyCost;
}
