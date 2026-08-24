package dk.trustworks.intranet.utils;

import dk.trustworks.intranet.utils.HolidayCalendar.Holiday;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Month;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HolidayCalendar}.
 *
 * <p>The parity test below is the one that matters: {@link DateUtils#getVacationDayArray(int)}
 * is the authoritative holiday definition for availability and capacity, and
 * {@code HolidayCalendar} exists only to put English names on those same dates.
 * Asserting set equality year by year makes drift between the two impossible to
 * merge unnoticed.</p>
 */
class HolidayCalendarTest {

    /** First and last year of the parity sweep — wide enough to cross the 2024 prayer-day cut. */
    private static final int FIRST_YEAR = 2020;
    private static final int LAST_YEAR = 2035;

    private static Set<LocalDate> dates(List<Holiday> holidays) {
        return holidays.stream().map(Holiday::date).collect(Collectors.toSet());
    }

    @Test
    void matches_DateUtils_vacation_days_for_every_year() {
        for (int year = FIRST_YEAR; year <= LAST_YEAR; year++) {
            // getVacationDayArray hands out a fresh mutable list that callers append to,
            // and its order is an implementation detail — compare as sets.
            Set<LocalDate> authoritative = new HashSet<>(DateUtils.getVacationDayArray(year));
            assertEquals(authoritative, dates(HolidayCalendar.forYear(year)), "holiday dates diverge in " + year);
        }
    }

    @Test
    void every_holiday_has_a_name_and_is_sorted() {
        for (int year = FIRST_YEAR; year <= LAST_YEAR; year++) {
            List<Holiday> holidays = HolidayCalendar.forYear(year);
            for (Holiday holiday : holidays) {
                assertFalse(holiday.name() == null || holiday.name().isBlank(), "unnamed holiday in " + year);
            }
            for (int i = 1; i < holidays.size(); i++) {
                assertTrue(holidays.get(i - 1).date().isBefore(holidays.get(i).date()), "unsorted holidays in " + year);
            }
        }
    }

    @Test
    void general_prayer_day_disappears_in_2024() {
        // Abolished by law with effect from 2024; 2023 is the last year it exists.
        assertTrue(hasName(HolidayCalendar.forYear(2023), "General Prayer Day"));
        assertFalse(hasName(HolidayCalendar.forYear(2024), "General Prayer Day"));
        assertFalse(hasName(HolidayCalendar.forYear(2026), "General Prayer Day"));
    }

    @Test
    void christmas_eve_and_new_years_eve_are_holidays() {
        // Not statutory Danish public holidays, but Trustworks closes both days.
        Set<LocalDate> holidays = dates(HolidayCalendar.forYear(2026));
        assertTrue(holidays.contains(LocalDate.of(2026, Month.DECEMBER, 24)));
        assertTrue(holidays.contains(LocalDate.of(2026, Month.DECEMBER, 31)));
    }

    @Test
    void constitution_day_is_not_a_holiday() {
        assertFalse(dates(HolidayCalendar.forYear(2026)).contains(LocalDate.of(2026, Month.JUNE, 5)));
    }

    @Test
    void between_crossing_new_year_returns_both_years() {
        List<Holiday> holidays = HolidayCalendar.between(LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 5));
        assertEquals(
                Set.of(
                        LocalDate.of(2026, 12, 24),
                        LocalDate.of(2026, 12, 25),
                        LocalDate.of(2026, 12, 26),
                        LocalDate.of(2026, 12, 31),
                        LocalDate.of(2027, 1, 1)),
                dates(holidays));
    }

    @Test
    void between_bounds_are_inclusive() {
        // Both ends land exactly on a holiday.
        List<Holiday> holidays = HolidayCalendar.between(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 26));
        assertEquals(3, holidays.size());
        assertEquals(LocalDate.of(2026, 12, 24), holidays.getFirst().date());
        assertEquals(LocalDate.of(2026, 12, 26), holidays.getLast().date());
    }

    @Test
    void between_excludes_days_just_outside_the_range() {
        assertTrue(HolidayCalendar.between(LocalDate.of(2026, 12, 21), LocalDate.of(2026, 12, 23)).isEmpty());
    }

    @Test
    void between_single_day_on_a_holiday() {
        List<Holiday> holidays = HolidayCalendar.between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));
        assertEquals(1, holidays.size());
        assertEquals("New Year's Day", holidays.getFirst().name());
    }

    @Test
    void between_returns_empty_when_from_is_after_to() {
        assertTrue(HolidayCalendar.between(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 5, 1)).isEmpty());
    }

    @Test
    void between_rejects_an_absurd_span() {
        assertThrows(IllegalArgumentException.class,
                () -> HolidayCalendar.between(LocalDate.of(2026, 1, 1), LocalDate.of(9999, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> HolidayCalendar.between(null, LocalDate.of(2026, 1, 1)));
    }

    private static boolean hasName(List<Holiday> holidays, String name) {
        return holidays.stream().anyMatch(holiday -> holiday.name().equals(name));
    }
}
