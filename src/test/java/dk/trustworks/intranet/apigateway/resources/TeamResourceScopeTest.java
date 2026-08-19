package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.AuthorizationService;
import dk.trustworks.intranet.security.DataScope;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeEnforced;
import dk.trustworks.intranet.security.ScopeGuard;
import dk.trustworks.intranet.security.ScopeResolution;
import dk.trustworks.intranet.security.TestScopeGuards;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.services.TeamLogoService;
import dk.trustworks.intranet.userservice.services.TeamService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of Phase 10.2 enforcement on team mutations
 * (access-intent Decision 10). Today's only human write audience is HR/ADMIN
 * (unbounded after V473) through ADMIN-gated BFF routes that already send the
 * actor header — so every guard passes for every real actor today. The
 * bounded-lead cases pin the behaviour Decision 10 promises for a future
 * TEAMLEAD membership UI: edit your own team's membership, nothing else.
 */
class TeamResourceScopeTest {

    private static final String ACTOR = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String LED_TEAM = "team-led-0000-0000-000000000001";
    private static final String OTHER_TEAM = "team-oth-0000-0000-000000000002";

    private TeamResource resource;
    private TeamService teamService;
    private TeamLogoService teamLogoService;
    private AuthorizationService authorizationService;
    private RequestHeaderHolder headers;
    private TeamRole stubbedRow;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        teamLogoService = mock(TeamLogoService.class);
        authorizationService = mock(AuthorizationService.class);
        headers = mock(RequestHeaderHolder.class);
        stubbedRow = null;

