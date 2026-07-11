package dk.trustworks.intranet.aggregates.bonus.individual.model;

/**
 * The curated set of business facts an individual bonus rule can be evaluated against.
 * <p>
 * Each value is one arm of a hard-coded switch in {@code IndividualBonusBasisResolver} — the
 * evaluator NEVER reflectively resolves a field name from the spec (no arbitrary-code surface).
 * "READY" labels are backed by data that already exists per-employee; {@code COMPANY_*} labels are
 * company-grain and not yet wired (they throw {@link UnsupportedOperationException} when resolved).
 */
public enum Basis {
    /** The employee's own registered / "faktureret" production (RevenueService, work_full). */
    OWN_INVOICED_REVENUE,
    /** Billable hours divided by net available hours (fact_user_day). */
    UTILIZATION,
    /** Raw billable hours (fact_user_day). */
    BILLABLE_HOURS,
    /** Actual registered amount divided by budgeted revenue (bi_budget_per_day). */
    BUDGET_ATTAINMENT,
    /** Average monthly gross salary over the window (fact_user_day). */
    SALARY,
    /** Flat amount — no fact feed, no tier table; resolved by the schedule. */
    FIXED_AMOUNT,
    /** Company-grain invoiced revenue — not yet wired. */
    COMPANY_INVOICED_REVENUE,
    /** Company-grain utilization — not yet wired. */
    COMPANY_UTILIZATION,
    /** Company result — not yet wired. */
    COMPANY_EBITDA
}
