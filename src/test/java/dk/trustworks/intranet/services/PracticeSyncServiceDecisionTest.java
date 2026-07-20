package dk.trustworks.intranet.services;

import dk.trustworks.intranet.services.PracticeSyncService.HistoryState;
import dk.trustworks.intranet.services.PracticeSyncService.TransitionAction;
import dk.trustworks.intranet.services.PracticeSyncService.TransitionPlan;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free unit tests for the pure derivation/transition decision helpers in
 * {@link PracticeSyncService} (Part 2 Phase 2, spec §4.2). Same package so the
 * package-private statics are visible — the Phase 0 house style.
 */
class PracticeSyncServiceDecisionTest {

    private static final LocalDate ASOF = LocalDate.of(2026, 7, 19);

    private static TeamRole role(String team, LocalDate start, LocalDate end) {
        return new TeamRole("role-" + team + "-" + start, team, "user-1", start, end, TeamMemberType.MEMBER);
    }

    // ── currentMemberRole: the canonical temporal predicate (pinned) ──────

    @Test
    void current_iff_started_and_not_yet_ended() {
        TeamRole open = role("A", ASOF.minusYears(1), null);
        assertEquals(open, PracticeSyncService.currentMemberRole(List.of(open), ASOF));
    }

    @Test
    void start_today_is_current_start_tomorrow_is_not() {
        assertEquals("A", PracticeSyncService.currentMemberRole(
                List.of(role("A", ASOF, null)), ASOF).getTeamuuid());
        assertNull(PracticeSyncService.currentMemberRole(
                List.of(role("A", ASOF.plusDays(1), null)), ASOF));
    }

    @Test
    void enddate_today_is_no_longer_current_enddate_tomorrow_still_is() {
        // Half-open [startdate, enddate): enddate must be strictly after asOf.
        assertNull(PracticeSyncService.currentMemberRole(
                List.of(role("A", ASOF.minusYears(1), ASOF)), ASOF));
        assertEquals("A", PracticeSyncService.currentMemberRole(
                List.of(role("A", ASOF.minusYears(1), ASOF.plusDays(1))), ASOF).getTeamuuid());
    }

    @Test
    void latest_start_wins_when_the_invariant_is_violated() {
        TeamRole older = role("A", ASOF.minusYears(2), null);
        TeamRole newer = role("B", ASOF.minusYears(1), null);
        assertEquals("B", PracticeSyncService.currentMemberRole(List.of(older, newer), ASOF).getTeamuuid());
    }

    @Test
    void null_startdate_counts_as_open_past() {
        assertEquals("A", PracticeSyncService.currentMemberRole(
                List.of(role("A", null, null)), ASOF).getTeamuuid());
    }

    // ── staleDerivedEvidence: team-less manual-vs-stale decision ──────────

    @Test
    void membership_ended_after_the_current_period_began_is_evidence() {
        TeamRole ended = role("A", ASOF.minusYears(1), ASOF.minusDays(2));
        assertEquals("A", PracticeSyncService.staleDerivedEvidence(
                List.of(ended), ASOF, ASOF.minusDays(10)).getTeamuuid());
    }

    @Test
    void membership_ended_before_the_current_period_began_is_not_evidence() {
        // The documented team-less users: their value is manual, not stale.
        TeamRole longGone = role("A", ASOF.minusYears(3), ASOF.minusYears(2));
        assertNull(PracticeSyncService.staleDerivedEvidence(
                List.of(longGone), ASOF, ASOF.minusDays(10)));
    }

    @Test
    void open_and_future_ended_roles_are_never_evidence() {
        TeamRole open = role("A", ASOF.minusYears(1), null);
        TeamRole futureEnd = role("B", ASOF.minusYears(1), ASOF.plusDays(5));
        assertNull(PracticeSyncService.staleDerivedEvidence(List.of(open, futureEnd), ASOF, null));
    }

    @Test
    void without_history_any_ended_role_is_evidence_and_the_latest_end_wins() {
        TeamRole first = role("A", ASOF.minusYears(3), ASOF.minusYears(2));
        TeamRole second = role("B", ASOF.minusYears(2), ASOF.minusYears(1));
        assertEquals("B", PracticeSyncService.staleDerivedEvidence(
                List.of(first, second), ASOF, null).getTeamuuid());
    }

    @Test
    void enddate_exactly_on_the_anchor_is_not_evidence() {
        // enddate must be strictly after the anchor — a transition already
        // recorded on that date must not re-fire (convergence).
        LocalDate anchor = ASOF.minusDays(5);
        assertNull(PracticeSyncService.staleDerivedEvidence(
                List.of(role("A", ASOF.minusYears(1), anchor)), ASOF, anchor));
    }

    // ── latestEndedOn (forced-UD date for deletions/re-types) ─────────────

