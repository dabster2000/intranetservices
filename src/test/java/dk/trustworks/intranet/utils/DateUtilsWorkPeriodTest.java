package dk.trustworks.intranet.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure JUnit 5 tests (no Quarkus, no CDI, no DB) for the work-period helpers added to
 * {@link DateUtils} per the partner-bonus work-period FY bucketing spec (§3.2 / §9.2 bullet 1).
 *
 * <p>{@code workPeriodStart} resolves the first day of the work period an invoice bills for:
 * primary = (year, month); fallback = invoicedate's month; tertiary = current month.
 * {@code workPeriodFiscalYearStartYear} maps that work-period date to its fiscal-year start year
 * (FY runs July 1 (Y) → June 30 (Y+1)).</p>
 */
class DateUtilsWorkPeriodTest {

    @Test
    void june2026Work_bucketsIntoFy2025() {
        // (2026, 6) -> 2026-06-01, which is FY2025/26 (July 2025 -> June 2026).
        assertEquals(LocalDate.of(2026, 6, 1),
                DateUtils.workPeriodStart(2026, 6, null),
                "June 2026 work period start");
        assertEquals(2025,
                DateUtils.workPeriodFiscalYearStartYear(2026, 6, null),
                "June 2026 work belongs to FY2025/26");
    }

    @Test
    void july2026Work_bucketsIntoFy2026() {
        // (2026, 7) -> 2026-07-01, which is the first month of FY2026/27.
        assertEquals(LocalDate.of(2026, 7, 1),
                DateUtils.workPeriodStart(2026, 7, null),
                "July 2026 work period start");
        assertEquals(2026,
                DateUtils.workPeriodFiscalYearStartYear(2026, 7, null),
                "July 2026 work belongs to FY2026/27");
    }

    @Test
    void malformedYearMonth_fallsBackToInvoicedate_july() {
        // Malformed (0,0): the D5 fallback uses invoicedate's month. 2026-07-01 -> FY2026/27.
        LocalDate invoicedate = LocalDate.of(2026, 7, 1);
        assertEquals(LocalDate.of(2026, 7, 1),
                DateUtils.workPeriodStart(0, 0, invoicedate),
                "fallback to invoicedate month");
        assertEquals(2026,
                DateUtils.workPeriodFiscalYearStartYear(0, 0, invoicedate),
                "fallback invoicedate 2026-07 -> FY2026/27");
    }

    @Test
    void outOfRangeMonth_fallsBackToInvoicedate_june() {
        // month 13 is out of [1,12] -> fallback to invoicedate's month. 2026-06-15 -> 2026-06-01 -> FY2025/26.
        LocalDate invoicedate = LocalDate.of(2026, 6, 15);
        assertEquals(LocalDate.of(2026, 6, 1),
                DateUtils.workPeriodStart(0, 13, invoicedate),
                "out-of-range month falls back to first day of invoicedate month");
        assertEquals(2025,
                DateUtils.workPeriodFiscalYearStartYear(0, 13, invoicedate),
                "fallback invoicedate 2026-06 -> FY2025/26");
    }

    @Test
    void allNullish_fallsBackToToday() {
        // No work period and no invoicedate -> today's month. Compute the expected FY via DateUtils
        // itself (not a hard-coded year) so the test never goes stale as the clock advances.
        LocalDate expectedStart = LocalDate.now().withDayOfMonth(1);
        int expectedFy = DateUtils.fiscalYearStart(LocalDate.now()).getYear();

        assertEquals(expectedStart,
                DateUtils.workPeriodStart(0, 0, null),
                "all-nullish falls back to first day of current month");
        assertEquals(expectedFy,
                DateUtils.workPeriodFiscalYearStartYear(0, 0, null),
                "all-nullish FY equals today's fiscal-year start year");
    }
}
