package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "working day before" rule the eve brief runs on (V531).
 *
 * <p>The whole point of the eve brief is that it arrives when someone is at
 * their desk and can still act on it. A naive "interview date minus one" gets
 * that wrong once a week — it briefs Monday's interviews on Sunday, which is
 * the exact failure the eve brief was introduced to fix. These tests pin the
 * weekend behaviour so nobody simplifies it back.
 *
 * <p>Pure date arithmetic, no Quarkus: this is the fast tier, which is the
 * gate that actually runs on every deploy.
 */
class RecruitmentEveBriefWindowTest {

    // A known-good week: 2026-08-24 is a Monday.
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final LocalDate TUESDAY = MONDAY.plusDays(1);
    private static final LocalDate WEDNESDAY = MONDAY.plusDays(2);
    private static final LocalDate THURSDAY = MONDAY.plusDays(3);
    private static final LocalDate FRIDAY = MONDAY.plusDays(4);
    private static final LocalDate SATURDAY = MONDAY.plusDays(5);
    private static final LocalDate SUNDAY = MONDAY.plusDays(6);
    private static final LocalDate NEXT_MONDAY = MONDAY.plusDays(7);

    @Test
    @DisplayName("a midweek run covers exactly the next day")
    void midweekRunCoversTomorrow() {
        assertEquals(List.of(WEDNESDAY), RecruitmentMorningBriefService.eveTargetDates(TUESDAY, 1));
        assertEquals(List.of(THURSDAY), RecruitmentMorningBriefService.eveTargetDates(WEDNESDAY, 1));
        assertEquals(List.of(FRIDAY), RecruitmentMorningBriefService.eveTargetDates(THURSDAY, 1));
    }

    @Test
    @DisplayName("Friday carries the weekend: it briefs Saturday, Sunday AND Monday")
    void fridayCoversTheWeekendAndMonday() {
        // The load-bearing case. Without it, Monday's interviews would be
        // briefed on Sunday evening — delivered, unread, and worse than not
        // sending at all, because the day-of brief then shortens itself on
        // the assumption the prep already landed.
        assertEquals(List.of(SATURDAY, SUNDAY, NEXT_MONDAY),
                RecruitmentMorningBriefService.eveTargetDates(FRIDAY, 1));
    }

    @Test
    @DisplayName("a weekend run sends nothing")
    void weekendRunsAreEmpty() {
        // No interview date has a Saturday or Sunday as its working-day-before,
        // so these runs are no-ops rather than special cases in the caller.
        assertTrue(RecruitmentMorningBriefService.eveTargetDates(SATURDAY, 1).isEmpty());
        assertTrue(RecruitmentMorningBriefService.eveTargetDates(SUNDAY, 1).isEmpty());
    }

    @Test
    @DisplayName("every date is covered exactly once across a full week of runs")
    void everyDateIsBriefedExactlyOnce() {
        // The property that matters more than any individual case: over a
        // week of daily runs no interview date is briefed twice (duplicate
        // DMs) and none is skipped (silent loss). Checked across a whole
        // year so month and DST boundaries are included.
        LocalDate from = LocalDate.of(2026, 1, 1);
        int[] coverage = new int[400];
        for (int i = 0; i < 365; i++) {
            for (LocalDate covered : RecruitmentMorningBriefService.eveTargetDates(from.plusDays(i), 1)) {
                int index = (int) (covered.toEpochDay() - from.toEpochDay());
                if (index >= 0 && index < coverage.length) {
                    coverage[index]++;
                }
            }
        }
        // Skip the first days (briefed by runs before the window) and the
        // last (their brief day falls after it).
        for (int i = 4; i < 360; i++) {
            assertEquals(1, coverage[i],
                    "date " + from.plusDays(i) + " must be briefed exactly once");
        }
    }

    @Test
    @DisplayName("a longer lead time still lands on a working day")
    void longerLeadStillSkipsTheWeekend() {
        // lead=3 from Wednesday is Sunday, which walks back to Friday — so
        // Friday's run picks Wednesday up. Nothing may ever be scheduled to
        // deliver on a Saturday or Sunday, whatever the lead.
        for (int lead = 1; lead <= 5; lead++) {
            for (int i = 0; i < 30; i++) {
                LocalDate run = MONDAY.plusDays(i);
                if (!RecruitmentMorningBriefService.eveTargetDates(run, lead).isEmpty()) {
                    assertTrue(run.getDayOfWeek().getValue() <= 5,
                            "lead=" + lead + " scheduled a brief on " + run.getDayOfWeek());
                }
            }
        }
    }

    @Test
    @DisplayName("the covered dates are always in the future and in order")
    void coveredDatesAreForwardLookingAndSorted() {
        for (int i = 0; i < 30; i++) {
            LocalDate run = MONDAY.plusDays(i);
            List<LocalDate> dates = RecruitmentMorningBriefService.eveTargetDates(run, 1);
            LocalDate previous = null;
            for (LocalDate date : dates) {
                assertTrue(date.isAfter(run), "brief must look forward, got " + date + " on " + run);
                if (previous != null) {
                    assertTrue(date.isAfter(previous), "dates must be ascending");
                }
                previous = date;
            }
        }
    }
}
