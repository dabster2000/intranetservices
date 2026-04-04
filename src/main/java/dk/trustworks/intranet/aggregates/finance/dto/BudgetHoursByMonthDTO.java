package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Budget hours aggregated by month.
 * Used by the Executive utilization trend chart for budget utilization overlay.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BudgetHoursByMonthDTO {

    /** Month key in format YYYYMM (e.g., "202501") */
    private String monthKey;

    /** Total budget hours across all matching service lines for this month */
    private double totalBudget;
}