        resource = new TeamResource() {
            @Override
            TeamRole findTeamRole(String teamroleuuid) {
                return stubbedRow;
            }
        };
        resource.teamService = teamService;
        resource.teamLogoService = teamLogoService;
        resource.requestHeaderHolder = headers;
        resource.scope = TestScopeGuards.wired(authorizationService, headers);
    }

    private void actorIs(String actor) {
        when(headers.getUserUuid()).thenReturn(actor);
    }

    private void writeReachIs(ScopeResolution resolution) {
        when(authorizationService.resolveReach(eq(ACTOR), eq(TeamResource.TEAMS_WRITE), any(), anySet()))
                .thenReturn(resolution);
    }

    private void actorLeads(String teamuuid) {
        User lead = new User();
        lead.setUuid(ACTOR);
        when(teamService.getTeamLeadersByTeam(eq(teamuuid), any())).thenReturn(List.of(lead));
    }

    // ---- Today's world: HR/ADMIN unbounded, headerless machine callers ------

    @Test
    void unboundedActorEditsAnyTeamMembership() {
        actorIs(ACTOR);
        writeReachIs(ScopeResolution.unboundedAll());
        resource.addUserToTeam(OTHER_TEAM, new TeamRole());
        verify(teamService).addTeamroleToUser(eq(OTHER_TEAM), any());
        verify(teamService, never()).getTeamLeadersByTeam(any(), any());
    }

    @Test
    void headerlessCallerIsUntouched() {
        actorIs(null);
        resource.addUserToTeam(OTHER_TEAM, new TeamRole());
        verify(teamService).addTeamroleToUser(eq(OTHER_TEAM), any());
        verify(authorizationService, never()).resolveReach(any(), any(), any(), anySet());
    }

    // ---- Decision 10: a bounded lead edits exactly their own team -----------

    @Test
    void boundedLeadEditsOwnTeamMembership() {
        actorIs(ACTOR);
        writeReachIs(ScopeResolution.bounded(DataScope.TEAM, Set.of(ACTOR)));
        actorLeads(LED_TEAM);
        resource.addUserToTeam(LED_TEAM, new TeamRole());
        verify(teamService).addTeamroleToUser(eq(LED_TEAM), any());
    }

    @Test
    void boundedLeadIs403OnAnotherTeam() {
        actorIs(ACTOR);
        writeReachIs(ScopeResolution.bounded(DataScope.TEAM, Set.of(ACTOR)));
        when(teamService.getTeamLeadersByTeam(eq(OTHER_TEAM), any())).thenReturn(List.of());
        assertThrows(ForbiddenException.class,
                () -> resource.addUserToTeam(OTHER_TEAM, new TeamRole()));
        verify(teamService, never()).addTeamroleToUser(any(), any());
    }

    @Test
    void actorWithoutTeamsWriteIs403() {
        actorIs(ACTOR);
        writeReachIs(ScopeResolution.none());
        assertThrows(ForbiddenException.class,
                () -> resource.addUserToTeam(LED_TEAM, new TeamRole()));
        verify(teamService, never()).addTeamroleToUser(any(), any());
    }

    @Test
    void deleteChecksTheRowsOwnTeamNotThePathSegment() {
        // A bounded lead of LED_TEAM addresses another team's row under their
        // own team's URL — the guard must judge the row's team and refuse.
        actorIs(ACTOR);
        writeReachIs(ScopeResolution.bounded(DataScope.TEAM, Set.of(ACTOR)));
        actorLeads(LED_TEAM);
        when(teamService.getTeamLeadersByTeam(eq(OTHER_TEAM), any())).thenReturn(List.of());
        stubbedRow = new TeamRole();
        stubbedRow.setTeamuuid(OTHER_TEAM);
        assertThrows(ForbiddenException.class,
                () -> resource.deleteTeamRole(LED_TEAM, "role-row-uuid"));
        verify(teamService, never()).removeUserFromTeam(any());
    }

    // ---- Team identity (logo): LEADER *or SPONSOR* of that one team ---------

    private static final TeamResource.UpdateTeamLogoRequest LOGO =
            new TeamResource.UpdateTeamLogoRequest("logo.jpg", "aGVsbG8=");

    private void actorLeadsOrSponsors(String teamuuid) {
        when(teamService.isLeaderOrSponsor(eq(teamuuid), eq(ACTOR), any())).thenReturn(true);
    }

    @Test
    void unboundedActorChangesAnyTeamLogo() {
        actorIs(ACTOR);
        writeReachIs(ScopeResolution.unboundedAll());
        resource.updateTeamLogo(OTHER_TEAM, LOGO);
        verify(teamLogoService).updateTeamLogo(eq(OTHER_TEAM), eq("aGVsbG8="), eq("logo.jpg"));
        verify(teamService, never()).isLeaderOrSponsor(any(), any(), any());
    }

    @Test
    void headerlessCallerChangesLogoUnchecked() {
        actorIs(null);
        resource.updateTeamLogo(OTHER_TEAM, LOGO);
        verify(teamLogoService).updateTeamLogo(eq(OTHER_TEAM), any(), any());
        verify(authorizationService, never()).resolveReach(any(), any(), any(), anySet());
    }

    @Test
    void boundedLeadChangesOwnTeamLogo() {
        actorIs(ACTOR);
        writeReachIs(ScopeResolution.bounded(DataScope.TEAM, Set.of(ACTOR)));
        actorLeadsOrSponsors(LED_TEAM);
        resource.updateTeamLogo(LED_TEAM, LOGO);
        verify(teamLogoService).updateTeamLogo(eq(LED_TEAM), any(), any());
    }

    @Test
    void boundedActorIs403OnAnotherTeamsLogo() {
        actorIs(ACTOR);
        writeReachIs(ScopeResolution.bounded(DataScope.TEAM, Set.of(ACTOR)));
        when(teamService.isLeaderOrSponsor(eq(OTHER_TEAM), eq(ACTOR), any())).thenReturn(false);
        assertThrows(ForbiddenException.class, () -> resource.updateTeamLogo(OTHER_TEAM, LOGO));
        verify(teamLogoService, never()).updateTeamLogo(any(), any(), any());
    }

    @Test
    void actorWithoutTeamsWriteIs403OnLogo() {
        actorIs(ACTOR);
        writeReachIs(ScopeResolution.none());
        assertThrows(ForbiddenException.class, () -> resource.updateTeamLogo(LED_TEAM, LOGO));
        verify(teamLogoService, never()).updateTeamLogo(any(), any(), any());
    }

    @Test
    void logoRequestWithoutBytesIs400BeforeAnyReachLookup() {
        // The 400 must precede the guard: an empty body is a client mistake, and
        // resolving reach first would report it as a permissions problem.
        actorIs(ACTOR);
        assertThrows(BadRequestException.class,
                () -> resource.updateTeamLogo(LED_TEAM, new TeamResource.UpdateTeamLogoRequest("logo.jpg", "  ")));
        assertThrows(BadRequestException.class, () -> resource.updateTeamLogo(LED_TEAM, null));
        verify(teamLogoService, never()).updateTeamLogo(any(), any(), any());
        verify(authorizationService, never()).resolveReach(any(), any(), any(), anySet());
    }

    @Test
    void logoWriteIsNotScopeEnforced() {
        // @ScopeEnforced demands an ALL-scope grant and would lock out the
        // TEAM-scoped teams:write TEAMLEAD holds (V473) — the whole audience
        // this endpoint exists for.
        Method m = Arrays.stream(TeamResource.class.getDeclaredMethods())
                .filter(x -> x.getName().equals("updateTeamLogo")).findFirst().orElseThrow();
        assertFalse(m.isAnnotationPresent(ScopeEnforced.class),
                "updateTeamLogo must NOT be @ScopeEnforced — bounded team leads are its audience");
    }

    // ---- Whole-team writes stay unbounded-only ------------------------------

    @Test
    void wholeTeamWritesAreScopeEnforced() {
        // A bounded TEAMLEAD@TEAM may edit membership — never create teams,
        // rename them, move practices or flip team settings.
        for (String name : List.of("createTeam", "updateTeam", "updateTeamPractice",
                "updateTeamSetting", "regenerateDescriptions")) {
            Method m = Arrays.stream(TeamResource.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertTrue(m.isAnnotationPresent(ScopeEnforced.class),
                    name + " must be @ScopeEnforced — whole-team mutations are unbounded-only");
        }
    }

    @Test
    void membershipWritesAreNotScopeEnforced() {
        // @ScopeEnforced would deny the bounded lead Decision 10 admits; the
        // own-team guard replaces it on exactly these two endpoints.
        for (String name : List.of("addUserToTeam", "deleteTeamRole")) {
            Method m = Arrays.stream(TeamResource.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertFalse(m.isAnnotationPresent(ScopeEnforced.class),
                    name + " must NOT be @ScopeEnforced — Decision 10 admits bounded leads here");
        }
    }
}
