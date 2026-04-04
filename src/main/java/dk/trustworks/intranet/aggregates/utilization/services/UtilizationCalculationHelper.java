package dk.trustworks.intranet.aggregates.utilization.services;

import java.time.LocalDate;
import java.time.Month;
import java.util.Set;

/**
 * Shared utility for all utilization calculations across the application.
 *
 * <p>This class is the single source of truth for:</p>
 * <ul>
 *   <li>The utilization percentage formula: {@code billable / netAvailable * 100}</li>
 *   <li>Fiscal year boundary computation (July 1 – June 30)</li>
 *   <li>Month key formatting (YYYYMM)</li>
 *   <li>Trailing-twelve-month (TTM) boundary calculation</li>
 *   <li>Canonical filter constants (ACTIVE status, CONSULTANT type, billable practices)</li>
 * </ul>
 *
 * <p><b>Business context:</b> Utilization = percentage of a consultant's available hours
 * that are spent on billable client work. This metric drives staffing decisions,
 * bonus eligibility, and financial reporting across all dashboards.</p>
 *
 * <p><b>Data source:</b> All utilization queries MUST use {@code fact_user_day} as the
 * single source of truth. The materialized view {@code fact_user_utilization_mat}
 * has been eliminated (see V272 migration).</p>
 *
 * @see UtilizationService
 * @see dk.trustworks.intranet.aggregates.finance.services.CxoFinanceService
 * @see dk.trustworks.intranet.aggregates.finance.services.TeamDashboardService
 * @see dk.trustworks.intranet.aggregates.finance.services.ConsultantInsightsService
 */
public final class UtilizationCalculationHelper {

    private UtilizationCalculationHelper() {}

    // ── Filter constants ──────────────────────────────────────────────────

    /**
     * Billable practice codes used by executive and CXO dashboards.
     * The dashboard (company-wide) intentionally does NOT filter by practice.
     * The team dashboard filters by team membership instead.
     *
     * <p><b>Note:</b> {@code user.practice} is current-state only (no temporal history).
     * Practice-filtered reports attribute all historical data to the consultant's
     * current practice assignment. As of 2026-04, no practice changes have occurred
     * in production data, so this is a documented limitation, not an active bug.</p>
     */
    public static final Set<String> BILLABLE_PRACTICES = Set.of("PM", "BA", "CYB", "DEV", "SA");

    /** Canonical consultant type filter. Only billable consultants contribute to utilization. */
    public static final String CONSULTANT_TYPE = "CONSULTANT";

    /** Canonical status filter. Only ACTIVE consultants contribute to utilization.
     *  PREBOARDING, NON_PAY_LEAVE, PAID_LEAVE, INACTIVE, and TERMINATED are excluded. */
    public static final String ACTIVE_STATUS = "ACTIVE";

    /**
     * SQL WHERE clause fragment for the canonical utilization population filter.
     * Use in native queries: {@code sql.append(ACTIVE_CONSULTANT_FILTER)}.
     *
     * <p>Applies to {@code fact_user_day} columns: {@code consultant_type} and {@code status_type}.
     * Both columns are resolved temporally by {@code sp_recalculate_availability} —
     * they reflect the consultant's status as-of each {@code document_date}.</p>
     */
    public static final String ACTIVE_CONSULTANT_FILTER =
            "consultant_type = 'CONSULTANT' AND status_type = 'ACTIVE'";

    /**
     * SQL subquery to resolve the latest userstatus for a user as of today.
     * Used in ranking/insights queries that need to filter out TERMINATED/PREBOARDING
     * consultants based on their current (not historical) status.
     *
     * <p>Bind the user UUID column as {@code u.uuid} in the outer query.</p>
     */
    public static final String LATEST_STATUS_SUBQUERY =
            "us.statusdate = (" +
            "  SELECT MAX(us2.statusdate) FROM userstatus us2" +
            "  WHERE us2.useruuid = u.uuid AND us2.statusdate <= CURDATE()" +
            ")";

    // ── Utilization formula ───────────────────────────────────────────────

