package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing monthly overhead efficiency metrics using TTM (Trailing Twelve Months) calculations.
 * Shows overhead per FTE and overhead per billable FTE trends.
 *
 * Used by Cost Overview Dashboard to display Overhead per FTE (Dual-Line) chart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyOverheadPerFTEDTO {

    /** User-friendly month label (e.g., "Jan 2024" - represents TTM ending this month) */
    private String monthLabel;

    /** Month key in format YYYYMM (e.g., "202401" - TTM ending this month) */
    private String monthKey;

    // TTM overhead (OPEX excluding payroll)

    /** Trailing 12-month non-payroll OPEX in DKK (sum of last 12 months where is_payroll_flag = 0) */
    private double ttmNonPayrollOpex;

    // TTM FTE averages

    /** Trailing 12-month average total FTE (average of last 12 months) */
    private double ttmAvgTotalFTE;

    /** Trailing 12-month average billable FTE (average of last 12 months) */
    private double ttmAvgBillableFTE;

    // Calculated metrics (line series values)

    /** Overhead per FTE in DKK: ttmNonPayrollOpex / ttmAvgTotalFTE */
    private double overheadPerFTE;

    /** Overhead per billable FTE in DKK: ttmNonPayrollOpex / ttmAvgBillableFTE */
    private double overheadPerBillableFTE;
}
