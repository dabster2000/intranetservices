package dk.trustworks.intranet.recruitmentservice.security;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentCircleRole;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast-tier pin of the 2026-08-23 access-model rules
 * ({@code docs/access/recruitment-access-model-target.md}) — decisions 1
 * (read tier decides), 7 (final outcomes need their own gate), 10 (an
 * assistant never creates candidates) and the assistant practice scoping.
 *
 * <p>This exists because the full-fixture proof
 * ({@code RecruitmentVisibilityIntegrationTest}) is a {@code @QuarkusTest}
 * and therefore NOT in the CI deploy gate — without this file, none of the
 * redesign's rules would block a deploy. Same stub-subclass pattern as
 * {@code RecruitmentDossierSelfAttachEscalationTest}: every database lookup
 * is overridden, so the decision logic runs against declared facts.</p>
 */
class RecruitmentVisibilityAccessModelTest {

    private static final String PRACTICE = "practice-a";
    private static final String OTHER_PRACTICE = "practice-b";

    /** RecruitmentVisibility with every DB lookup replaced by declared facts. */
    static final class StubVisibility extends RecruitmentVisibility {
        final Map<String, Set<String>> roles = new HashMap<>();
        final Map<String, String> practices = new HashMap<>();
        final Map<String, List<String>> ledTeams = new HashMap<>();
        final Map<String, Set<String>> circles = new HashMap<>();
        final Set<String> intakeHolders = new java.util.HashSet<>();
        final Set<String> hiringOwners = new java.util.HashSet<>();
        final Set<String> profileReaders = new java.util.HashSet<>();
        final Set<String> assistantPracticeCandidates = new HashSet<>();

        @Override
        public Set<String> rolesOf(String userUuid) {
            return roles.getOrDefault(userUuid, Set.of());
        }

        @Override
        public String practiceOfUser(String userUuid) {
            return practices.get(userUuid);
        }

        @Override
        public List<String> currentlyLedTeams(String userUuid) {
            return ledTeams.getOrDefault(userUuid, List.of());
        }

        @Override
        public boolean isCircleMember(String userUuid, String positionUuid) {
            return circles.getOrDefault(userUuid, Set.of()).contains(positionUuid);
        }

        @Override
        Set<String> circledPositionUuids(String viewerUuid) {
            return circles.getOrDefault(viewerUuid, Set.of());
        }

        @Override
        Map<String, RecruitmentCircleRole> circleRolesFor(String viewerUuid) {
            Map<String, RecruitmentCircleRole> result = new HashMap<>();
            circles.getOrDefault(viewerUuid, Set.of()).forEach(positionUuid ->
                    result.put(positionUuid, RecruitmentCircleRole.OWNER));
            return result;
        }

        @Override
        public boolean canManageCircle(String viewerUuid, RecruitmentPosition position) {
            // The partner branch of canDecideCore delegates here; for these
            // tests the seat-holding half is declared through `circles`.
            Set<String> viewerRoles = rolesOf(viewerUuid);
            if (viewerRoles.contains("ADMIN") || viewerRoles.contains("HR")) {
                return true;
            }
            if (position.getHiringTrack() != RecruitmentHiringTrack.PARTNER
                    && viewerRoles.contains("ASSISTANT_TEAMLEAD")
                    && position.getPracticeUuid() != null
                    && position.getPracticeUuid().equals(practiceOfUser(viewerUuid))) {
                return true;
            }
            return circles.getOrDefault(viewerUuid, Set.of()).contains(position.getUuid());
        }

        @Override
        boolean holdsRecruitmentIntakeGrant(String viewerUuid) {
            return intakeHolders.contains(viewerUuid);
        }

        @Override
        public boolean isHiringOwnerForCandidate(String viewerUuid, String candidateUuid) {
            return hiringOwners.contains(viewerUuid);
        }

        @Override
        public boolean canReadCandidateProfile(String viewerUuid,
                                               RecruitmentCandidate candidate) {
            return profileReaders.contains(viewerUuid)
                    || super.canReadCandidateProfile(viewerUuid, candidate);
        }

        @Override
        public boolean hasApplicationInAssistantPractice(String viewerUuid,
                                                         String candidateUuid) {
            return practiceOfUser(viewerUuid) != null
                    && assistantPracticeCandidates.contains(candidateUuid);
        }

        @Override
        public boolean isPartnerTrackOnly(String viewerUuid, String candidateUuid) {
            return false;
        }
    }

