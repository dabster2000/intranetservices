package dk.trustworks.intranet.aggregates.utilization.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.capToLastCompleteMonth;
import static dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.getDefaultReportingFiscalYear;
import static dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.getFiscalYearRange;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests (no Quarkus context) for the reporting-window helpers.
 * The helpers read the real clock, so assertions are phrased relative to
 * {@link LocalDate#now()} instead of fixed dates.
 */
class UtilizationCalculationHelperTest {

    private static LocalDate lastCompleteMonthEnd() {
        return LocalDate.now().withDayOfMonth(1).minusDays(1);
    }

    @Test
    void capToLastCompleteMonth_leavesPastDatesUntouched() {
        LocalDate past = LocalDate.now().minusYears(2);
        assertEquals(past, capToLastCompleteMonth(past));
    }

    @Test
    void capToLastCompleteMonth_capsFutureDatesAtPreviousMonthEnd() {
        LocalDate future = LocalDate.now().plusYears(1);
        assertEquals(lastCompleteMonthEnd(), capToLastCompleteMonth(future));
    }

    @Test
    void capToLastCompleteMonth_capsTodayAndCurrentMonth() {
        // Any date inside the in-progress month must be pushed back to the previous month end
        assertEquals(lastCompleteMonthEnd(), capToLastCompleteMonth(LocalDate.now()));
        assertEquals(lastCompleteMonthEnd(),
                capToLastCompleteMonth(LocalDate.now().withDayOfMonth(1)));
    }

    @Test
    void capToLastCompleteMonth_neverReturnsInProgressMonth() {
        LocalDate capped = capToLastCompleteMonth(LocalDate.now().plusDays(1));
        assertTrue(capped.isBefore(LocalDate.now().withDayOfMonth(1)));
    }

    @Test
    void defaultReportingFiscalYear_isFiscalYearOfLastCompleteMonth() {
        LocalDate lastComplete = lastCompleteMonthEnd();
        int expected = lastComplete.getMonthValue() >= 7
                ? lastComplete.getYear()
                : lastComplete.getYear() - 1;
        assertEquals(expected, getDefaultReportingFiscalYear());
    }

    @Test
    void defaultReportingFiscalYear_hasAtLeastOneCompleteMonth() {
        // The invariant that motivated the helper: the default FY's window, capped to
        // complete months, must never be empty (unlike the current FY on July 1st).
        var fy = getFiscalYearRange(getDefaultReportingFiscalYear());
        LocalDate cappedEnd = capToLastCompleteMonth(fy.end());
        assertFalse(fy.start().isAfter(cappedEnd),
                "default reporting FY must contain at least one complete month");
    }
}
