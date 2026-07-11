package dk.trustworks.intranet.aggregates.utilization.services;

import java.time.LocalDate;
import java.time.Month;
import java.util.Set;
import java.util.regex.Pattern;

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

    // ── Team membership resolution (the shared member-resolution contract) ─

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Canonical TEMPORAL team-membership join — the single member-resolution contract
     * for every window-scoped team aggregation (revenue, cost, hours, rates, client mix).
     *
     * <p>Produces a SQL JOIN fragment that keeps a per-day fact row only if the person
     * was a {@code MEMBER} of the team on that row's date (team membership is temporal —
     * see docs/finalized/utilization, §4.3 membership resolution):
     * {@code tr.startdate <= <date> AND (tr.enddate IS NULL OR tr.enddate > <date>)}.
     * The fragment binds the named parameter {@code :teamId}; callers must set it and must
     * not use the alias {@code tr} themselves.</p>
     *
     * <p>Do NOT filter window-scoped aggregations by a point-in-time member list instead:
     * a today-snapshot applied to a historical window silently drops leavers' contribution
     * and wrongly adds joiners' pre-team data (audit finding C4). Point-in-time member lists
     * ({@code TeamDashboardService#getTeamMemberUuids} / {@code #getAllTeamMemberUuids})
     * are only for "who is on the team right now" views: roster, bench, contract timeline,
     * forward allocation, and access/membership checks.</p>
     *
     * <p>Type/status canon: this join resolves membership only. Combine it with the
     * per-day population filter ({@code fud.consultant_type = 'CONSULTANT' AND
     * fud.status_type = 'ACTIVE'}) so all widgets share one population definition.</p>
     *
     * @param factAlias  SQL alias of the fact table being joined — compile-time identifier
     *                   literal only, never user input
     * @param dateColumn date column on that alias used for the temporal bound — compile-time
     *                   identifier literal only, never user input
     * @return JOIN fragment (leading/trailing newline included) using alias {@code tr}
     * @throws IllegalArgumentException if either argument is not a plain SQL identifier
     */
    public static String teamMemberTemporalJoin(String factAlias, String dateColumn) {
        requireSqlIdentifier(factAlias);
        requireSqlIdentifier(dateColumn);
        String dateRef = factAlias + "." + dateColumn;
        return "\nJOIN teamroles tr ON tr.useruuid = " + factAlias + ".useruuid\n" +
               "    AND tr.teamuuid = :teamId\n" +
               "    AND tr.membertype = 'MEMBER'\n" +
               "    AND tr.startdate <= " + dateRef + "\n" +
               "    AND (tr.enddate IS NULL OR tr.enddate > " + dateRef + ")\n";
    }

    private static void requireSqlIdentifier(String value) {
        if (value == null || !SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Expected a plain SQL identifier (compile-time literal), got: " + value);
        }
    }

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

    /**
     * Caps an inclusive reporting end date at the last day of the most recent COMPLETE month.
     *
     * <p>Per the current-period capping rule (docs/finalized/shared/fiscal-year.md), reporting
     * windows must exclude the in-progress month: a month-to-date numerator paired with a
     * full-month denominator (or vice versa) presents partial data as a complete period.</p>
     *
     * @param end inclusive end date of the requested window (typically a fiscal year end)
     * @return {@code end}, or the last day of the previous month if {@code end} is later
     */
    public static LocalDate capToLastCompleteMonth(LocalDate end) {
        LocalDate lastCompleteMonthEnd = LocalDate.now().withDayOfMonth(1).minusDays(1);
        return end.isBefore(lastCompleteMonthEnd) ? end : lastCompleteMonthEnd;
    }

    /**
     * The default fiscal year for reporting views: the fiscal year of the most recent
     * complete month.
     *
     * <p>In July this is the just-ended fiscal year — the new fiscal year has no complete
     * months yet, so defaulting to it would render empty or partial-data dashboards.
     * From August onward this equals the current fiscal year.</p>
     *
     * @return fiscal year number of the last complete month
     */
    public static int getDefaultReportingFiscalYear() {
        LocalDate lastCompleteMonthEnd = LocalDate.now().withDayOfMonth(1).minusDays(1);
        return lastCompleteMonthEnd.getMonthValue() >= 7
                ? lastCompleteMonthEnd.getYear()
                : lastCompleteMonthEnd.getYear() - 1;
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