    private static RecruitmentPosition position(String uuid, RecruitmentHiringTrack track,
                                                String practiceUuid, String teamUuid,
                                                String ownerUuid) {
        RecruitmentPosition position = new RecruitmentPosition();
        position.setUuid(uuid);
        position.setHiringTrack(track);
        position.setPracticeUuid(practiceUuid);
        position.setTeamUuid(teamUuid);
        position.setHiringOwnerUuid(ownerUuid);
        return position;
    }

    private static StubVisibility stub() {
        StubVisibility visibility = new StubVisibility();
        visibility.roles.put("admin", Set.of("ADMIN"));
        visibility.roles.put("hr", Set.of("HR"));
        visibility.roles.put("teamlead", Set.of("TEAMLEAD", "USER"));
        visibility.roles.put("assistant", Set.of("ASSISTANT_TEAMLEAD", "USER"));
        visibility.roles.put("both", Set.of("TEAMLEAD", "ASSISTANT_TEAMLEAD"));
        visibility.roles.put("recruiter", Set.of("RECRUITMENT", "USER"));
        visibility.roles.put("plain", Set.of("USER"));
        visibility.practices.put("assistant", PRACTICE);
        visibility.practices.put("both", PRACTICE);
        return visibility;
    }

    private static final RecruitmentPosition IN_PRACTICE =
            position("p-in", RecruitmentHiringTrack.PRACTICE_TEAM, PRACTICE, null, null);
    private static final RecruitmentPosition OUT_OF_PRACTICE =
            position("p-out", RecruitmentHiringTrack.PRACTICE_TEAM, OTHER_PRACTICE, null, null);
    private static final RecruitmentPosition PARTNER_IN_PRACTICE =
            position("p-partner", RecruitmentHiringTrack.PARTNER, PRACTICE, null, null);
    private static final RecruitmentPosition OWNED_BY_PLAIN =
            position("p-owned", RecruitmentHiringTrack.STAFF_ROLE, null, null, "plain");
    private static final RecruitmentPosition OWNED_BY_ASSISTANT =
            position("p-owned-assistant", RecruitmentHiringTrack.STAFF_ROLE, null, null, "assistant");
    private static final RecruitmentPosition OUT_OF_PRACTICE_OWNED_BY_ASSISTANT =
            position("p-owned-assistant-out", RecruitmentHiringTrack.STAFF_ROLE,
                    OTHER_PRACTICE, "team-assistant", "assistant");
    private static final RecruitmentPosition OWNED_BY_BOTH =
            position("p-owned-both", RecruitmentHiringTrack.STAFF_ROLE, null, null, "both");

    // ---- Decision 1: the read tier decides, company-wide ------------------------

    @Test
    void decision1_teamleadDecidesOnEveryNonPartnerPosition() {
        StubVisibility visibility = stub();
        assertTrue(visibility.canDecideOnApplication("teamlead", IN_PRACTICE));
        assertTrue(visibility.canDecideOnApplication("teamlead", OUT_OF_PRACTICE),
                "practice irrelevant — one tier");
        assertTrue(visibility.canMutatePosition("teamlead", OUT_OF_PRACTICE));
        assertTrue(visibility.isRecruiterOrHiringOwner("teamlead", OUT_OF_PRACTICE),
                "stage skips collapse into the same tier");
        assertFalse(visibility.canDecideOnApplication("teamlead", PARTNER_IN_PRACTICE),
                "partner track stays circle-gated");
    }

    @Test
    void decision1_finalOutcomesStayWithTheTeamlead() {
        StubVisibility visibility = stub();
        assertTrue(visibility.canDecideFinalOutcome("teamlead", OUT_OF_PRACTICE));
        assertTrue(visibility.canDecideFinalOutcome("hr", OUT_OF_PRACTICE));
    }

    // ---- Decisions 2–5: assistant practice scoping ------------------------------

    @Test
    void assistant_decidesInsideTheirPractice_only() {
        StubVisibility visibility = stub();
        assertTrue(visibility.canDecideOnApplication("assistant", IN_PRACTICE));
        assertTrue(visibility.canMutatePosition("assistant", IN_PRACTICE));
        assertTrue(visibility.isRecruiterOrHiringOwner("assistant", IN_PRACTICE));
        assertFalse(visibility.canDecideOnApplication("assistant", OUT_OF_PRACTICE));
        assertFalse(visibility.canDecideOnApplication("assistant", OWNED_BY_PLAIN),
                "a practice-less position is in no assistant's scope");
        assertFalse(visibility.canDecideOnApplication("assistant", PARTNER_IN_PRACTICE),
                "their own practice's PARTNER req stays invisible to the route");
    }

