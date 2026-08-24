package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentLandingService.taskInScope;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentLandingService.taskPositionUuids;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast-tier pin of "My tasks" scoping
 * ({@code RecruitmentLandingService.taskInScope}) — added after every one of
 * the twenty production team leads was found carrying the same 34-application
 * task universe, because go-live decision D3 lets a {@code TEAMLEAD} decide
 * on every non-partner position in the company. The card now answers the
 * narrower question "is this mine to worry about?"; rights are untouched
 * (pinned separately by
 * {@code RecruitmentVisibilityAccessModelTest.ledPractice_grantsNoReadOrDecideRight}).
 *
 * <p>The landing API test is a {@code @QuarkusTest} outside the CI deploy
 * gate; this file is what actually blocks a regression.</p>
 */
class RecruitmentLandingTaskScopeTest {

    private static final String LED_PRACTICE = "practice-led";
    private static final String OTHER_PRACTICE = "practice-other";
    private static final String LED_TEAM = "team-led";

    private static final Set<String> LED_PRACTICES = Set.of(LED_PRACTICE);
    private static final Set<String> LED_TEAMS = Set.of(LED_TEAM);
    private static final Set<String> NONE = Set.of();

    private static RecruitmentPosition position(String uuid, String practiceUuid,
                                                String teamUuid) {
        RecruitmentPosition position = new RecruitmentPosition();
        position.setUuid(uuid);
        position.setHiringTrack(RecruitmentHiringTrack.PRACTICE_TEAM);
        position.setPracticeUuid(practiceUuid);
        position.setTeamUuid(teamUuid);
        return position;
    }

    /**
     * Production shape: {@code recruitment_positions.team_uuid} is NULL for
     * every row, so the practice hop is the only one that ever fires.
     */
    private static final RecruitmentPosition IN_LED_PRACTICE =
            position("pos-in", LED_PRACTICE, null);
    private static final RecruitmentPosition IN_OTHER_PRACTICE =
            position("pos-out", OTHER_PRACTICE, null);
    /** A team whose practice is NULL — reachable by the team hop only. */
    private static final RecruitmentPosition ON_LED_TEAM_NO_PRACTICE =
            position("pos-team", null, LED_TEAM);
    private static final RecruitmentPosition PRACTICELESS_TEAMLESS =
            position("pos-bare", null, null);

    // ---- The recruiter tier is never narrowed -----------------------------------

    @Test
    void recruiterTier_keepsEverything_evenWithNoTeamAndNoPractice() {
        assertTrue(taskInScope(IN_OTHER_PRACTICE, true, NONE, NONE, NONE),
                "the world is the recruiter's job (spec §6.1)");
        assertTrue(taskInScope(PRACTICELESS_TEAMLESS, true, NONE, NONE, NONE));
        assertTrue(taskInScope(null, true, NONE, NONE, NONE),
                "and short-circuits before the position is even needed");
    }

    // ---- The lead's own practice ------------------------------------------------

    @Test
    void lead_seesTheirOwnPractice_andNotTheRest() {
        assertTrue(taskInScope(IN_LED_PRACTICE, false, LED_PRACTICES, NONE, NONE),
                "a position on the practice of a team they currently lead");
        assertFalse(taskInScope(IN_OTHER_PRACTICE, false, LED_PRACTICES, NONE, NONE),
                "the other five practices' openings are not their problem —"
                        + " they can still read and decide on them");
    }

    /**
     * The load-bearing second hop: {@code RecruitmentVisibility} treats
     * "current lead of the position's team" as a first-class grant in three
     * places, and production has a lead of a team whose practice is NULL.
     * Dropping this term would silently empty their card.
     */
    @Test
    void leadOfAPracticelessTeam_stillSeesThatTeamsPosition() {
        assertTrue(taskInScope(ON_LED_TEAM_NO_PRACTICE, false, NONE, LED_TEAMS, NONE));
        assertFalse(taskInScope(ON_LED_TEAM_NO_PRACTICE, false, LED_PRACTICES, NONE, NONE),
                "a NULL practice matches no led practice — no null-equals-null hop");
    }

    // ---- No team ⇒ no tasks, by construction ------------------------------------

