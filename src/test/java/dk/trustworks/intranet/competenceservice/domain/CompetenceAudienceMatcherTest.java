package dk.trustworks.intranet.competenceservice.domain;

import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher.Subject;
import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher.Targeting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §5.2. The union rule, and above all the regression it exists to prevent.
 */
class CompetenceAudienceMatcherTest {

    private static final String TECH = "practice-tech";
    private static final String CYB = "practice-cyb";
    private static final String TEAM_A = "team-a";
    private static final String TEAM_B = "team-b";
    private static final String ANNA = "user-anna";
    private static final String BO = "user-bo";

    /** An ordinary consultant sitting in a practice. */
    private static Subject consultant(String uuid, String practiceUuid, String... teams) {
        return new Subject(uuid, true, practiceUuid, Set.of(teams), Set.of());
    }

    /**
     * A team lead: no practice of their own — their only MEMBER role is on the
     * practice-less "Teamleads" org team — but LEADER on a team that does belong to one.
     */
    private static Subject teamLead(String uuid, String ledTeam, String ledTeamPractice) {
        return new Subject(uuid, true, null, Set.of(ledTeam, "team-teamleads"), Set.of(ledTeamPractice));
    }

    private static Targeting practices(String... uuids) {
        return new Targeting(List.of(uuids), null, null);
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("all three arrays absent targets everyone")
    void untargetedReachesEveryone() {
        Targeting none = new Targeting(null, null, null);
        assertTrue(CompetenceAudienceMatcher.inAudience(consultant(ANNA, TECH), none));
        assertTrue(CompetenceAudienceMatcher.inAudience(consultant(BO, null), none));
        assertTrue(CompetenceAudienceMatcher.inAudience(teamLead(ANNA, TEAM_A, TECH), none));
    }

    @Test
    @DisplayName("'[]' in every array targets nobody — a parked requirement")
    void explicitlyEmptyReachesNobody() {
        Targeting parked = new Targeting(List.of(), List.of(), List.of());
        assertFalse(CompetenceAudienceMatcher.inAudience(consultant(ANNA, TECH), parked));
        assertFalse(CompetenceAudienceMatcher.inAudience(teamLead(BO, TEAM_A, TECH), parked));
    }

    @Test
    void practiceOnlyTargeting() {
        assertTrue(CompetenceAudienceMatcher.inAudience(consultant(ANNA, TECH), practices(TECH)));
        assertFalse(CompetenceAudienceMatcher.inAudience(consultant(BO, CYB), practices(TECH)));
    }

    @Test
    void teamOnlyTargeting() {
        Targeting t = new Targeting(null, List.of(TEAM_A), null);
        assertTrue(CompetenceAudienceMatcher.inAudience(consultant(ANNA, CYB, TEAM_A), t));
        assertFalse(CompetenceAudienceMatcher.inAudience(consultant(BO, CYB, TEAM_B), t));
    }

    @Test
    void namedUserTargeting() {
        Targeting t = new Targeting(null, null, List.of(ANNA));
        assertTrue(CompetenceAudienceMatcher.inAudience(consultant(ANNA, CYB), t));
        assertFalse(CompetenceAudienceMatcher.inAudience(consultant(BO, CYB), t));
    }

    @Test
    @DisplayName("the union: any one arm is enough")
    void unionOfTwoDimensions() {
        Targeting t = new Targeting(List.of(TECH), List.of(TEAM_B), null);
        // practice arm only
        assertTrue(CompetenceAudienceMatcher.inAudience(consultant(ANNA, TECH, TEAM_A), t));
        // team arm only
        assertTrue(CompetenceAudienceMatcher.inAudience(consultant(BO, CYB, TEAM_B), t));
        // neither
        assertFalse(CompetenceAudienceMatcher.inAudience(consultant("user-c", CYB, TEAM_A), t));
    }

    // -----------------------------------------------------------------------
    // The reason this rule is not questionnaire semantics.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION: a practice-less team lead leading a TECH team IS in the TECH audience")
    void practicelessTeamLeadIsIncludedViaLeadership() {
        // Under questionnaire AND-semantics this person resolves to practice_uuid = null
        // and is silently excluded — along with all nine team leads, who are exactly the
        // people approving the pull requests this module is about.
        Subject lead = teamLead(ANNA, TEAM_A, TECH);
        assertTrue(CompetenceAudienceMatcher.inAudience(lead, practices(TECH)));
    }

    @Test
    @DisplayName("but leading a team in a DIFFERENT practice does not include them")
    void leadershipOfAnotherPracticesTeamDoesNotInclude() {
        Subject lead = teamLead(BO, TEAM_B, CYB);
        assertFalse(CompetenceAudienceMatcher.inAudience(lead, practices(TECH)));
    }

    @Test
    @DisplayName("a SPONSOR counts the same as a LEADER")
    void sponsorshipAlsoCounts() {
        Subject sponsor = new Subject("user-sponsor", true, null, Set.of(TEAM_A), Set.of(TECH));
        assertTrue(CompetenceAudienceMatcher.inAudience(sponsor, practices(TECH)));
    }

    @Test
    @DisplayName("a plain MEMBER of a TECH team is not pulled in by the practice arm")
    void plainMembershipDoesNotImplyPracticeMembership() {
        // Only LEADER/SPONSOR feed ledOrSponsoredPracticeUuids; an ordinary member of a
        // TECH team who sits in another practice is reached by team targeting, not by
        // practice targeting.
        Subject member = new Subject("user-m", true, CYB, Set.of(TEAM_A), Set.of());
        assertFalse(CompetenceAudienceMatcher.inAudience(member, practices(TECH)));
        assertTrue(CompetenceAudienceMatcher.inAudience(member, new Targeting(null, List.of(TEAM_A), null)));
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("expired team roles are excluded by the caller, so an empty set never matches")
    void expiredRolesContributeNothing() {
        // The active-role predicate lives in the resolver; here the contract is simply
        // that a subject whose active sets are empty matches only untargeted requirements.
        Subject expired = new Subject(ANNA, true, null, Set.of(), Set.of());
        assertFalse(CompetenceAudienceMatcher.inAudience(expired, practices(TECH)));
        assertFalse(CompetenceAudienceMatcher.inAudience(expired, new Targeting(null, List.of(TEAM_A), null)));
        assertTrue(CompetenceAudienceMatcher.inAudience(expired, new Targeting(null, null, null)));
    }

    @Test
    @DisplayName("terminated employees are in no audience, however they are targeted")
    void inactiveEmployeeIsNeverInAudience() {
        Subject terminated = new Subject(ANNA, false, TECH, Set.of(TEAM_A), Set.of(TECH));
        assertFalse(CompetenceAudienceMatcher.inAudience(terminated, practices(TECH)));
        assertFalse(CompetenceAudienceMatcher.inAudience(terminated, new Targeting(null, null, null)));
        assertFalse(CompetenceAudienceMatcher.inAudience(terminated, new Targeting(null, null, List.of(ANNA))));
    }

    @Test
    void nullsAreRefusedRatherThanThrowing() {
        assertFalse(CompetenceAudienceMatcher.inAudience(null, practices(TECH)));
        assertFalse(CompetenceAudienceMatcher.inAudience(consultant(ANNA, TECH), null));
    }
}
