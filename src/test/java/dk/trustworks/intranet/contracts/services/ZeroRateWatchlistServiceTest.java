package dk.trustworks.intranet.contracts.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The watchlist arithmetic (spec §4.4.3): days remaining, overdue, and value given away = hours × list rate. */
class ZeroRateWatchlistServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    @Test
    void daysRemainingCountsForwardAndGoesNegativeWhenPassed() {
        assertEquals(26, ZeroRateWatchlistService.daysRemaining(TODAY, LocalDate.of(2026, 10, 31)));
        assertEquals(0, ZeroRateWatchlistService.daysRemaining(TODAY, TODAY));
        assertEquals(-5, ZeroRateWatchlistService.daysRemaining(TODAY, LocalDate.of(2026, 9, 30)));
        assertEquals(0, ZeroRateWatchlistService.daysRemaining(TODAY, null));
    }

    @Test
    void overdueOnlyWhenTheReviewDateHasPassed() {
        assertTrue(ZeroRateWatchlistService.isOverdue(TODAY, LocalDate.of(2026, 9, 30)));
        assertFalse(ZeroRateWatchlistService.isOverdue(TODAY, TODAY));
        assertFalse(ZeroRateWatchlistService.isOverdue(TODAY, LocalDate.of(2026, 10, 31)));
        assertFalse(ZeroRateWatchlistService.isOverdue(TODAY, null));
    }

    @Test
    void valueGivenAwayIsHoursTimesListRateRoundedToOere() {
        assertEquals(22_800.0, ZeroRateWatchlistService.valueGivenAway(38.0, 600.0), 1e-9);
        assertEquals(2_250.0, ZeroRateWatchlistService.valueGivenAway(3.75, 600.0), 1e-9);
        assertEquals(0.0, ZeroRateWatchlistService.valueGivenAway(0.0, 600.0), 1e-9);
        assertEquals(0.0, ZeroRateWatchlistService.valueGivenAway(38.0, 0.0), 1e-9);
    }
}