    @Test
    void noTeamAndNoRoles_scopesToNothing() {
        assertFalse(taskInScope(IN_LED_PRACTICE, false, NONE, NONE, NONE));
        assertFalse(taskInScope(IN_OTHER_PRACTICE, false, NONE, NONE, NONE));
        assertFalse(taskInScope(ON_LED_TEAM_NO_PRACTICE, false, NONE, NONE, NONE));
        assertFalse(taskInScope(PRACTICELESS_TEAMLESS, false, NONE, NONE, NONE),
                "a TEAMLEAD who leads no team gets an empty card, not the company's");
    }

    // ---- Already theirs: named ownership, circle seat, assistant practice --------

    /**
     * {@code ownPositionUuids} carries the named hiring owner, every circle
     * seat and the assistant's own practice. A role-less circle owner has no
     * led practice and no led team, so this term is the only thing keeping
     * their partner slice on the card.
     */
    @Test
    void circleOwnerOrNamedOwner_keepsTheirSlice_withoutLeadingAnything() {
        RecruitmentPosition partner = position("pos-partner", OTHER_PRACTICE, null);
        partner.setHiringTrack(RecruitmentHiringTrack.PARTNER);

        assertTrue(taskInScope(partner, false, NONE, NONE, Set.of("pos-partner")));
        assertFalse(taskInScope(partner, false, NONE, NONE, NONE),
                "…and nothing else reaches it");
    }

    @Test
    void aPositionIsNeverReachedTwice_theRoutesAreAUnion() {
        assertTrue(taskInScope(IN_LED_PRACTICE, false, LED_PRACTICES, LED_TEAMS,
                Set.of("pos-in")), "all three routes agreeing is still one row");
    }

    // ---- Null safety ------------------------------------------------------------

    @Test
    void aMissingPosition_failsClosed_forEveryoneBelowTheRecruiterTier() {
        assertFalse(taskInScope(null, false, LED_PRACTICES, LED_TEAMS, Set.of("pos-in")),
                "a decidable uuid with no position in hand must not become a task");
    }

    // ---- The wiring, not just the predicate -------------------------------------

    private static final Map<String, RecruitmentPosition> BY_UUID = Map.of(
            "pos-in", IN_LED_PRACTICE,
            "pos-out", IN_OTHER_PRACTICE,
            "pos-team", ON_LED_TEAM_NO_PRACTICE,
            "pos-bare", PRACTICELESS_TEAMLESS);

    @Test
    void taskPositions_keepOnlyTheDecidableOnesInScope() {
        Set<String> decidable = Set.of("pos-in", "pos-out", "pos-team", "pos-bare");
        assertEquals(Set.of("pos-in", "pos-team"),
                taskPositionUuids(decidable, BY_UUID, false, LED_PRACTICES, LED_TEAMS, NONE),
                "the led practice and the led team survive; the rest do not");
    }

    @Test
    void taskPositions_areAlwaysASubsetOfTheDecidableSlice() {
        // The load-bearing property. A union here would hand the viewer task
        // rows for a position they may not act on, and a taskInScope-only
        // test would still pass.
        Set<String> decidable = Set.of("pos-out");
        Set<String> result = taskPositionUuids(decidable, BY_UUID, false,
                LED_PRACTICES, LED_TEAMS, Set.of("pos-in", "pos-team"));
        assertTrue(decidable.containsAll(result),
                "in scope but not decidable must never become a task");
        assertTrue(result.isEmpty(), "nothing decidable was in scope");
    }

    @Test
    void taskPositions_leaveTheRecruiterTierWhole() {
        Set<String> decidable = Set.of("pos-in", "pos-out", "pos-team", "pos-bare");
        assertEquals(decidable,
                taskPositionUuids(decidable, BY_UUID, true, NONE, NONE, NONE),
                "the recruiter tier is never narrowed — the world is their job");
    }

    @Test
    void taskPositions_areEmptyForALeadWithNoTeam() {
        // "No team means no tasks": every set empty, below the recruiter
        // tier, collapses the whole card.
        Set<String> decidable = Set.of("pos-in", "pos-out", "pos-team", "pos-bare");
        assertTrue(taskPositionUuids(decidable, BY_UUID, false, NONE, NONE, NONE).isEmpty(),
                "a TEAMLEAD who leads nothing gets no decision or idle rows");
    }

    @Test
    void taskPositions_failClosedOnAUuidWithNoPositionInHand() {
        assertTrue(taskPositionUuids(Set.of("pos-ghost"), BY_UUID, false,
                        LED_PRACTICES, LED_TEAMS, NONE).isEmpty(),
                "an unresolvable uuid must not slip through as a task");
    }
}