    @Test
    void assistant_withNoPractice_failsClosed() {
        StubVisibility visibility = stub();
        visibility.ledTeams.put("assistant", List.of("team-assistant"));
        visibility.practices.remove("assistant");
        assertFalse(visibility.canDecideOnApplication("assistant", IN_PRACTICE));
        assertFalse(visibility.canReadPosition("assistant", IN_PRACTICE));
        assertFalse(visibility.canDecideOnApplication(
                "assistant", OUT_OF_PRACTICE_OWNED_BY_ASSISTANT));
        assertFalse(visibility.canReadPosition(
                "assistant", OUT_OF_PRACTICE_OWNED_BY_ASSISTANT));
        assertFalse(visibility.isRecruiterOrHiringOwner(
                "assistant", OUT_OF_PRACTICE_OWNED_BY_ASSISTANT));
        assertFalse(visibility.isCompTierFor(
                "assistant", List.of(OUT_OF_PRACTICE_OWNED_BY_ASSISTANT)));
    }

    @Test
    void assistant_readsExactlyWhatTheyDecideOn() {
        StubVisibility visibility = stub();
        visibility.ledTeams.put("assistant", List.of("team-assistant"));
        assertTrue(visibility.canReadPosition("assistant", IN_PRACTICE));
        assertFalse(visibility.canReadPosition("assistant", OUT_OF_PRACTICE));
        assertFalse(visibility.canReadPosition(
                "assistant", OUT_OF_PRACTICE_OWNED_BY_ASSISTANT),
                "named ownership and current leadership cannot widen practice scope");
        assertFalse(visibility.canDecideOnApplication(
                "assistant", OUT_OF_PRACTICE_OWNED_BY_ASSISTANT));
        assertFalse(visibility.isRecruiterOrHiringOwner(
                "assistant", OUT_OF_PRACTICE_OWNED_BY_ASSISTANT));
        assertFalse(visibility.canReadPosition("assistant", PARTNER_IN_PRACTICE));
    }

    @Test
    void assistantBatchedPositionPredicates_failClosedOutsidePractice() {
        StubVisibility visibility = stub();
        visibility.ledTeams.put("assistant", List.of("team-assistant"));
        List<RecruitmentPosition> positions = List.of(
                IN_PRACTICE, OUT_OF_PRACTICE_OWNED_BY_ASSISTANT);

        assertEquals(Set.of(IN_PRACTICE.getUuid()),
                visibility.readablePositionUuids("assistant", positions));
        assertEquals(Set.of(IN_PRACTICE.getUuid()),
                visibility.decidablePositionUuids("assistant", positions));
        assertEquals(Set.of(IN_PRACTICE.getUuid()),
                visibility.ownPositionUuids("assistant", positions));

        visibility.practices.remove("assistant");
        assertTrue(visibility.readablePositionUuids("assistant", positions).isEmpty());
        assertTrue(visibility.decidablePositionUuids("assistant", positions).isEmpty());
        assertTrue(visibility.ownPositionUuids("assistant", positions).isEmpty());
    }

    @Test
    void assistantCandidateAndCompScope_cannotFallThroughToInvolvement() {
        StubVisibility visibility = stub();
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid("out-of-practice-candidate");
        candidate.setStatus(CandidateStatus.ACTIVE);
        visibility.ledTeams.put("assistant", List.of("team-assistant"));

        assertFalse(visibility.canReadCandidateProfile("assistant", candidate));
        assertFalse(visibility.isCompTierFor(
                "assistant", List.of(OUT_OF_PRACTICE_OWNED_BY_ASSISTANT)));

        visibility.assistantPracticeCandidates.add(candidate.getUuid());
        assertTrue(visibility.canReadCandidateProfile("assistant", candidate));
        assertTrue(visibility.isCompTierFor("assistant", List.of(IN_PRACTICE)));

        visibility.practices.remove("assistant");
        assertFalse(visibility.canReadCandidateProfile("assistant", candidate));
        assertFalse(visibility.isCompTierFor("assistant", List.of(IN_PRACTICE)));
    }

