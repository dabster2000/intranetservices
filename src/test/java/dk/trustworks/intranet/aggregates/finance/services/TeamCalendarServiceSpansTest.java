package dk.trustworks.intranet.aggregates.finance.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §4.6.5 / §4.6.7: leave days aggregate to contiguous spans server-side, bridged across
 * weekends and non-working days — the mockup slide 7 case (overlapping contract, internal
 * assignment and ferie in one month) needs one bar per run, not one per day.
 */
class TeamCalendarServiceSpansTest {

    @Test
    void consecutiveDaysAreOneSpan() {
        List<LocalDate[]> runs = TeamCalendarService.toSpans(List.of(
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7), LocalDate.of(2026, 7, 8)), Set.of());
        assertEquals(1, runs.size());
        assertEquals(LocalDate.of(2026, 7, 6), runs.get(0)[0]);
        assertEquals(LocalDate.of(2026, 7, 8), runs.get(0)[1]);
    }

    @Test
    void aWeekendBridgesFridayToMonday() {
        // Fri 10 Jul → Mon 13 Jul 2026: one holiday, not two
        List<LocalDate[]> runs = TeamCalendarService.toSpans(List.of(
                LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 14)), Set.of());
        assertEquals(1, runs.size());
        assertEquals(LocalDate.of(2026, 7, 9), runs.get(0)[0]);
        assertEquals(LocalDate.of(2026, 7, 14), runs.get(0)[1]);
    }

    @Test
    void aWorkingDayGapSplitsTheRunUnlessItIsANonWorkingDay() {
        LocalDate wed = LocalDate.of(2026, 7, 8);
        List<LocalDate[]> split = TeamCalendarService.toSpans(List.of(
                LocalDate.of(2026, 7, 7), LocalDate.of(2026, 7, 9)), Set.of());
        assertEquals(2, split.size());

        List<LocalDate[]> bridged = TeamCalendarService.toSpans(List.of(
                LocalDate.of(2026, 7, 7), LocalDate.of(2026, 7, 9)), Set.of(wed));
        assertEquals(1, bridged.size());
    }

    @Test
    void unorderedInputIsSortedAndEmptyInputIsEmpty() {
        List<LocalDate[]> runs = TeamCalendarService.toSpans(List.of(
                LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7)), Set.of());
        assertEquals(1, runs.size());
        assertTrue(TeamCalendarService.toSpans(List.of(), Set.of()).isEmpty());
        assertTrue(TeamCalendarService.toSpans(null, Set.of()).isEmpty());
    }
}
