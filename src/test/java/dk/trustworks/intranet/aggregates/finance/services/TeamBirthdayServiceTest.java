package dk.trustworks.intranet.aggregates.finance.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spec §4.6.6: days-until wraps across the year boundary; 0 on the day itself. */
class TeamBirthdayServiceTest {

    @Test
    void todayIsZero() {
        assertEquals(0, TeamBirthdayService.daysUntilNext(LocalDate.of(1998, 9, 5), LocalDate.of(2026, 9, 5)));
    }

    @Test
    void laterThisYearCountsForward() {
        assertEquals(26, TeamBirthdayService.daysUntilNext(LocalDate.of(2001, 10, 1), LocalDate.of(2026, 9, 5)));
    }

    @Test
    void alreadyPassedWrapsToNextYear() {
        // 4 Sep passed yesterday → 364 days until the next one (2027 is not a leap year)
        assertEquals(364, TeamBirthdayService.daysUntilNext(LocalDate.of(2000, 9, 4), LocalDate.of(2026, 9, 5)));
    }

    @Test
    void yearBoundaryWrapsCorrectly() {
        assertEquals(3, TeamBirthdayService.daysUntilNext(LocalDate.of(1995, 1, 2), LocalDate.of(2026, 12, 30)));
    }

    @Test
    void leapDayFallsOnTwentyEighthInACommonYear() {
        assertEquals(0, TeamBirthdayService.daysUntilNext(LocalDate.of(2000, 2, 29), LocalDate.of(2026, 2, 28)));
    }
}
