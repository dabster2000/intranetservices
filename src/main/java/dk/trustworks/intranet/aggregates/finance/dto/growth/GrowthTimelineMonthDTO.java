package dk.trustworks.intranet.aggregates.finance.dto.growth;

/**
 * One month on the Growth &amp; Scenarios timeline.
 *
 * <p>Revenue is GROUP external net revenue from live invoices (INVOICE +
 * PHANTOM − external credit notes, invoice-date basis, from 2017-07) —
 * intercompany invoices are eliminated, matching the Executive Summary's group
 * P&amp;L netting. Cost components are nullable because the GL-derived cost
 * facts ({@code fact_opex_distribution_mat}, {@code finance_details}) only
 * exist from 2024-07 — months before that carry {@code null} costs, and the
 * frontend renders the cost/EBITDA layer only where data exists.</p>
 *
 * <p>Headcount fields are point-in-time counts at month end from
 * {@code userstatus}: {@code consultants}/{@code students}/{@code staff} count
 * EMPLOYED people by type — ACTIVE plus leave statuses (maternity / paid /
 * non-pay leave), matching the HR &amp; People tab's headcount. {@code onLeave}
 * is the leave subset. {@code hires} and {@code terminations} count employment
 * transitions dated in the month.</p>
 *
 * <p>{@code bankBalance} (end-of-month) and {@code bankNetFlow} are the
 * combined liquidity across all three companies, from the imported e-conomic
 * bank flows ({@code fact_bank_flow_monthly}) — null until the first import
 * has run or for months before bank data starts.</p>
 */
public record GrowthTimelineMonthDTO(
        String monthKey,
        int year,
        int monthNumber,
        int fiscalYear,
        double netRevenue,
        Double opexCost,
        Double glDirectCost,
        Double totalCost,
        Double bankBalance,
        Double bankNetFlow,
        long consultants,
        long students,
        long staff,
        long onLeave,
        long hires,
        long terminations) {
}