    @Test
    void assistantPlusTeamlead_keepsBroaderPositionCandidateAndCompRights() {
        StubVisibility visibility = stub();
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid("candidate");
        candidate.setStatus(CandidateStatus.ACTIVE);

        assertTrue(visibility.canReadPosition("both", OUT_OF_PRACTICE_OWNED_BY_ASSISTANT));
        assertTrue(visibility.canDecideOnApplication("both", OUT_OF_PRACTICE_OWNED_BY_ASSISTANT));
        assertTrue(visibility.canReadCandidateProfile("both", candidate));
        assertTrue(visibility.isCompTierFor(
                "both", List.of(OUT_OF_PRACTICE_OWNED_BY_ASSISTANT)));
    }

    // ---- Decision 7: final outcomes need their own gate --------------------------

    @Test
    void decision7_assistantMovesStagesButNeverClosesAnOutcome() {
        StubVisibility visibility = stub();
        assertTrue(visibility.canDecideOnApplication("assistant", IN_PRACTICE),
                "precondition: the stage-move gate is open");
        assertFalse(visibility.canDecideFinalOutcome("assistant", IN_PRACTICE),
                "hire, reject, withdraw, return-to-pool: all four closed");
    }

    @Test
    void decision7_namedOwnershipCannotBypassAssistantTerminalDenial() {
        StubVisibility visibility = stub();
        assertFalse(visibility.canDecideOnApplication("assistant", OWNED_BY_ASSISTANT),
                "named ownership cannot bypass the assistant practice boundary");
        assertFalse(visibility.canDecideFinalOutcome("assistant", OWNED_BY_ASSISTANT),
                "assistant-only named owner still cannot hire, reject, withdraw, "
                        + "return-to-pool or record NO-GO");

        assertTrue(visibility.canDecideFinalOutcome("both", OWNED_BY_BOTH),
                "a simultaneous TEAMLEAD keeps the broader role's terminal rights");
    }

    @Test
    void decision7_involvementKeepsFinalOutcomes() {
        StubVisibility visibility = stub();
        assertTrue(visibility.canDecideFinalOutcome("plain", OWNED_BY_PLAIN),
                "the named hiring owner still closes outcomes — only the"
                        + " assistant route is excluded");
    }

    /** The role only adds. A TEAMLEAD who also holds it keeps everything. */
    @Test
    void assistantRole_neverNarrowsWiderStanding() {
        StubVisibility visibility = stub();
        assertTrue(visibility.canDecideOnApplication("both", OUT_OF_PRACTICE));
        assertTrue(visibility.canDecideFinalOutcome("both", OUT_OF_PRACTICE));
    }

    // ---- Decision 9: offer dossier remains outside assistant scope ---------------

    @Test
    void assistantNamedHiringOwner_stillCannotReadDossier_broaderRolesKeepAccess() {
        StubVisibility visibility = stub();
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid("candidate");
        visibility.hiringOwners.addAll(Set.of(
                "assistant", "teamlead", "both", "recruiter", "plain"));
        visibility.profileReaders.addAll(Set.of(
                "assistant", "teamlead", "both", "recruiter", "plain"));

        assertFalse(visibility.canReadDossier("assistant", candidate),
                "assistant-only is denied before named-owner involvement is considered");
        assertTrue(visibility.canReadDossier("teamlead", candidate),
                "an eligible named TEAMLEAD keeps the read-only dossier view");
        assertTrue(visibility.canReadDossier("both", candidate),
                "ASSISTANT_TEAMLEAD never narrows simultaneous TEAMLEAD standing");
        assertFalse(visibility.canReadDossier("recruiter", candidate),
                "RECRUITMENT-only named ownership is not dossier access");
        assertFalse(visibility.canReadDossier("plain", candidate),
                "plain named ownership is not dossier access");
        assertTrue(visibility.canReadDossier("hr", candidate));
        assertTrue(visibility.canReadDossier("admin", candidate));
    }

    @Test
    void dossierWritesRemainHrAdminOnly_andRoleCompositionIsAdditive() {
        StubVisibility visibility = stub();

        assertFalse(visibility.canWriteDossier("assistant"));
        assertFalse(visibility.canWriteDossier("teamlead"));
        assertFalse(visibility.canWriteDossier("both"),
                "ASSISTANT_TEAMLEAD+TEAMLEAD still has no offer-dossier write role");
        assertFalse(visibility.canWriteDossier("recruiter"));
        assertTrue(visibility.canWriteDossier("hr"));
        assertTrue(visibility.canWriteDossier("admin"));

        visibility.roles.put("assistant-hr", Set.of("ASSISTANT_TEAMLEAD", "HR"));
        assertTrue(visibility.canWriteDossier("assistant-hr"),
                "the assistant role must not narrow a simultaneous HR role");
    }

