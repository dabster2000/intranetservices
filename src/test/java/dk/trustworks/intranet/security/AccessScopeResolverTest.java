package dk.trustworks.intranet.security;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import dk.trustworks.intranet.userservice.services.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of the Phase 8 scope resolvers (tasks 8.2 + 8.3),
 * centred on AC-11: <em>a team lead who changed teams in March must not read
 * their former team's April data</em> — the classic quiet bug in temporal team
 * resolution. Phase 8 policy makes {@code asOf} the day of the check (owner
 * decision 2026-08-06), so a lead who left a team reaches none of its members
 * for data of ANY month once the role has ended.
 */
class AccessScopeResolverTest {

    private static final String LEAD = "11111111-0000-0000-0000-000000000001";
    private static final String FORMER_TEAM = "team-former";
    private static final String NEW_TEAM = "team-new";

    private TeamService teamService;
    private AccessScopeResolver resolver;
    private List<TeamRole> ledRoles;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        resolver = new AccessScopeResolver() {
            @Override
            List<TeamRole> ledTeamRoles(String actorUuid) {
                return ledRoles;
            }
        };
        resolver.teamService = teamService;
    }

    // ------------------------------------------------------------------
    // AC-11 — the mover case, pinned on the pure predicate
    // ------------------------------------------------------------------

    @Test
    void moverCase_leadWhoChangedTeamsInMarchDoesNotKeepFormerTeamAccess() {
        // LEADER of the former team until 15 March, LEADER of the new team since.
        TeamRole formerRole = new TeamRole("r1", FORMER_TEAM, LEAD,
                LocalDate.of(2024, 1, 1), LocalDate.of(2026, 3, 15), TeamMemberType.LEADER);
        TeamRole newRole = new TeamRole("r2", NEW_TEAM, LEAD,
                LocalDate.of(2026, 3, 15), null, TeamMemberType.LEADER);

        LocalDate checkedInApril = LocalDate.of(2026, 4, 20);
        assertFalse(AccessScopeResolver.isActiveLeadAsOf(formerRole, checkedInApril),
                "AC-11: the ended LEADER role must not be active after the move");
        assertTrue(AccessScopeResolver.isActiveLeadAsOf(newRole, checkedInApril));
    }

    @Test
    void moverCase_endToEnd_formerTeamMembersAreNotResolved() {
        TeamRole formerRole = new TeamRole("r1", FORMER_TEAM, LEAD,
                LocalDate.of(2024, 1, 1), LocalDate.of(2026, 3, 15), TeamMemberType.LEADER);
        TeamRole newRole = new TeamRole("r2", NEW_TEAM, LEAD,
                LocalDate.of(2026, 3, 15), null, TeamMemberType.LEADER);
        ledRoles = List.of(formerRole, newRole);

        LocalDate asOf = LocalDate.of(2026, 4, 20);
        when(teamService.getUsersByTeamIncludingPreboarding(NEW_TEAM, asOf))
                .thenReturn(List.of(user("new-member")));

        Set<String> subjects = resolver.resolveTeam(LEAD, asOf);

        assertEquals(Set.of(LEAD, "new-member"), subjects);
        verify(teamService, never()).getUsersByTeamIncludingPreboarding(eq(FORMER_TEAM), any());
    }

    // ------------------------------------------------------------------
    // Temporal edges of the canonical predicate
    // ------------------------------------------------------------------

    @Test
    void roleEndingTodayIsAlreadyInactive_andFutureRoleIsNotYetActive() {
        LocalDate today = LocalDate.of(2026, 8, 6);
        TeamRole endsToday = new TeamRole("r1", FORMER_TEAM, LEAD,
                LocalDate.of(2024, 1, 1), today, TeamMemberType.LEADER);
        TeamRole startsTomorrow = new TeamRole("r2", NEW_TEAM, LEAD,
                today.plusDays(1), null, TeamMemberType.LEADER);

        assertFalse(AccessScopeResolver.isActiveLeadAsOf(endsToday, today),
                "enddate is exclusive — the canonical predicate, stricter than the retired BFF guard");
        assertFalse(AccessScopeResolver.isActiveLeadAsOf(startsTomorrow, today),
                "a future-dated leadership must not grant reach yet");
    }

    @Test
    void memberRoleNeverGrantsLeadReach_andNullStartdateFailsClosed() {
        LocalDate today = LocalDate.of(2026, 8, 6);
        TeamRole memberRole = new TeamRole("r1", FORMER_TEAM, LEAD,
                LocalDate.of(2024, 1, 1), null, TeamMemberType.MEMBER);
        TeamRole dirtyRole = new TeamRole("r2", FORMER_TEAM, LEAD,
                null, null, TeamMemberType.LEADER);

        assertFalse(AccessScopeResolver.isActiveLeadAsOf(memberRole, today));
        assertFalse(AccessScopeResolver.isActiveLeadAsOf(dirtyRole, today),
                "a NULL startdate (pre-V422 dirt) must deny, not grant");
    }

    @Test
    void sponsorGrantsTeamReachLikeLeader() {
        TeamRole sponsorRole = new TeamRole("r1", FORMER_TEAM, LEAD,
                LocalDate.of(2024, 1, 1), null, TeamMemberType.SPONSOR);
        ledRoles = List.of(sponsorRole);

        LocalDate asOf = LocalDate.of(2026, 8, 6);
        when(teamService.getUsersByTeamIncludingPreboarding(FORMER_TEAM, asOf))
                .thenReturn(List.of(user("sponsored-member")));

        assertEquals(Set.of(LEAD, "sponsored-member"), resolver.resolveTeam(LEAD, asOf));
    }

    @Test
    void teamReachAlwaysIncludesTheActorThemselves() {
        ledRoles = List.of();
        assertEquals(Set.of(LEAD), resolver.resolveTeam(LEAD, LocalDate.of(2026, 8, 6)),
                "a lead with no active teams still reaches their own record");
    }

    private static User user(String uuid) {
        User user = new User();
        user.setUuid(uuid);
        return user;
    }
}
