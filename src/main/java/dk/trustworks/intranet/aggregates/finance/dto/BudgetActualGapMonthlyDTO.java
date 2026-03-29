package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing one month of budget vs actual hours for a specific consultant.
 * Used by the Consultant Insights drill-down to show the monthly breakdown of budget-actual gap.
 *
 * budgetUtilizationPct = (budgetHours / netAvailableHours) × 100
 * actualUtilizationPct = (actualHours / netAvailableHours) × 100
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BudgetActualGapMonthlyDTO {

    /** Month key in YYYYMM format (e.g., "202501") */
    private String monthKey;

    /** Calendar year */
    private int year;

    /** Calendar month number (1-12) */
    private int monthNumber;

    /** Human-readable month label (e.g., "Jan 2025") */
    private String monthLabel;

    /** Budgeted hours for this month */
    private double budgetHours;

    /** Actual billable hours for this month */
    private double actualHours;

    /** Net available hours for this month (working hours minus leave/holidays) */
    private double netAvailableHours;

    /** Budget utilization: budgetHours / netAvailableHours × 100. Zero if net available is zero. */
    private double budgetUtilizationPct;

    /** Actual utilization: actualHours / netAvailableHours × 100. Zero if net available is zero. */
    private double actualUtilizationPct;
}
