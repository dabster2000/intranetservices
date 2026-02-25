package dk.trustworks.intranet.aggregates.finance.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing one month of accumulated EBITDA data for the fiscal year chart.
 * Used by CxO Executive Dashboard to display Expected Accumulated EBITDA (FY).
 *
 * EBITDA = Revenue - monthlyDirectCostDkk - monthlyInternalInvoiceCostDkk - monthlyOpexDkk
 *
 * Past months use actuals from fact_project_financials_mat + fact_opex + fact_internal_invoice_cost_mat.
 * Future months use backlog revenue (fact_backlog) with TTM gross margin to estimate
 * direct costs, and average monthly OPEX (TTM) for opex estimate.
 * Internal invoice cost is not forecast for future months (set to 0.0).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyAccumulatedEbitdaDTO {

    /** Month key in format YYYYMM (e.g., "202507") */
    private String monthKey;

    /** Calendar year (e.g., 2025) */
    private int year;

    /** Calendar month number (1-12) */
    private int monthNumber;

    /** User-friendly month label (e.g., "Jul 2025") */
    private String monthLabel;

    /**
     * Fiscal month number within the fiscal year (1=Jul, 2=Aug, ..., 6=Dec, 7=Jan, ..., 12=Jun).
     */
    private int fiscalMonthNumber;

    /**
     * Revenue for this month in DKK.
     * Past months: actual recognized_revenue_dkk from fact_project_financials_mat.
     * Future months: backlog_revenue_dkk from fact_backlog.
     */
    private double monthlyRevenueDkk;

    /**
     * Direct delivery cost for this month in DKK.
     * Past months: actual direct_delivery_cost_dkk from fact_project_financials_mat.
     * Future months: estimated as backlog_revenue × (1 - ttm_gross_margin_pct / 100).
     */
    private double monthlyDirectCostDkk;

    /**
     * Internal invoice cost for this month in DKK.
     * Past months: actual internal_invoice_cost_dkk from fact_internal_invoice_cost_mat.
     * This captures intercompany (INTERNAL) invoice costs charged to the debtor company
     * (QUEUED invoices sourced from invoice items; CREATED invoices sourced from GL accounts).
     * Future months: 0.0 (internal invoice costs are not forecast).
     * Can be negative for reversal months (SUM, not ABS).
     */
    private double monthlyInternalInvoiceCostDkk;

    /**
     * Operating expenses for this month in DKK.
     * Past months: actual opex_amount_dkk from fact_opex.
     * Future months: average monthly OPEX from TTM period.
     */
    private double monthlyOpexDkk;

    /**
     * Monthly EBITDA = monthlyRevenueDkk - monthlyDirectCostDkk - monthlyInternalInvoiceCostDkk - monthlyOpexDkk.
     * Can be negative.
     */
    private double monthlyEbitdaDkk;

    /** Running accumulated sum of EBITDA from fiscal month 1 through this month */
    private double accumulatedEbitdaDkk;

    /**
     * True if this month uses actuals (past month with data).
     * False if this month uses projected/estimated figures (future month, or current month without full data).
     */
    @JsonProperty("isActual")
    private boolean isActual;
}
