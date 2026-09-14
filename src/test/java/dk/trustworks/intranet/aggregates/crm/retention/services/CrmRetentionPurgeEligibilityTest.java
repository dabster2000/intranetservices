package dk.trustworks.intranet.aggregates.crm.retention.services;

import dk.trustworks.intranet.aggregates.crm.retention.services.CrmRetentionPurgeService.PurgeCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic and the cap — the two pure decisions behind an irreversible sweep.
 *
 * <p>Everything else in {@code CrmRetentionPurgeService} is SQL and transactions. These two
 * are not, and they are where a mistake costs the most: getting the boundary wrong by a day
 * erases an account that was still inside the window, and getting the cap wrong lets a first
 * armed run meet years of backlog in one go.
 */
class CrmRetentionPurgeEligibilityTest {

    /** A fixed day, so the test says the same thing in February as in July. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    @Test
    @DisplayName("the cutoff is exactly 24 months back, calendar months and not 730 days")
    void theCutoffIsTwentyFourCalendarMonthsBack() {
        assertEquals(LocalDate.of(2024, 9, 14), CrmRetentionPurgeService.cutoff(TODAY));
    }

    @Test
    @DisplayName("month arithmetic clamps rather than overflowing — 29 February back two years is 28 February")
    void theCutoffUsesCalendarArithmetic() {
        assertEquals(LocalDate.of(2022, 2, 28),
                CrmRetentionPurgeService.cutoff(LocalDate.of(2024, 2, 29)));
    }

    @Test
    @DisplayName("an account quiet for two years and a day is past the window")
    void olderThanTheWindowIsEligible() {
        assertTrue(CrmRetentionPurgeService.isPastRetention(LocalDate.of(2024, 9, 13), TODAY));
    }

    @Test
    @DisplayName("an account quiet for exactly 24 months is kept one more day — the boundary falls on not deleting")
    void exactlyAtTheWindowIsNotEligible() {
        assertFalse(CrmRetentionPurgeService.isPastRetention(LocalDate.of(2024, 9, 14), TODAY));
    }

    @Test
    @DisplayName("an account that saw activity yesterday is nowhere near it")
    void recentActivityIsNotEligible() {
        assertFalse(CrmRetentionPurgeService.isPastRetention(TODAY.minusDays(1), TODAY));
    }

    @Test
    @DisplayName("an unknown clock is never an old one — a null date must not erase anything")
    void anUnknownClockIsNotEligible() {
        assertFalse(CrmRetentionPurgeService.isPastRetention(null, TODAY),
                "an account whose last activity cannot be established is kept, not purged");
    }

    @Test
    @DisplayName("the cap takes the first N of an oldest-first list and leaves the rest for tomorrow")
    void theCapTakesTheOldestFirst() {
        List<PurgeCandidate> eligible = candidates(
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2021, 1, 1),
                LocalDate.of(2022, 1, 1),
                LocalDate.of(2023, 1, 1));

        List<PurgeCandidate> batch = CrmRetentionPurgeService.withinCap(eligible, 2);

        assertEquals(2, batch.size());
        assertEquals(LocalDate.of(2020, 1, 1), batch.get(0).lastActivityOn());
        assertEquals(LocalDate.of(2021, 1, 1), batch.get(1).lastActivityOn());
    }

    @Test
    @DisplayName("a shorter list than the cap is taken whole")
    void aShortListIsTakenWhole() {
        List<PurgeCandidate> eligible = candidates(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));
        assertEquals(2, CrmRetentionPurgeService.withinCap(eligible, 10).size());
    }

    @Test
    @DisplayName("an empty list stays empty rather than throwing on the sublist")
    void anEmptyListIsSafe() {
        assertTrue(CrmRetentionPurgeService.withinCap(new ArrayList<>(), 10).isEmpty());
    }

    @Test
    @DisplayName("a cap of zero sweeps one account, never the whole backlog")
    void aNonPositiveCapIsTreatedAsOne() {
        List<PurgeCandidate> eligible = candidates(
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1), LocalDate.of(2022, 1, 1));

        assertEquals(1, CrmRetentionPurgeService.withinCap(eligible, 0).size());
        assertEquals(1, CrmRetentionPurgeService.withinCap(eligible, -7).size());
    }

    @Test
    @DisplayName("outstanding() adds up every table the sweep would touch — an account with nothing left scores zero")
    void outstandingCountsEveryTable() {
        PurgeCandidate holdsSomething = new PurgeCandidate(
                "c1", LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1), 3, 1, 2, 4, 1);
        assertEquals(11, holdsSomething.outstanding());

        PurgeCandidate alreadySwept = new PurgeCandidate(
                "c2", LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1), 0, 0, 0, 0, 0);
        assertEquals(0, alreadySwept.outstanding(),
                "an account with nothing left must fall out of the candidate set, or the same ten "
                        + "accounts occupy every night's cap for ever");
    }

    private static List<PurgeCandidate> candidates(LocalDate... lastActivity) {
        List<PurgeCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < lastActivity.length; i++) {
            candidates.add(new PurgeCandidate("client-" + i, lastActivity[i],
                    lastActivity[i].plusMonths(CrmRetentionPurgeService.RETENTION_MONTHS),
                    1, 0, 0, 0, 0));
        }
        return candidates;
    }
}
