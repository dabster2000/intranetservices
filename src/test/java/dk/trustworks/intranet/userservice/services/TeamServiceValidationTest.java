package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DB-free unit tests for the pure single-MEMBER-invariant decision in
 * {@link TeamService} (Phase 0, spec §4.2): a MEMBER role's half-open period
 * [startdate, enddate) must not overlap another MEMBER role of the same user
 * on a different team — future-dated rows count (canonical temporal predicate).
 * Same package so the package-private static is visible.
 */
class TeamServiceValidationTest {

    private static final String TEAM_A = "team-a";
    private static final String TEAM_B = "team-b";

    @Test
    void open_membership_blocks_a_new_open_membership_on_another_team() {
        TeamRole existing = member("r1", TEAM_A, LocalDate.of(2024, 2, 1), null);
        TeamRole conflict = TeamService.findOverlappingMemberRole(
                null, TEAM_B, LocalDate.of(2026, 1, 1), null, List.of(existing));
        assertEquals(existing, conflict);
    }

    @Test
    void future_dated_membership_counts_even_though_it_is_not_current_yet() {
        // enddate IS NULL alone does not mean current — and a future-start row
        // must still block an overlapping new membership.
        TeamRole future = member("r1", TEAM_A, LocalDate.of(2027, 1, 1), null);
        TeamRole conflict = TeamService.findOverlappingMemberRole(
                null, TEAM_B, LocalDate.of(2026, 6, 1), null, List.of(future));
        assertEquals(future, conflict);
    }

    @Test
    void adjacent_periods_do_not_overlap() {
        // Half-open semantics: old membership ends the day the new one starts.
        TeamRole closed = member("r1", TEAM_A, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1));
        assertNull(TeamService.findOverlappingMemberRole(
                null, TEAM_B, LocalDate.of(2024, 3, 1), null, List.of(closed)));
    }

    @Test
    void bounded_new_period_before_an_open_membership_is_free() {
        TeamRole open = member("r1", TEAM_A, LocalDate.of(2025, 1, 1), null);
        assertNull(TeamService.findOverlappingMemberRole(
                null, TEAM_B, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), List.of(open)));
    }

    @Test
    void editing_a_role_does_not_collide_with_itself() {
        TeamRole edited = member("r1", TEAM_A, LocalDate.of(2024, 2, 1), null);
        assertNull(TeamService.findOverlappingMemberRole(
                "r1", TEAM_B, LocalDate.of(2024, 2, 1), null, List.of(edited)));
    }

    @Test
    void same_team_rows_are_ignored() {
        // Re-joining or editing within one team is not a dual membership.
        TeamRole sameTeam = member("r1", TEAM_A, LocalDate.of(2024, 2, 1), null);
        assertNull(TeamService.findOverlappingMemberRole(
                null, TEAM_A, LocalDate.of(2025, 1, 1), null, List.of(sameTeam)));
    }

    @Test
    void first_conflicting_row_is_returned_when_several_overlap() {
        TeamRole first = member("r1", TEAM_A, LocalDate.of(2024, 1, 1), null);
        TeamRole second = member("r2", "team-c", LocalDate.of(2024, 6, 1), null);
        TeamRole conflict = TeamService.findOverlappingMemberRole(
                null, TEAM_B, LocalDate.of(2025, 1, 1), null, List.of(first, second));
        assertEquals(first, conflict);
    }

    private static TeamRole member(String uuid, String teamuuid, LocalDate startdate, LocalDate enddate) {
        return new TeamRole(uuid, teamuuid, "user-1", startdate, enddate, TeamMemberType.MEMBER);
    }
}
