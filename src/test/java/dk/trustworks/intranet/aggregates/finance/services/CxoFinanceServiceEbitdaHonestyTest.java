package dk.trustworks.intranet.aggregates.finance.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the two EBITDA-honesty fixes in
 * {@link CxoFinanceService#getExpectedAccumulatedEBITDA}:
 *
 * <ul>
 *   <li><b>F5</b> — for the current (in-progress) month the forecast branch must take the higher of
 *       actual-booked revenue and backlog so already-invoiced revenue (incl. the ~13M year-end
 *       internal settlement) is never discarded. Older forecast months keep using backlog as-is.</li>
 *   <li><b>F4</b> — for the 1–2 PROVISIONAL actual months immediately before the current month
 *       (where GL payroll posts in arrears) the salary line is sourced as max(fact_salary_monthly,
 *       booked), so an under-posted recent month is lifted to the complete figure while a fully-posted
 *       (complete) month is left untouched and keeps reconciling to the krone.</li>
 * </ul>
 *
 * <p>The selection/classification logic is extracted into package-private static methods precisely so
 * it can be asserted deterministically here, without a test database (full-DB numeric assertions are
 * not feasible under the lightweight test profile). These methods did not exist before the fix, so
 * this class fails to compile against the pre-fix tree — a hard pre-fix failure.
 */
class CxoFinanceServiceEbitdaHonestyTest {

    // June 2026 production scenario from the bug report.
    private static final String CURRENT_MONTH = "202606"; // current (in-progress) month
    private static final double JUNE_ACTUAL_BOOKED_REVENUE = 23_240_467.0;
    private static final double JUNE_BACKLOG_REVENUE        = 16_187_406.0;

    // ------------------------------------------------------------------
    // F5: current-month revenue = max(actualBooked, backlog)
    // ------------------------------------------------------------------

    @Test
    void resolveForecastMonthRevenue_currentMonth_booksExceedBacklog_usesBooked() {
        double chosen = CxoFinanceService.resolveForecastMonthRevenue(
                CURRENT_MONTH, CURRENT_MONTH, JUNE_BACKLOG_REVENUE, JUNE_ACTUAL_BOOKED_REVENUE);

        // June's booked invoiced revenue exceeds backlog, so it must NOT be discarded.
        assertEquals(JUNE_ACTUAL_BOOKED_REVENUE, chosen, 0.001,
                "Current month must keep actual booked revenue when it exceeds backlog");
    }

    @Test
    void resolveForecastMonthRevenue_currentMonth_backlogExceedsBooks_usesBacklog() {
        double chosen = CxoFinanceService.resolveForecastMonthRevenue(
                CURRENT_MONTH, CURRENT_MONTH, /*backlog*/ 20_000_000.0, /*booked*/ 5_000_000.0);

        // When backlog is the larger (early in the month, little booked yet) it still wins.
        assertEquals(20_000_000.0, chosen, 0.001,
                "Current month must use the higher of booked and backlog");
    }

    @Test
    void resolveForecastMonthRevenue_futureMonth_alwaysBacklog_evenIfBookedHigher() {
        // A future month (not the current month) must use backlog only — booked figures there are
        // partial/noise and must not be rescued.
        double chosen = CxoFinanceService.resolveForecastMonthRevenue(
                "202608", CURRENT_MONTH, /*backlog*/ 4_000_000.0, /*booked*/ 9_999_999.0);

        assertEquals(4_000_000.0, chosen, 0.001,
                "Non-current forecast months must use backlog regardless of any booked value");
    }

    // ------------------------------------------------------------------
    // F4: provisional-month salary = max(fact_salary_monthly, booked)
    // ------------------------------------------------------------------

    @Test
    void resolveProvisionalSalary_bookedZero_liftsToFactSalary() {
        // May-2026 under BOOKED: booked payroll not yet posted (0), true salary ~8.22M.
        double salary = CxoFinanceService.resolveProvisionalSalary(8_220_000.0, 0.0);
        assertEquals(8_220_000.0, salary, 0.001,
                "Provisional month with un-posted booked payroll must use fact_salary_monthly");
    }

    @Test
    void resolveProvisionalSalary_bookedDraftPartial_liftsToFactSalary() {
        // May-2026 under BOOKED_PLUS_DRAFT: draft 6.67M < true 8.22M.
        double salary = CxoFinanceService.resolveProvisionalSalary(8_220_000.0, 6_670_000.0);
        assertEquals(8_220_000.0, salary, 0.001,
                "Provisional month with partial draft payroll must still use the higher fact figure");
    }

    @Test
    void resolveProvisionalSalary_completeMonthBookedHigher_leavesBookedUntouched() {
        // A fully-posted month (booked GL incl. pension/holiday >= contractual fact) must be unchanged
        // so completed months keep reconciling to the krone — max() never lowers booked.
        double salary = CxoFinanceService.resolveProvisionalSalary(8_000_000.0, 10_000_000.0);
        assertEquals(10_000_000.0, salary, 0.001,
                "A complete month (booked >= fact) must keep its booked salary unchanged");
    }

    // ------------------------------------------------------------------
    // F4: provisional window = currentMonth-1 and currentMonth-2 only
    // ------------------------------------------------------------------

    @Test
    void provisionalMonthKeys_june2026_isMayAndApril() {
        Set<String> keys = CxoFinanceService.provisionalMonthKeys(LocalDate.of(2026, 6, 28));

        assertEquals(Set.of("202605", "202604"), keys,
                "Provisional window must be exactly the two months before the current month");
    }

    @Test
    void provisionalMonthKeys_excludesCurrentAndOlderCompleteMonths() {
        Set<String> keys = CxoFinanceService.provisionalMonthKeys(LocalDate.of(2026, 6, 28));

        assertFalse(keys.contains("202606"), "Current month must NOT be provisional");
        assertFalse(keys.contains("202603"), "March (complete) must NOT be provisional");
        assertFalse(keys.contains("202507"), "July (complete) must NOT be provisional");
        assertTrue(keys.contains("202605"), "May (currentMonth-1) must be provisional");
        assertTrue(keys.contains("202604"), "April (currentMonth-2) must be in the window");
    }

    @Test
    void provisionalMonthKeys_handlesYearBoundary() {
        // Current month January 2026 → provisional Dec-2025 and Nov-2025.
        Set<String> keys = CxoFinanceService.provisionalMonthKeys(LocalDate.of(2026, 1, 15));
        assertEquals(Set.of("202512", "202511"), keys,
                "Provisional window must cross the calendar-year boundary correctly");
    }
}
