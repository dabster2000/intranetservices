package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.TeamCapacityDTO.WeekCell;
import dk.trustworks.intranet.aggregates.finance.services.HourlyTeamDashboardService.WorkSplit;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Spec §4.6.4 / D7: a week with no declaration is <em>No plan</em> (null), not 0; the capacity
 * split is client paid / client 0 kr / internal / unplanned; unplanned = declared − demand.
 */
class HourlyTeamDashboardServiceWeekCellTest {

    /** Monday 7 Sep 2026 — an ordinary five-day week. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);

    private static WorkSplit split(double paid, double zero, double internal) {
        WorkSplit w = new WorkSplit();
        w.paidHours = paid;
        w.zeroRateHours = zero;
        w.internalHours = internal;
        return w;
    }

    @Test
    void undeclaredWeekIsNoPlanWithFiveUndeclaredWorkingDays() {
        WeekCell cell = HourlyTeamDashboardService.weekCell(MONDAY, Map.of(), Map.of(), Map.of(), Map.of());
        assertNull(cell.declaredHours());
        assertEquals(5, cell.undeclaredDays());
        assertEquals(0.0, cell.unplannedHours(), 1e-9);
        assertEquals(0.0, cell.registeredHours(), 1e-9);
    }

    @Test
    void declaredWeekSumsHoursAndCountsTheMissingDays() {
        Map<LocalDate, Double> declared = Map.of(MONDAY, 4.0, MONDAY.plusDays(1), 4.0, MONDAY.plusDays(3), 8.0);
        WeekCell cell = HourlyTeamDashboardService.weekCell(MONDAY, declared, Map.of(), Map.of(), Map.of());
        assertEquals(16.0, cell.declaredHours(), 1e-9);
        assertEquals(2, cell.undeclaredDays());
        assertEquals(16.0, cell.unplannedHours(), 1e-9);
    }

    @Test
    void capacitySplitAndUnplannedFollowTheDiscriminator() {
        Map<LocalDate, Double> declared = Map.of(MONDAY, 8.0, MONDAY.plusDays(1), 8.0, MONDAY.plusDays(2), 8.0,
                MONDAY.plusDays(3), 8.0, MONDAY.plusDays(4), 8.0);
        Map<LocalDate, WorkSplit> work = Map.of(
                MONDAY, split(6.0, 0.0, 2.0),
                MONDAY.plusDays(1), split(0.0, 7.0, 0.0));
        Map<LocalDate, Double> contractBudget = Map.of(MONDAY, 7.4, MONDAY.plusDays(1), 7.4);
        Map<LocalDate, Double> internalBudget = Map.of(MONDAY.plusDays(2), 4.0);

        WeekCell cell = HourlyTeamDashboardService.weekCell(MONDAY, declared, work, contractBudget, internalBudget);
        assertEquals(40.0, cell.declaredHours(), 1e-9);
        assertEquals(0, cell.undeclaredDays());
        assertEquals(15.0, cell.registeredHours(), 1e-9);
        assertEquals(6.0, cell.paidClientHours(), 1e-9);
        assertEquals(7.0, cell.zeroRateClientHours(), 1e-9);
        assertEquals(2.0, cell.internalHours(), 1e-9);
        assertEquals(14.8, cell.contractBudgetHours(), 1e-9);
        assertEquals(4.0, cell.internalBudgetHours(), 1e-9);
        assertEquals(40.0 - 14.8 - 4.0, cell.unplannedHours(), 1e-9);
    }

    @Test
    void unplannedNeverGoesNegative() {
        Map<LocalDate, Double> declared = Map.of(MONDAY, 2.0);
        Map<LocalDate, Double> contractBudget = Map.of(MONDAY, 7.4);
        WeekCell cell = HourlyTeamDashboardService.weekCell(MONDAY, declared, Map.of(), contractBudget, Map.of());
        assertEquals(0.0, cell.unplannedHours(), 1e-9);
    }

    @Test
    void workingDaysSkipWeekendsAndDanishHolidays() {
        // Christmas week 2026: 24 Dec (Thu, holiday) and 25 Dec (Fri, holiday) are not working days
        List<LocalDate> days = HourlyTeamDashboardService.workingDays(LocalDate.of(2026, 12, 21), LocalDate.of(2026, 12, 27));
        assertEquals(List.of(LocalDate.of(2026, 12, 21), LocalDate.of(2026, 12, 22), LocalDate.of(2026, 12, 23)), days);
    }
}
