package dk.trustworks.intranet.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure JUnit 5 parity test (no DB) that is the runnable twin of the SQL⇄Java cross-check in
 * the partner-bonus work-period spec (§3 / §9.2 "SQL⇄Java parity").
 *
 * <p>The production code carries the work-period rule in two forms that MUST agree:
 * <ul>
 *   <li>SQL: {@code InvoiceBonusService.WP_DATE_SQL} =
 *       {@code CASE WHEN i.year > 0 AND i.month BETWEEN 1 AND 12
 *              THEN MAKEDATE(i.year,1)+INTERVAL(i.month-1) MONTH ELSE i.invoicedate END}</li>
 *   <li>Java: {@link DateUtils#workPeriodFiscalYearStartYear(int, int, LocalDate)}</li>
 * </ul>
 * We re-implement the SQL {@code CASE} here in plain Java, map both results through
 * {@link DateUtils#fiscalYearStart(LocalDate)}, and assert they yield the SAME fiscal year for a
 * fixture set that includes the §6 FY-mover invoices and the four explicit spec tuples.</p>
 */
class WorkPeriodParityTest {

    /** One fixture row: an invoice's work-period fields plus the FY it must land in. */
    private record Fixture(int year, int month, LocalDate invoicedate, int expectedFyStartYear, String label) {}

    /**
     * Java re-implementation of the {@code WP_DATE_SQL} CASE expression.
     * Mirrors the SQL exactly: valid (year, month) -> first of that month; otherwise the raw
     * {@code invoicedate}. (The SQL ELSE branch returns {@code i.invoicedate} unchanged; the day
     * component is irrelevant to fiscal-year assignment, which depends only on year+month.)
     */
    private static LocalDate wpDateSql(int year, int month, LocalDate invoicedate) {
        if (year > 0 && month >= 1 && month <= 12) {
            return LocalDate.of(year, month, 1);
        }
        return invoicedate;
    }

    /** FY start year derived from the SQL-twin work-period date. */
    private static int sqlFyStartYear(int year, int month, LocalDate invoicedate) {
        return DateUtils.fiscalYearStart(wpDateSql(year, month, invoicedate)).getYear();
    }

    private static final List<Fixture> FIXTURES = List.of(
            // ---- The four explicit spec parity tuples ----
            new Fixture(2024, 6, LocalDate.of(2024, 7, 10), 2023, "#17217/#17228 Jun-24 work / Jul-24 issue -> FY2023"),
            new Fixture(2026, 6, LocalDate.of(2026, 7, 5),  2025, "#28080 Jun-26 work / Jul-26 issue -> FY2025"),
            new Fixture(2026, 7, LocalDate.of(2026, 7, 20), 2026, "Jul-26 work / Jul-26 issue -> FY2026"),
            new Fixture(0,    0, LocalDate.of(2026, 3, 11), 2025, "malformed year/month -> invoicedate fallback Mar-26 -> FY2025"),

            // ---- Additional §6 FY-mover rows (all June work / July issue) ----
            new Fixture(2026, 6, LocalDate.of(2026, 7, 1),  2025, "#28087 Ramboll Jun-26 -> FY2025"),
            new Fixture(2026, 6, LocalDate.of(2026, 7, 3),  2025, "#28100 Novo Nordisk Jun-26 -> FY2025"),
            new Fixture(2026, 6, LocalDate.of(2026, 7, 9),  2025, "#28109 Novo Nordisk Jun-26 -> FY2025"),

            // ---- Boundary / non-mover controls ----
            new Fixture(2025, 7, LocalDate.of(2025, 7, 15), 2025, "start of FY2025 (July) stays FY2025"),
            new Fixture(2026, 6, LocalDate.of(2026, 6, 30), 2025, "end of FY2025 (June) stays FY2025"),
            new Fixture(2025, 12, LocalDate.of(2025, 12, 5), 2025, "mid FY2025 (December) stays FY2025")
    );

    @Test
    void helperReproducesIntendedFyForEveryFixture() {
        for (Fixture f : FIXTURES) {
            assertEquals(f.expectedFyStartYear(),
                    DateUtils.workPeriodFiscalYearStartYear(f.year(), f.month(), f.invoicedate()),
                    "Java helper FY mismatch for: " + f.label());
        }
    }

    @Test
    void sqlTwinMatchesJavaHelperForEveryFixture() {
        for (Fixture f : FIXTURES) {
            int sqlFy = sqlFyStartYear(f.year(), f.month(), f.invoicedate());
            int helperFy = DateUtils.workPeriodFiscalYearStartYear(f.year(), f.month(), f.invoicedate());
            assertEquals(helperFy, sqlFy,
                    "SQL-twin and Java helper diverge for: " + f.label());
            assertEquals(f.expectedFyStartYear(), sqlFy,
                    "SQL-twin FY mismatch for: " + f.label());
        }
    }
}
