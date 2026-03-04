package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for per-career-level cost economics data.
 * Sourced from the fact_minimum_viable_rate view.
 *
 * <p>All monetary values are in DKK per month per FTE.
 * Utilization values are ratios between 0.0 and 1.0.
 * Rate values are in DKK per hour.</p>
 *
 * <p>Used by the CXO Dashboard Cost Overview tab — Career Level Cost Structure section.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CareerLevelEconomicsItemDTO {

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
     * Number of consultants at this career level in the TTM window.
     */
    private int consultantCount;

    /**
     * Average gross monthly salary per FTE in DKK.
     * Sourced from avg_monthly_salary_dkk in fact_minimum_viable_rate.
     */
    private double avgMonthlySalary;

    /**
     * Employer pension contribution per FTE per month in DKK.
     * Sourced from employer_pension_dkk in fact_minimum_viable_rate.
     */
    private double employerPension;

    /**
     * Combined statutory labour-market costs per FTE per month in DKK.
     * Computed as atp_per_person_dkk + am_bidrag_per_person_dkk from fact_minimum_viable_rate.
     */
    private double statutoryCosts;

    /**
     * Employee benefits per FTE per month in DKK.
     * Sourced from benefit_per_person_dkk in fact_minimum_viable_rate.
     */
    private double benefits;

    /**
     * Staff (non-billable people) overhead allocation per FTE per month in DKK.
     * Sourced from staff_allocation_dkk in fact_minimum_viable_rate.
     */
    private double staffAllocation;

    /**
     * General OPEX overhead allocation per FTE per month in DKK.
     * Sourced from overhead_allocation_dkk in fact_minimum_viable_rate.
     */
    private double overheadAllocation;

    /**
     * Total fully-loaded monthly cost per FTE in DKK.
     * Sourced from total_monthly_cost_dkk in fact_minimum_viable_rate.
     */
    private double totalMonthlyCost;

    /**
     * Actual TTM utilization ratio for this career level (0.0–1.0).
     * Sourced from actual_utilization_ratio in fact_minimum_viable_rate.
     */
    private double actualUtilization;

    /**
     * Average actual billing rate in DKK per hour (TTM).
     * Sourced from avg_actual_billing_rate in fact_minimum_viable_rate.
     */
    private double avgBillingRate;

    /**
     * Break-even hourly rate needed to cover costs at the target utilization assumption in DKK.
     * Sourced from break_even_rate_target in fact_minimum_viable_rate.
     * Null when avg_net_available_hours or actual_utilization_ratio is zero.
     */
    private Double breakEvenRateTarget;

    /**
     * Minimum viable hourly rate to achieve 15% gross margin in DKK.
     * Sourced from min_rate_15pct_margin in fact_minimum_viable_rate.
     * Null when avg_net_available_hours or actual_utilization_ratio is zero.
     */
    private Double rateWith15Margin;

    /**
     * Minimum viable hourly rate to achieve 20% gross margin in DKK.
     * Sourced from min_rate_20pct_margin in fact_minimum_viable_rate.
     * Null when avg_net_available_hours or actual_utilization_ratio is zero.
     */
    private Double rateWith20Margin;

    /**
     * Difference between average actual billing rate and break-even rate target in DKK.
     * Positive means the career level is billing above break-even; negative means below.
     * Sourced from rate_buffer_dkk in fact_minimum_viable_rate.
     * Null when break-even rate cannot be computed.
     */
    private Double rateBuffer;

    /**
     * Minimum monthly salary across all consultants at this career level in DKK.
     * Sourced from min_monthly_salary_dkk in fact_minimum_viable_rate_mat.
     * Null when data is unavailable.
     */
    private Integer minMonthlySalary;

    /**
     * Maximum monthly salary across all consultants at this career level in DKK.
     * Sourced from max_monthly_salary_dkk in fact_minimum_viable_rate_mat.
     * Null when data is unavailable.
     */
    private Integer maxMonthlySalary;
}
