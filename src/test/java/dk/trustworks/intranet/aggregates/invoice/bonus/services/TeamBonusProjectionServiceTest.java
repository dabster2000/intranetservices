package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.services.TeamBonusProjectionService.LeaderPeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-logic unit tests for the {@link TeamBonusProjectionService} monthly-detail helpers (no DB, no
 * CDI): fiscal-month key generation, the divide-by-zero utilization guard, and the leader-of-month
 * selection over half-open {@code [start, endExclusive)} teamrole periods.
 */
class TeamBonusProjectionServiceTest {

    private static final double DELTA = 1e-9;

    // --- month key ---

    @Test
    void monthKey_julyFiscalYearStart_isYyyymm() {
        assertEquals("202507", TeamBonusProjectionService.monthKey(YearMonth.of(2025, 7)));
    }

    @Test
    void monthKey_singleDigitMonth_isZeroPadded() {
        assertEquals("202601", TeamBonusProjectionService.monthKey(YearMonth.of(2026, 1)));
    }

    // --- utilization guard ---

    @Test
    void utilizationOrNull_normal_isBillableOverAvailableRoundedTo4() {
        // 120 / 160 = 0.75
        assertEquals(0.75, TeamBonusProjectionService.utilizationOrNull(120.0, 160.0), DELTA);
    }

    @Test
    void utilizationOrNull_roundsToFourDecimals() {
        // 100 / 150 = 0.66666... -> 0.6667
        assertEquals(0.6667, TeamBonusProjectionService.utilizationOrNull(100.0, 150.0), DELTA);
    }

    @Test
    void utilizationOrNull_zeroAvailable_isNull() {
        assertNull(TeamBonusProjectionService.utilizationOrNull(0.0, 0.0));
    }

    @Test
    void utilizationOrNull_billableWithoutAvailable_isNull() {
        assertNull(TeamBonusProjectionService.utilizationOrNull(10.0, 0.0));
    }

    // --- leader of month ---

    @Test
    void resolveLeaderOfMonth_noPeriods_isNull() {
        assertNull(TeamBonusProjectionService.resolveLeaderOfMonth(YearMonth.of(2025, 7), List.of()));
    }

    @Test
    void resolveLeaderOfMonth_openEndedRoleCoveringMonth_isSelected() {
        LeaderPeriod alice = new LeaderPeriod("alice", "Alice A", LocalDate.of(2024, 1, 1), null);
        LeaderPeriod winner = TeamBonusProjectionService.resolveLeaderOfMonth(YearMonth.of(2025, 7), List.of(alice));
        assertEquals("alice", winner.uuid());
    }

    @Test
    void resolveLeaderOfMonth_roleEndingBeforeMonth_isNotSelected() {
        // endExclusive = 2025-07-01 -> covers up to and including 2025-06-30, nothing in July.
        LeaderPeriod alice = new LeaderPeriod("alice", "Alice A",
                LocalDate.of(2024, 1, 1), LocalDate.of(2025, 7, 1));
        assertNull(TeamBonusProjectionService.resolveLeaderOfMonth(YearMonth.of(2025, 7), List.of(alice)));
    }

    @Test
    void resolveLeaderOfMonth_roleStartingAfterMonth_isNotSelected() {
        LeaderPeriod bob = new LeaderPeriod("bob", "Bob B", LocalDate.of(2025, 8, 1), null);
        assertNull(TeamBonusProjectionService.resolveLeaderOfMonth(YearMonth.of(2025, 7), List.of(bob)));
    }

    @Test
    void resolveLeaderOfMonth_twoLeadersSplitMonth_picksMostDays() {
        // July 2025 has 31 days. Alice covers 1..14 (endExclusive 15 -> 14 days),
        // Bob covers 15..31 (17 days) -> Bob wins.
        LeaderPeriod alice = new LeaderPeriod("alice", "Alice A",
                LocalDate.of(2025, 7, 1), LocalDate.of(2025, 7, 15));
        LeaderPeriod bob = new LeaderPeriod("bob", "Bob B",
                LocalDate.of(2025, 7, 15), null);
        LeaderPeriod winner = TeamBonusProjectionService.resolveLeaderOfMonth(
                YearMonth.of(2025, 7), List.of(alice, bob));
        assertEquals("bob", winner.uuid());
    }

    @Test
    void resolveLeaderOfMonth_equalDaysTie_brokenByLowestUuid() {
        // Alice covers 1..15 (endExclusive 16 -> 15 days), Bob covers 16..31 (16 days)?
        // Make it an exact tie: month with even split. Use a 30-day month (June).
        // June 2025: Alice 1..15 (endExclusive 16 -> 15 days), Bob 16..30 (endExclusive July 1 -> 15 days).
        LeaderPeriod bob = new LeaderPeriod("bob", "Bob B",
                LocalDate.of(2025, 6, 16), LocalDate.of(2025, 7, 1));
        LeaderPeriod alice = new LeaderPeriod("alice", "Alice A",
                LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 16));
        LeaderPeriod winner = TeamBonusProjectionService.resolveLeaderOfMonth(
                YearMonth.of(2025, 6), List.of(bob, alice));
        assertEquals("alice", winner.uuid(), "tie must be broken deterministically by lowest UUID");
    }

    @Test
    void resolveLeaderOfMonth_partialMonthRole_stillSelectedWhenSoleOverlap() {
        // Role starts mid-month and is open-ended: it is the only overlap, so it wins even if partial.
        LeaderPeriod carol = new LeaderPeriod("carol", "Carol C", LocalDate.of(2025, 7, 20), null);
        LeaderPeriod winner = TeamBonusProjectionService.resolveLeaderOfMonth(
                YearMonth.of(2025, 7), List.of(carol));
        assertEquals("carol", winner.uuid());
    }
}
