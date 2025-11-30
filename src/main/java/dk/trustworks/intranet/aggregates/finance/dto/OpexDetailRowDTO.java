package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing detailed expense drill-down data with account-level granularity.
 * Includes budget comparison and variance analysis.
 *
 * Used by Cost Overview Dashboard to display Expense Detail Table (Grid).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpexDetailRowDTO {

    // Dimensions

    /** Company name (display label from companies table) */
    private String companyName;

    /** Cost center name (display label for cost_center_id) */
    private String costCenterName;

    /** Expense category name (display label for expense_category_id) */
    private String expenseCategoryName;

    /** Account code from accounting_accounts (e.g., "8722" for Telecommunications) */
    private String accountCode;

    /** Account name from accounting_accounts (e.g., "Telecommunications/Internet") */
    private String accountName;

    // Month identifier

    /** Month key in format YYYYMM (e.g., "202401") */
    private String monthKey;

    /** User-friendly month label (e.g., "Jan 2024") */
    private String monthLabel;

    // Metrics

    /** Actual OPEX amount in DKK (from fact_opex) */
    private double opexAmount;

    /** Budget amount in DKK (from fact_opex_budget, may be 0 if no budget data) */
    private double budgetAmount;

    /** Variance amount in DKK: opexAmount - budgetAmount (positive = over budget) */
    private double varianceAmount;

    /** Variance percentage: (varianceAmount / budgetAmount) * 100 (nullable if budget is 0) */
    private Double variancePercent;

    /** Number of invoice/GL entries contributing to this amount */
    private int invoiceCount;

    /** Flag indicating if this is a payroll-related expense (from is_payroll_flag) */
    private boolean isPayroll;

    // Fiscal year context

    /** Fiscal year (e.g., 2024 for FY2024/25 which runs Jul 2024 - Jun 2025) */
    private int fiscalYear;

    /** Fiscal month number (1-12, where 1 = July, 12 = June) */
    private int fiscalMonthNumber;
}
