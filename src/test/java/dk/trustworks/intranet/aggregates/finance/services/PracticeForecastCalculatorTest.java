package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.PracticeForecastPeriodType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PracticeForecastCalculatorTest {

    @Test
    void julyWindow_preservesJanToDecLayoutAndStopsActualAtYesterday() {
        PracticeForecastCalculator.ForecastWindow window =
                PracticeForecastCalculator.window(LocalDate.of(2026, 7, 14));

        assertEquals(YearMonth.of(2026, 1), window.outputStartMonth());
        assertEquals(YearMonth.of(2026, 12), window.outputEndMonth());
        assertEquals(LocalDate.of(2026, 1, 1), window.actualFromDate());
        assertEquals(LocalDate.of(2026, 7, 13), window.actualToDate());
        assertEquals(LocalDate.of(2026, 7, 1), window.budgetFromDate());
        assertEquals(LocalDate.of(2026, 12, 31), window.budgetToDate());
    }

    @Test
    void periodTypesAndActualThroughDates_areExplicit() {
        PracticeForecastCalculator.ForecastWindow window =
                PracticeForecastCalculator.window(LocalDate.of(2026, 7, 14));

        assertEquals(PracticeForecastPeriodType.COMPLETED_ACTUAL,
                PracticeForecastCalculator.periodType(YearMonth.of(2026, 6), window.currentMonth()));
        assertEquals(LocalDate.of(2026, 6, 30),
                PracticeForecastCalculator.actualThroughDate(YearMonth.of(2026, 6), window));

        assertEquals(PracticeForecastPeriodType.CURRENT_MTD,
                PracticeForecastCalculator.periodType(YearMonth.of(2026, 7), window.currentMonth()));
        assertEquals(LocalDate.of(2026, 7, 13),
                PracticeForecastCalculator.actualThroughDate(YearMonth.of(2026, 7), window));

        assertEquals(PracticeForecastPeriodType.FORWARD_BUDGET,
                PracticeForecastCalculator.periodType(YearMonth.of(2026, 8), window.currentMonth()));
        assertNull(PracticeForecastCalculator.actualThroughDate(YearMonth.of(2026, 8), window));
    }

    @Test
    void percentagesAndGapHours_useMatchingHourBases() {
        assertEquals(50.0, PracticeForecastCalculator.utilizationPct(50.0, 100.0), 1e-9);
        assertEquals(30.0, PracticeForecastCalculator.gapHours(50.0, 100.0, 80.0), 1e-9);
        assertEquals(0.0, PracticeForecastCalculator.gapHours(90.0, 100.0, 80.0), 1e-9);
        assertNull(PracticeForecastCalculator.utilizationPct(10.0, 0.0));
        assertNull(PracticeForecastCalculator.gapHours(10.0, 0.0, 80.0));

        // Weighted-hour result: 80/160 (50%) and 80/80 (100%) combine to 66.67%, not 75%.
        assertEquals(66.6666667,
                PracticeForecastCalculator.utilizationPct(80.0 + 80.0, 160.0 + 80.0), 1e-6);
    }

    @Test
    void firstDayOfMonth_hasNoProvisionalCurrentActualDate() {
        PracticeForecastCalculator.ForecastWindow window =
                PracticeForecastCalculator.window(LocalDate.of(2026, 8, 1));

        assertNull(PracticeForecastCalculator.actualThroughDate(YearMonth.of(2026, 8), window));
        assertEquals(LocalDate.of(2026, 7, 31), window.actualToDate());
    }

    @Test
    void sharedActualSqlUsesHourSumsEffectiveDatesAndCurrentPracticeFallback() {
        String history = CxoFinanceService.practiceActualMonthlySql(false, false);
        String forecast = CxoFinanceService.practiceActualMonthlySql(true, true);

        assertTrue(history.contains("SUM(fud.net_available_hours)"));
        assertTrue(history.contains("SUM(fud.registered_billable_hours)"));
        assertTrue(history.contains("LEFT JOIN user_practice_history"));
        assertTrue(history.contains("COALESCE(uph.practice, u.practice)"));
        assertTrue(history.contains(":fromDate"));
        assertTrue(history.contains(":toDate"));
        assertFalse(history.contains("AVG("));
        assertTrue(forecast.contains(":actualFromDate"));
        assertTrue(forecast.contains(":actualToDate"));
        assertTrue(forecast.contains("fud.companyuuid IN (:companyIds)"));
    }

    @Test
    void budgetSqlIsCapacityLedAndPreservesMissingBudgetAsZero() {
        String sql = CxoFinanceService.practiceBudgetMonthlySql(true);

        assertTrue(sql.contains("SUM(fud2.net_available_hours)"));
        assertTrue(sql.contains("SUM(budget_hours)"));
        assertTrue(sql.contains("COALESCE(rb.budget_hours, 0)"));
        assertTrue(sql.contains("LEFT JOIN"));
        assertTrue(sql.contains("fud2.companyuuid IN (:companyIds)"));
        assertTrue(sql.contains("company_id IN (:companyIds)"));
        assertFalse(sql.contains("AVG("));
    }
}