    @Test
    void latest_ended_on_picks_the_teams_latest_past_end() {
        List<TeamRole> roles = List.of(
                role("A", ASOF.minusYears(2), ASOF.minusYears(1)),
                role("A", ASOF.minusMonths(6), ASOF.minusDays(3)),
                role("B", ASOF.minusMonths(2), ASOF.minusDays(1)));
        assertEquals(ASOF.minusDays(3), PracticeSyncService.latestEndedOn(roles, "A", ASOF));
        assertNull(PracticeSyncService.latestEndedOn(roles, "C", ASOF));
    }

    // ── planTransition: the V407/V424 trigger semantics + the clamp ───────
    // Uuid-only since Phase 5A — the stored legacy code plays no part.

    private static HistoryState openRow(LocalDate from, String uuid) {
        return new HistoryState("open-row", from, uuid, null);
    }

    @Test
    void unchanged_practice_is_a_noop() {
        TransitionPlan plan = PracticeSyncService.planTransition(
                openRow(ASOF.minusDays(5), "uuid-sa"), ASOF, "uuid-sa");
        assertEquals(TransitionAction.NOOP, plan.action());
    }

    @Test
    void different_uuid_is_not_a_noop() {
        // A uuid re-mint (staging refresh) must converge on the next derivation.
        TransitionPlan plan = PracticeSyncService.planTransition(
                openRow(ASOF.minusDays(5), "uuid-old"), ASOF, "uuid-new");
        assertEquals(TransitionAction.CLOSE_AND_INSERT, plan.action());
    }

    @Test
    void normal_change_closes_the_open_row_and_inserts_at_the_triggering_date() {
        TransitionPlan plan = PracticeSyncService.planTransition(
                openRow(ASOF.minusDays(5), "uuid-sa"), ASOF, "uuid-cyb");
        assertEquals(TransitionAction.CLOSE_AND_INSERT, plan.action());
        assertEquals(ASOF, plan.effectiveFrom());
        assertFalse(plan.clamped());
    }

    @Test
    void change_on_the_open_rows_own_date_collapses_in_place() {
        // The triggers' same-day collapse — also the only CHECK-safe action
        // (effective_to must be strictly greater than effective_from).
        TransitionPlan plan = PracticeSyncService.planTransition(
                openRow(ASOF, "uuid-sa"), ASOF, "uuid-cyb");
        assertEquals(TransitionAction.UPDATE_OPEN_ROW, plan.action());
        assertEquals(ASOF, plan.effectiveFrom());
        assertFalse(plan.clamped());
    }

    @Test
    void backdated_transition_clamps_to_the_open_rows_effective_from() {
        // Retroactive rewrites are out of scope: a triggering date before the
        // open row clamps onto it (and collapses in place, with a warning).
        TransitionPlan plan = PracticeSyncService.planTransition(
                openRow(ASOF.minusDays(5), "uuid-sa"), ASOF.minusDays(20), "uuid-cyb");
        assertEquals(TransitionAction.UPDATE_OPEN_ROW, plan.action());
        assertEquals(ASOF.minusDays(5), plan.effectiveFrom());
        assertTrue(plan.clamped());
    }

    @Test
    void backdated_between_open_row_and_today_is_honoured_unclamped() {
        // e.g. the tick materializing a membership end from two days ago —
        // deriving to NULL (no practice), the Phase 4 operational truth.
        TransitionPlan plan = PracticeSyncService.planTransition(
                openRow(ASOF.minusDays(5), "uuid-sa"), ASOF.minusDays(2), null);
        assertEquals(TransitionAction.CLOSE_AND_INSERT, plan.action());
        assertEquals(ASOF.minusDays(2), plan.effectiveFrom());
        assertFalse(plan.clamped());
    }

    @Test
    void no_history_inserts_a_fresh_open_row() {
        TransitionPlan plan = PracticeSyncService.planTransition(
                new HistoryState(null, null, null, null), ASOF, "uuid-sa");
        assertEquals(TransitionAction.INSERT_OPEN_ROW, plan.action());
        assertEquals(ASOF, plan.effectiveFrom());
        assertFalse(plan.clamped());
    }

    @Test
    void closed_only_history_clamps_the_new_open_row_to_the_latest_close() {
        // Keeps the timeline contiguous and collision-free under the
        // (useruuid, effective_from) unique key.
        TransitionPlan plan = PracticeSyncService.planTransition(
                new HistoryState(null, null, null, ASOF.minusDays(3)),
                ASOF.minusDays(10), "uuid-sa");
        assertEquals(TransitionAction.INSERT_OPEN_ROW, plan.action());
        assertEquals(ASOF.minusDays(3), plan.effectiveFrom());
        assertTrue(plan.clamped());
    }

    // ── small helpers ─────────────────────────────────────────────────────

    @Test
    void latest_of_is_null_safe_max() {
        assertEquals(ASOF, PracticeSyncService.latestOf(ASOF, ASOF.minusDays(1)));
        assertEquals(ASOF, PracticeSyncService.latestOf(ASOF.minusDays(1), ASOF));
        assertEquals(ASOF, PracticeSyncService.latestOf(ASOF, null));
        assertEquals(ASOF, PracticeSyncService.latestOf(null, ASOF));
        assertNull(PracticeSyncService.latestOf(null, null));
    }

}
