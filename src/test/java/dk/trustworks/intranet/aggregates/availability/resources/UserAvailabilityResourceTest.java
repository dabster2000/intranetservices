package dk.trustworks.intranet.aggregates.availability.resources;

import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code hourlyPaid} follows the salary in force on the day — latest {@code activefrom ≤ day}. */
class UserAvailabilityResourceTest {

    private static Salary salary(LocalDate from, SalaryType type) {
        Salary s = new Salary();
        s.setActivefrom(from);
        s.setType(type);
        return s;
    }

    @Test
    void latestSalaryOnOrBeforeTheDayDecides() {
        List<Salary> history = List.of(
                salary(LocalDate.of(2025, 1, 1), SalaryType.HOURLY),
                salary(LocalDate.of(2026, 9, 1), SalaryType.NORMAL));

        assertTrue(UserAvailabilityResource.isHourlyPaid(history, LocalDate.of(2026, 8, 31)));
        assertFalse(UserAvailabilityResource.isHourlyPaid(history, LocalDate.of(2026, 9, 1)));
        assertFalse(UserAvailabilityResource.isHourlyPaid(history, LocalDate.of(2026, 12, 1)));
    }

    @Test
    void noSalaryYetIsNotHourly() {
        assertFalse(UserAvailabilityResource.isHourlyPaid(List.of(), LocalDate.of(2026, 10, 1)));
        assertFalse(UserAvailabilityResource.isHourlyPaid(
                List.of(salary(LocalDate.of(2026, 11, 1), SalaryType.HOURLY)), LocalDate.of(2026, 10, 1)));
        assertFalse(UserAvailabilityResource.isHourlyPaid(null, LocalDate.of(2026, 10, 1)));
    }
}
