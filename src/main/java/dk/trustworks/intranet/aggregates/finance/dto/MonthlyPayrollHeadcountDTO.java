package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing monthly payroll and headcount structure for combo chart.
 * Shows FTE breakdown (billable vs non-billable) and payroll as percentage of revenue.
 *
 * Used by Cost Overview Dashboard to display Payroll & Headcount Structure chart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyPayrollHeadcountDTO {

    /** User-friendly month label (e.g., "Jan 2024") */
    private String monthLabel;

    /** Month key in format YYYYMM (e.g., "202401") */
    private String monthKey;

    // FTE metrics (column series)

    /** Billable FTE count (consultants generating revenue) */
    private double billableFTE;

    /** Non-billable FTE count (admin, sales, management, support staff) */
    private double nonBillableFTE;

    // Payroll metrics (line series)

    /** Total payroll expense in DKK (from fact_opex where is_payroll_flag = 1) */
    private double totalPayroll;

    /** Total revenue in DKK (from fact_project_financials) */
    private double totalRevenue;

    /** Payroll as percentage of total revenue: (totalPayroll / totalRevenue) * 100 */
    private double payrollAsPercentOfRevenue;

    // Context

    /** Total FTE count: billableFTE + nonBillableFTE */
    private double totalFTE;
}
