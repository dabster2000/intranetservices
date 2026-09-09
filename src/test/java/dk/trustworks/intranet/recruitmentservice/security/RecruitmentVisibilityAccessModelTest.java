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
        /**
         * The assistant's whole scope since 2026-09-08 (D1): viewer -> the
         * position uuids somebody assigned them to. Replaced a
         * {@code Map<String,String> practices} map that modelled the old
         * practice route.
         *
         * Deliberately NOT derived from the position's practiceUuid. Deriving
         * it would keep every assertion below green while testing PRACTICE
         * semantics against ASSIGNMENT code — i.e. it would prove nothing and
         * would hide the exact regression this change exists to prevent.
         */
        final Map<String, Set<String>> assignedPositions = new HashMap<>();
        final Map<String, List<String>> ledTeams = new HashMap<>();
        /**
         * Practices of the teams the user currently leads — the landing
         * card's "is this mine to worry about?" hop
         * ({@code RecruitmentLandingService.taskInScope}). Deliberately
         * separate from {@link #practices} ({@code user.practice_uuid}, the
         * assistant's scope): in production every team lead's own practice
         * differs from the practice of the team they lead.
         */
        final Map<String, Set<String>> ledPractices = new HashMap<>();
        final Map<String, Set<String>> circles = new HashMap<>();
        final Set<String> intakeHolders = new java.util.HashSet<>();
        final Set<String> hiringOwners = new java.util.HashSet<>();
        final Set<String> profileReaders = new java.util.HashSet<>();
        final Set<String> assistantVisibleCandidates = new HashSet<>();

        @Override
        public Set<String> rolesOf(String userUuid) {
            return roles.getOrDefault(userUuid, Set.of());
        }

        @Override
        public Set<String> assignedPositionUuids(String viewerUuid) {
            return assignedPositions.getOrDefault(viewerUuid, Set.of());
        }

        @Override
        public List<String> currentlyLedTeams(String userUuid) {
            return ledTeams.getOrDefault(userUuid, List.of());
        }

        @Override
        public Set<String> ledPracticeUuids(String userUuid) {
            return ledPractices.getOrDefault(userUuid, Set.of());
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
            // D10 (2026-09-08): the assistant branch is GONE. An assistant
            // may not manage a circle at all; a seat they hold still counts,
            // which is the line below.
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
        public boolean hasApplicationOnAssignedPosition(String viewerUuid,
                                                         String candidateUuid) {
            return !assignedPositionUuids(viewerUuid).isEmpty()
                    && assistantVisibleCandidates.contains(candidateUuid);
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
        visibility.roles.put("assistant", Set.of("RECRUITMENT_ASSISTANT", "USER"));
        visibility.roles.put("both", Set.of("TEAMLEAD", "RECRUITMENT_ASSISTANT"));
        visibility.roles.put("recruiter", Set.of("RECRUITMENT", "USER"));
        visibility.roles.put("plain", Set.of("USER"));
        // "p-in" is IN_PRACTICE below. The assistant is assigned to it and to
        // nothing else, so "p-out" (another practice) and "p-partner" are both
        // out of scope — and critically so is any OTHER position of the same
        // practice, which is what D1 changed.
        visibility.assignedPositions.put("assistant", Set.of("p-in"));
        visibility.assignedPositions.put("both", Set.of("p-in"));
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

    /**
     * {@code ledPracticeUuids} is an attention hop, not a rights hop. It
     * feeds the landing card's {@code taskInScope} and nothing else —
     * decision 11 removed the practice routes from read/decide in 2026-08-23
     * and this must not quietly put one back.
     */
    @Test
    void ledPractice_grantsNoReadOrDecideRight() {
        StubVisibility visibility = stub();
        visibility.ledPractices.put("plain", Set.of(PRACTICE));

        assertFalse(visibility.canReadPosition("plain", IN_PRACTICE),
                "leading a team in the practice reveals no position");
        assertFalse(visibility.canDecideOnApplication("plain", IN_PRACTICE),
                "…and confers no decision right either");
        assertTrue(visibility.readablePositionUuids("plain", List.of(IN_PRACTICE)).isEmpty());
        assertTrue(visibility.decidablePositionUuids("plain", List.of(IN_PRACTICE)).isEmpty());
    }

    // ---- D1/D2: assistant position-assignment scoping ---------------------------

    @Test
    void assistant_decidesOnAssignedPositions_only() {
        StubVisibility visibility = stub();
        assertTrue(visibility.canDecideOnApplication("assistant", IN_PRACTICE));
        assertTrue(visibility.isRecruiterOrHiringOwner("assistant", IN_PRACTICE));
        // D2 (2026-09-08): pipeline rights and POSITION rights split. The
        // assistant moves candidates through an assigned position's stages
        // and may not edit or close the position itself — canMutatePosition
        // no longer delegates to canDecideOnApplication.
        assertFalse(visibility.canMutatePosition("assistant", IN_PRACTICE),
                "an assistant never edits or closes a position, assigned or not");
        assertFalse(visibility.canDecideOnApplication("assistant", OUT_OF_PRACTICE));
        assertFalse(visibility.canDecideOnApplication("assistant", OWNED_BY_PLAIN),
                "an unassigned position is in no assistant's scope");
        assertFalse(visibility.canDecideOnApplication("assistant", PARTNER_IN_PRACTICE),
                "a PARTNER req is never assignable and stays invisible to the route");
    }

    @Test
    void assistant_withNoAssignment_failsClosed() {
        StubVisibility visibility = stub();
        visibility.ledTeams.put("assistant", List.of("team-assistant"));
        visibility.assignedPositions.remove("assistant");
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

        visibility.assignedPositions.remove("assistant");
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

        visibility.assistantVisibleCandidates.add(candidate.getUuid());
        assertTrue(visibility.canReadCandidateProfile("assistant", candidate));
        // D8 (2026-09-08): the assistant is OUT of the comp tier entirely —
        // not the salary expectation, not any comp-group fact, on an assigned
        // position or otherwise. It returns false even here, where the
        // candidate IS visible to them, which is the whole point.
        assertFalse(visibility.isCompTierFor("assistant", List.of(IN_PRACTICE)),
                "an assistant reads no compensation fact, even on an assigned position");

        visibility.assignedPositions.remove("assistant");
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
                "RECRUITMENT_ASSISTANT never narrows simultaneous TEAMLEAD standing");
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
                "RECRUITMENT_ASSISTANT+TEAMLEAD still has no offer-dossier write role");
        assertFalse(visibility.canWriteDossier("recruiter"));
        assertTrue(visibility.canWriteDossier("hr"));
        assertTrue(visibility.canWriteDossier("admin"));

        visibility.roles.put("assistant-hr", Set.of("RECRUITMENT_ASSISTANT", "HR"));
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
                        + " recruitment:intake to RECRUITMENT_ASSISTANT opens nothing");

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

    // ---- 2026-08-25: writing to the candidate --------------------------------------

    @Test
    void candidateEmail_opensToTeamleadsAndAssistants_configurationDoesNot() {
        StubVisibility visibility = stub();
        assertTrue(visibility.canEmailCandidates("admin"));
        assertTrue(visibility.canEmailCandidates("hr"));
        assertTrue(visibility.canEmailCandidates("teamlead"));
        assertTrue(visibility.canEmailCandidates("assistant"));
        assertFalse(visibility.canEmailCandidates("plain"));

        // The split that makes the widening safe: composing and sending moved,
        // configuring what gets sent (templates, sender, pending queue) did not.
        assertFalse(visibility.isRecruiterTier("teamlead"),
                "a team lead writes to candidates but never edits the shared templates");
        assertFalse(visibility.isRecruiterTier("assistant"));
    }

    // ---- 2026-09-08 position scoping: the new cases ------------------------------

    /**
     * THE assertion of this change. Under the practice route an assistant saw
     * every non-partner position of their practice; now they see only what
     * they were assigned. Without this test nothing proves practice stopped
     * mattering, because every other assistant case would still pass if the
     * scope were "my practice".
     */
    @Test
    void assistant_unassignedPositionInTheSamePractice_isRefused() {
        StubVisibility visibility = stub();
        RecruitmentPosition sibling = position("p-sibling",
                RecruitmentHiringTrack.PRACTICE_TEAM, PRACTICE, null, null);
        assertTrue(visibility.canReadPosition("assistant", IN_PRACTICE),
                "assigned: visible");
        assertFalse(visibility.canReadPosition("assistant", sibling),
                "same practice, NOT assigned: invisible");
        assertFalse(visibility.canDecideOnApplication("assistant", sibling));
        assertEquals(Set.of(IN_PRACTICE.getUuid()),
                visibility.readablePositionUuids("assistant", List.of(IN_PRACTICE, sibling)));
    }

    /** D5: practice is not a boundary — an assignment in another practice works. */
    @Test
    void assistant_assignedOutsideTheirOwnPractice_isAllowed() {
        StubVisibility visibility = stub();
        visibility.assignedPositions.put("assistant", Set.of(OUT_OF_PRACTICE.getUuid()));
        assertTrue(visibility.canReadPosition("assistant", OUT_OF_PRACTICE),
                "cross-practice assignment is the point of D5");
        assertTrue(visibility.canDecideOnApplication("assistant", OUT_OF_PRACTICE));
        assertFalse(visibility.canReadPosition("assistant", IN_PRACTICE),
                "and their own practice grants nothing without an assignment");
    }

    /** D10: the assistant cannot manage a circle; a seat they hold still counts. */
    @Test
    void assistant_cannotManageCircle_butASeatStillGrants() {
        StubVisibility visibility = stub();
        assertFalse(visibility.canManageCircle("assistant", IN_PRACTICE),
                "D10: no circle management, not even on an assigned position");
        visibility.circles.put("assistant", Set.of(PARTNER_IN_PRACTICE.getUuid()));
        assertTrue(visibility.canManageCircle("assistant", PARTNER_IN_PRACTICE),
                "a seat someone GAVE them still works \u2014 accepted (spec A1/D10)");
    }

    /**
     * D3. Note the null-hiring-owner case: several production positions have
     * no hiring owner, and for those no team lead can assign at all.
     */
    @Test
    void canAssignAssistant_followsTheHiringOwnerRule() {
        StubVisibility visibility = stub();
        RecruitmentPosition ownedByTeamlead = position("p-owned",
                RecruitmentHiringTrack.PRACTICE_TEAM, PRACTICE, null, "teamlead");
        assertTrue(visibility.canAssignAssistant("teamlead", ownedByTeamlead));
        assertFalse(visibility.canAssignAssistant("teamlead", IN_PRACTICE),
                "a team lead who is not the named hiring owner may not assign");
        assertFalse(visibility.canAssignAssistant("teamlead", PARTNER_IN_PRACTICE),
                "partner track is never assignable, for anybody");
        assertFalse(visibility.canAssignAssistant("assistant", ownedByTeamlead),
                "an assistant never assigns, including themselves");
        for (String recruiterTier : List.of("admin", "hr", "recruiter")) {
            assertTrue(visibility.canAssignAssistant(recruiterTier, IN_PRACTICE),
                    recruiterTier + " assigns any non-partner position, any practice");
            assertFalse(visibility.canAssignAssistant(recruiterTier, PARTNER_IN_PRACTICE),
                    recruiterTier + " still cannot assign a partner req");
        }
    }

    /**
     * D2 is a SUBTRACTION, not a rewrite: the split of canMutatePosition away
     * from canDecideOnApplication must leave every other role untouched.
     */
    @Test
    void positionMutation_subtractsOnlyTheAssistant() {
        StubVisibility visibility = stub();
        assertTrue(visibility.canMutatePosition("teamlead", IN_PRACTICE));
        assertTrue(visibility.canMutatePosition("hr", IN_PRACTICE));
        assertTrue(visibility.canMutatePosition("recruiter", IN_PRACTICE));
        assertTrue(visibility.canMutatePosition("admin", IN_PRACTICE));
        assertFalse(visibility.canMutatePosition("assistant", IN_PRACTICE));
        assertFalse(visibility.canMutatePosition("plain", IN_PRACTICE));
        assertTrue(visibility.canMutatePosition("both", IN_PRACTICE),
                "TEAMLEAD+assistant is a team lead \u2014 the role only ever adds");
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
        // D2 (2026-09-08): an assistant opens NO position, anywhere. Their
        // scope is exactly what somebody assigned them, so creating one would
        // be self-service scope.
        assertFalse(visibility.canCreatePosition("assistant",
                RecruitmentHiringTrack.PRACTICE_TEAM, PRACTICE));
        assertFalse(visibility.canCreatePosition("assistant",
                RecruitmentHiringTrack.PRACTICE_TEAM, OTHER_PRACTICE));
        assertFalse(visibility.canCreatePosition("plain",
                RecruitmentHiringTrack.PRACTICE_TEAM, PRACTICE));
    }

    // ---- isAssistantScoped -------------------------------------------------------

    @Test
    void assistantScoped_meansAssistantAndNothingWider() {
        assertTrue(RecruitmentVisibility.isAssistantScoped(Set.of("RECRUITMENT_ASSISTANT")));
        assertTrue(RecruitmentVisibility.isAssistantScoped(Set.of("RECRUITMENT_ASSISTANT", "USER", "SALES")));
        assertFalse(RecruitmentVisibility.isAssistantScoped(Set.of("RECRUITMENT_ASSISTANT", "TEAMLEAD")));
        assertFalse(RecruitmentVisibility.isAssistantScoped(Set.of("RECRUITMENT_ASSISTANT", "HR")));
        assertFalse(RecruitmentVisibility.isAssistantScoped(Set.of("RECRUITMENT_ASSISTANT", "RECRUITMENT")));
        assertFalse(RecruitmentVisibility.isAssistantScoped(Set.of("RECRUITMENT_ASSISTANT", "ADMIN")));
        assertFalse(RecruitmentVisibility.isAssistantScoped(Set.of("TEAMLEAD")));
        assertFalse(RecruitmentVisibility.isAssistantScoped(Set.of()));
    }
}
