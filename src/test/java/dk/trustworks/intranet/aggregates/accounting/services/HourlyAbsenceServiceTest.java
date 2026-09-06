package dk.trustworks.intranet.aggregates.accounting.services;

import dk.trustworks.intranet.aggregates.accounting.dto.HourlyAbsenceDTO;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The worksheet's arithmetic (spec §4.2.3): hours per day, total and DKK value at the hourly rate. */
class HourlyAbsenceServiceTest {

    private static WorkFull sick(LocalDate day, double hours) {
        WorkFull w = new WorkFull();
        w.setRegistered(day);
        w.setWorkduration(hours);
        return w;
    }

    @Test
    void sumsHoursPerDayAndValuesThemAtTheHourlyRate() {
        HourlyAbsenceDTO dto = HourlyAbsenceService.summarise("u1", "Emma Junior", "c1", 175, List.of(
                sick(LocalDate.of(2026, 10, 6), 5.0),
                sick(LocalDate.of(2026, 10, 6), 0.5),   // second row on the same day
                sick(LocalDate.of(2026, 10, 13), 4.25)));

        assertEquals(2, dto.sickDays().size());
        assertEquals(LocalDate.of(2026, 10, 6), dto.sickDays().get(0).day());
        assertEquals(5.5, dto.sickDays().get(0).hours(), 1e-9);
        assertEquals(4.25, dto.sickDays().get(1).hours(), 1e-9);
        assertEquals(9.75, dto.sickHours(), 1e-9);
        assertEquals(9.75 * 175, dto.sickValue(), 1e-9);
        assertEquals(175, dto.hourlyRate());
    }

    @Test
    void noSickRowsIsAZeroRowNotAnAbsentEmployee() {
        HourlyAbsenceDTO dto = HourlyAbsenceService.summarise("u1", "Emma Junior", "c1", 175, List.of());
        assertTrue(dto.sickDays().isEmpty());
        assertEquals(0.0, dto.sickHours(), 1e-9);
        assertEquals(0.0, dto.sickValue(), 1e-9);
    }

    @Test
    void zeroAndNegativeDurationsAreIgnored() {
        HourlyAbsenceDTO dto = HourlyAbsenceService.summarise("u1", "n", "c1", 150, List.of(
                sick(LocalDate.of(2026, 10, 6), 0.0),
                sick(LocalDate.of(2026, 10, 7), -1.0)));
        assertTrue(dto.sickDays().isEmpty());
        assertEquals(0.0, dto.sickHours(), 1e-9);
    }
}
