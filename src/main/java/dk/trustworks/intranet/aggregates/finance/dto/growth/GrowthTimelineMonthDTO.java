package dk.trustworks.intranet.aggregates.finance.dto.growth;

/**
 * One month on the Growth &amp; Scenarios timeline.
 *
 * <p>Revenue is canonical invoice-based net revenue from
 * {@code fact_company_revenue_mat} (available from 2017-07). Cost components are
 * nullable because the GL-derived cost facts ({@code fact_opex_distribution_mat},
 * {@code finance_details}) only exist from 2024-07 — months before that carry
 * {@code null} costs, and the frontend renders the cost/EBITDA layer only where
 * data exists.</p>
 *
 * <p>Headcount fields are point-in-time counts at month end from
 * {@code userstatus}: {@code consultants}/{@code students}/{@code staff} count
 * {@code status='ACTIVE'} by type (matching the HR headcount-growth chart), and
 * {@code onLeave} counts employees of the same three types whose current status
 * is a leave status (maternity / paid / non-pay leave). {@code hires} and
 * {@code terminations} count employment transitions dated in the month.</p>
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
        long consultants,
        long students,
        long staff,
        long onLeave,
        long hires,
        long terminations) {
}