    // ---- Decision 10: assistants never create candidates -------------------------

    @Test
    void decision10_intakeGrantOpensNothingForAnAssistant() {
        StubVisibility visibility = stub();
        visibility.intakeHolders.add("assistant");
        assertFalse(visibility.canCreateCandidate("assistant"),
                "the rule sits on the role — a mistaken console grant of"
                        + " recruitment:intake to ASSISTANT_TEAMLEAD opens nothing");

        visibility.intakeHolders.add("teamlead");
        assertTrue(visibility.canCreateCandidate("teamlead"),
                "a team lead's intake grant works as before");
        visibility.intakeHolders.add("both");
        assertTrue(visibility.canCreateCandidate("both"),
                "TEAMLEAD standing wins over the assistant role");
    }

    // ---- Decisions 12/13: the Inbox tier ----------------------------------------

    @Test
    void inboxTier_isRecruitersPlusTeamleads_neverAssistants() {
        StubVisibility visibility = stub();
        assertTrue(visibility.isInboxTier("admin"));
        assertTrue(visibility.isInboxTier("hr"));
        assertTrue(visibility.isInboxTier("teamlead"));
        assertFalse(visibility.isInboxTier("assistant"));
        assertFalse(visibility.isInboxTier("plain"));
    }

    // ---- Decision 15: bulk tagging ----------------------------------------------

    @Test
    void bulkTagging_opensToTeamleadsAndAssistants() {
        StubVisibility visibility = stub();
        assertTrue(visibility.canBulkTag("hr"));
        assertTrue(visibility.canBulkTag("teamlead"));
        assertTrue(visibility.canBulkTag("assistant"));
        assertFalse(visibility.canBulkTag("plain"));
    }

    // ---- The §8 gap: position creation ------------------------------------------

    @Test
    void positionCreation_partnerIsRecruiterTierOnly_nonPartnerScales() {
        StubVisibility visibility = stub();
        assertTrue(visibility.canCreatePosition("hr", RecruitmentHiringTrack.PARTNER, null));
        assertFalse(visibility.canCreatePosition("teamlead", RecruitmentHiringTrack.PARTNER, null),
                "the pre-existing gap: any BFF-admitted caller could open a partner req");
        assertTrue(visibility.canCreatePosition("teamlead",
                RecruitmentHiringTrack.PRACTICE_TEAM, OTHER_PRACTICE));
        assertTrue(visibility.canCreatePosition("assistant",
                RecruitmentHiringTrack.PRACTICE_TEAM, PRACTICE));
        assertFalse(visibility.canCreatePosition("assistant",
                RecruitmentHiringTrack.PRACTICE_TEAM, OTHER_PRACTICE));
        assertFalse(visibility.canCreatePosition("plain",
                RecruitmentHiringTrack.PRACTICE_TEAM, PRACTICE));
    }

    // ---- isAssistantScoped -------------------------------------------------------

    @Test
    void assistantScoped_meansAssistantAndNothingWider() {
        assertTrue(RecruitmentVisibility.isAssistantScoped(Set.of("ASSISTANT_TEAMLEAD")));
        assertTrue(RecruitmentVisibility.isAssistantScoped(Set.of("ASSISTANT_TEAMLEAD", "USER", "SALES")));
        assertFalse(RecruitmentVisibility.isAssistantScoped(Set.of("ASSISTANT_TEAMLEAD", "TEAMLEAD")));
        assertFalse(RecruitmentVisibility.isAssistantScoped(Set.of("ASSISTANT_TEAMLEAD", "HR")));
        assertFalse(RecruitmentVisibility.isAssistantScoped(Set.of("ASSISTANT_TEAMLEAD", "RECRUITMENT")));
        assertFalse(RecruitmentVisibility.isAssistantScoped(Set.of("ASSISTANT_TEAMLEAD", "ADMIN")));
        assertFalse(RecruitmentVisibility.isAssistantScoped(Set.of("TEAMLEAD")));
        assertFalse(RecruitmentVisibility.isAssistantScoped(Set.of()));
    }
}