    /**
     * Calculates utilization as a percentage (0–100+).
     *
     * <p><b>Formula:</b> {@code (billableHours / netAvailableHours) * 100}</p>
     *
     * <p><b>Business rule:</b> Never average pre-computed percentages across months.
     * Always sum the underlying hours first, then divide. Example:
     * January (160 available, 80 billable = 50%) + February (80 available, 80 billable = 100%)
     * → Correct: (80+80)/(160+80) = 66.7%. Wrong: (50%+100%)/2 = 75%.</p>
     *
     * @param billableHours total billable hours (numerator)
     * @param netAvailableHours total net available hours (denominator)
     * @return utilization percentage, or 0.0 if netAvailableHours is zero or negative
     */
    public static double calcPercent(double billableHours, double netAvailableHours) {
        if (netAvailableHours <= 0.0) return 0.0;
        return (billableHours / netAvailableHours) * 100.0;
    }

    // ── Fiscal year ───────────────────────────────────────────────────────

    /**
     * Returns the fiscal year boundaries for the given fiscal year number.
     *
     * <p><b>Convention:</b> FY2025 runs from 2025-07-01 through 2026-06-30 (inclusive).</p>
     *
     * @param fiscalYear the fiscal year (e.g. 2025 for Jul 2025 – Jun 2026)
     * @return start (July 1) and end (June 30) as inclusive boundaries
     */
    public static FiscalYearRange getFiscalYearRange(int fiscalYear) {
        return new FiscalYearRange(
                fiscalYear,
                LocalDate.of(fiscalYear, Month.JULY, 1),
                LocalDate.of(fiscalYear + 1, Month.JUNE, 30)
        );
    }

    /**
     * Determines the current fiscal year and returns its boundaries.
     *
     * <p>If today is July or later, the fiscal year equals the calendar year.
     * If today is January through June, the fiscal year equals the previous calendar year.</p>
     *
     * @return current fiscal year range with inclusive boundaries
     */
    public static FiscalYearRange getCurrentFiscalYearRange() {
        LocalDate now = LocalDate.now();
        int fy = now.getMonthValue() >= 7 ? now.getYear() : now.getYear() - 1;
        return getFiscalYearRange(fy);
    }

    // ── Month key formatting ──────────────────────────────────────────────

    /**
     * Formats a year and month as a 6-character month key string.
     *
     * @param year 4-digit year
     * @param month 1-based month number (1=January, 12=December)
     * @return month key in "YYYYMM" format, e.g. "202507"
     */
    public static String toMonthKey(int year, int month) {
        return String.format("%04d%02d", year, month);
    }

    /**
     * Formats a LocalDate as a month key string.
     *
     * @param date any date within the target month
     * @return month key in "YYYYMM" format
     */
    public static String toMonthKey(LocalDate date) {
        return toMonthKey(date.getYear(), date.getMonthValue());
    }

    // ── TTM (Trailing Twelve Months) ──────────────────────────────────────

    /**
     * Returns the start date for a trailing-twelve-month window.
     *
     * <p>The TTM window starts at the first day of the month that is 12 months
     * before the current month. Example: if today is 2026-04-15, TTM start
     * is 2025-04-01.</p>
     *
     * @return first day of the month 12 months prior to the current month
     */
    public static LocalDate ttmStart() {
        return LocalDate.now().minusMonths(12).withDayOfMonth(1);
    }

    /**
     * Returns the start month key for a TTM window.
     *
     * @return month key of the TTM start month, e.g. "202504"
     */
    public static String ttmStartKey() {
        return toMonthKey(ttmStart());
    }

    /**
     * Returns the end month key for a TTM window (first day of current month).
     * Used with exclusive upper bound ({@code < :toKey}).
     *
     * @return month key of the current month, e.g. "202604"
     */
    public static String ttmEndKey() {
        LocalDate now = LocalDate.now().withDayOfMonth(1);
        return toMonthKey(now);
    }

    // ── Inner types ───────────────────────────────────────────────────────

    /**
     * Immutable fiscal year boundary pair.
     *
     * @param fiscalYear the fiscal year number (e.g. 2025)
     * @param start first day of fiscal year (July 1), inclusive
     * @param end last day of fiscal year (June 30), inclusive
     */
    public record FiscalYearRange(int fiscalYear, LocalDate start, LocalDate end) {
        /** @return start date formatted as month key "YYYYMM" */
        public String startKey() { return toMonthKey(start); }
        /** @return end date formatted as month key "YYYYMM" */
        public String endKey() { return toMonthKey(end); }
    }
}
