package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing the gap between budgeted and actual billable hours per consultant over TTM.
 * Used by the Consultant Insights tab to identify consultants driving the budget-actual gap.
 *
 * Gap = budgetHours - actualHours (positive means underperformance vs budget).
 * Fulfillment % = (actualHours / budgetHours) × 100.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BudgetActualGapDTO {

    /** User UUID */
    private String userId;

    /** Consultant first name */
    private String firstname;

    /** Consultant last name */
    private String lastname;

    /** Practice code (PM, BA, SA, CYB, DEV) */
    private String practice;

    /** Total budgeted hours in the TTM window */
    private double budgetHours;

    /** Total actual billable hours in the TTM window */
    private double actualHours;

    /** Gap = budgetHours - actualHours (positive = underperformance) */
    private double gapHours;

    /** Fulfillment percentage: actual / budget × 100. Null if budget is zero. */
    private Double fulfillmentPercent;
}
