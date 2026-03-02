package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for per-career-level break-even utilization data.
 * Sourced directly from the fact_minimum_viable_rate view.
 * All utilization values are ratios between 0.0 and 1.0.
 * Null values indicate missing data (zero hours or zero rate).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BreakEvenCareerLevelDTO {

    /**
     * Career level enum string (e.g., "SENIOR", "JUNIOR", "MANAGER").
     * Matches the career_level column in fact_minimum_viable_rate.
     */
    private String careerLevel;

    /**
     * Human-readable label for the career level (e.g., "Senior Consultant", "Junior").
     */
    private String careerLevelLabel;

    /**
     * Break-even utilization at 0% margin for this career level.
     * Formula: total_monthly_cost_dkk / (avg_net_available_hours * avg_actual_billing_rate)
     * Null when avg_net_available_hours or avg_actual_billing_rate is zero.
     */
    private Double breakEvenUtilization;

    /**
     * Break-even utilization at 15% margin target for this career level.
     * Formula: total_monthly_cost_dkk / (0.85 * avg_net_available_hours * avg_actual_billing_rate)
     * Null when avg_net_available_hours or avg_actual_billing_rate is zero.
     */
    private Double breakEvenUtilization15Margin;

    /**
     * Break-even utilization at 20% margin target for this career level.
     * Formula: total_monthly_cost_dkk / (0.80 * avg_net_available_hours * avg_actual_billing_rate)
     * Null when avg_net_available_hours or avg_actual_billing_rate is zero.
     */
    private Double breakEvenUtilization20Margin;

    /**
     * Actual TTM utilization ratio for this career level.
     * Sourced from actual_utilization_ratio in fact_minimum_viable_rate.
     */
    private double actualUtilization;

    /**
     * Number of consultants at this career level used in the TTM calculation.
     */
    private int consultantCount;

    /**
     * Average actual billing rate for this career level (DKK/hour, TTM).
     * Sourced from avg_actual_billing_rate in fact_minimum_viable_rate.
     */
    private double avgBillingRate;

    /**
     * Average fully-loaded monthly cost per FTE for this career level (DKK).
     * Sourced from total_monthly_cost_dkk in fact_minimum_viable_rate.
     */
    private double avgMonthlyCost;
}
