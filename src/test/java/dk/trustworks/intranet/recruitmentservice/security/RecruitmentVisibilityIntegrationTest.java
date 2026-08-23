package dk.trustworks.intranet.recruitmentservice.security;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authz, query-level via {@code RecruitmentVisibility} — rewritten
 * 2026-08-23 for the access-model redesign
 * ({@code docs/access/recruitment-access-model-target.md}); the previous
 * version pinned the pre-redesign rules (the 2026-08-12 practice-lead
 * grant among them) and is superseded, not extended:
 * <ul>
 *   <li>a partner-track position is invisible in list/get to every
 *       non-circle viewer below ADMIN — the hard filter is unchanged;</li>
 *   <li><b>decision 1:</b> the read tier (HR/RECRUITMENT/TEAMLEAD) both
 *       reads AND decides on every non-partner position;</li>
 *   <li><b>decision 11:</b> {@code practice_lead} rows and led-team
 *       practices grant nothing anywhere — rights come from roles only;</li>
 *   <li><b>decisions 2–10:</b> {@code ASSISTANT_TEAMLEAD} carries the team
 *       lead's capabilities scoped to {@code user.practice_uuid}, minus
 *       final outcomes, candidate creation and the Inbox.</li>
 * </ul>
 * Fixtures are raw rows (users, roles, practice, practice_lead, teamroles,
 * positions, circle members) so the helper is tested in isolation from the
 * command handlers. NOTE: {@code @QuarkusTest} — not in the CI fast tier;
 * run deliberately against a local DB.
 */
@QuarkusTest
class RecruitmentVisibilityIntegrationTest {

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String otherPracticeUuid;
    private String adminUser;
    private String recruiterUser;   // HR role — recruiter tier
    private String teamleadUser;    // TEAMLEAD role; leads teamUuid via teamroles LEADER
    private String assistantUser;   // ASSISTANT_TEAMLEAD role; user.practice_uuid = practiceUuid
    private String currentLeadUser; // practice_lead row, enddate IS NULL — NO role
    private String plainUser;       // no roles, no leads, no circles
    private String teamUuid;

    private String practicePositionUuid; // PRACTICE_TEAM on practiceUuid + teamUuid
    private String partnerPositionUuid;  // PARTNER
    private String staffPositionUuid;    // STAFF_ROLE owned by plainUser, no practice
    /**
     * PRACTICE_TEAM on practiceUuid with NO team and NO named owner — the
     * production shape (every prod position has {@code team_uuid} NULL and
     * almost none has an owner). Under decision 1 it belongs to every
     * TEAMLEAD's decide set anyway; for the assistant it isolates the
     * practice-membership route from the team and owner routes.
     */
    private String practiceOnlyPositionUuid;
    /** PRACTICE_TEAM on otherPracticeUuid — the assistant's boundary row. */
    private String otherPracticePositionUuid;

    private final List<Runnable> cleanupSteps = new ArrayList<>();

    @BeforeEach
    void seedFixtures() {
        practiceUuid = UUID.randomUUID().toString();
        otherPracticeUuid = UUID.randomUUID().toString();
        adminUser = UUID.randomUUID().toString();
        recruiterUser = UUID.randomUUID().toString();
        teamleadUser = UUID.randomUUID().toString();
        assistantUser = UUID.randomUUID().toString();
        currentLeadUser = UUID.randomUUID().toString();
        plainUser = UUID.randomUUID().toString();
        teamUuid = UUID.randomUUID().toString();
        practicePositionUuid = UUID.randomUUID().toString();
        partnerPositionUuid = UUID.randomUUID().toString();
        staffPositionUuid = UUID.randomUUID().toString();
        practiceOnlyPositionUuid = UUID.randomUUID().toString();
        otherPracticePositionUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            for (String user : List.of(adminUser, recruiterUser, teamleadUser,
                    assistantUser, currentLeadUser, plainUser)) {
                insertUser(user);
            }
            insertRole(adminUser, "ADMIN");
            insertRole(recruiterUser, "HR");
            insertRole(teamleadUser, "TEAMLEAD");
            insertRole(assistantUser, "ASSISTANT_TEAMLEAD");

            insertPractice(practiceUuid);
            insertPractice(otherPracticeUuid);
            setUserPractice(assistantUser, practiceUuid);
            insertPracticeLead(currentLeadUser, practiceUuid, null);
            insertTeam(teamUuid, practiceUuid);
            insertTeamLeader(teamleadUser, teamUuid);

            insertPosition(practicePositionUuid, "Consultant", "PRACTICE_TEAM", practiceUuid, teamUuid, null);
            insertPosition(partnerPositionUuid, "Partner hire", "PARTNER", null, null, null);
            insertPosition(staffPositionUuid, "Office manager", "STAFF_ROLE", null, null, plainUser);
            insertPosition(practiceOnlyPositionUuid, "Consultant (no team)", "PRACTICE_TEAM",
                    practiceUuid, null, null);
            insertPosition(otherPracticePositionUuid, "Consultant (other practice)", "PRACTICE_TEAM",
                    otherPracticeUuid, null, null);
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            List<String> positions = List.of(practicePositionUuid, partnerPositionUuid,
                    staffPositionUuid, practiceOnlyPositionUuid, otherPracticePositionUuid);
            em.createNativeQuery("DELETE FROM recruitment_circle_members WHERE position_uuid IN :p")
                    .setParameter("p", positions).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid IN :p")
                    .setParameter("p", positions).executeUpdate();
            List<String> users = List.of(adminUser, recruiterUser, teamleadUser,
                    assistantUser, currentLeadUser, plainUser);
            em.createNativeQuery("DELETE FROM practice_lead WHERE useruuid IN :u")
                    .setParameter("u", users).executeUpdate();
            em.createNativeQuery("DELETE FROM teamroles WHERE useruuid IN :u")
                    .setParameter("u", users).executeUpdate();
            em.createNativeQuery("DELETE FROM roles WHERE useruuid IN :u")
                    .setParameter("u", users).executeUpdate();
            em.createNativeQuery("DELETE FROM team WHERE uuid = :t")
                    .setParameter("t", teamUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM practice WHERE uuid IN :p")
                    .setParameter("p", List.of(practiceUuid, otherPracticeUuid)).executeUpdate();
            em.createNativeQuery("DELETE FROM user WHERE uuid IN :u")
                    .setParameter("u", users).executeUpdate();
        });
    }

    // ---- Partner track: circle-gated, hard filter ------------------------------

    @Test
    void partnerPosition_invisibleToNonCircleRecruiterAndTeamlead_visibleAfterCircleAdd() {
        assertFalse(visibleUuids(recruiterUser).contains(partnerPositionUuid),
                "non-circle recruiter must not see the partner position in the list");
        assertFalse(visibleUuids(teamleadUser).contains(partnerPositionUuid));
        assertFalse(canRead(recruiterUser, partnerPositionUuid),
                "non-circle recruiter must not read the partner position by uuid");
        assertFalse(canRead(teamleadUser, partnerPositionUuid));

        addCircleMember(partnerPositionUuid, recruiterUser);

        assertTrue(visibleUuids(recruiterUser).contains(partnerPositionUuid),
                "circle membership grants list visibility");
        assertTrue(canRead(recruiterUser, partnerPositionUuid));
        // The other viewer is still blind.
        assertFalse(canRead(teamleadUser, partnerPositionUuid));
    }

    @Test
    void partnerPosition_ownershipDoesNotBypassTheCircle() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_positions SET hiring_owner_uuid = :u, team_uuid = :t WHERE uuid = :p")
                        .setParameter("u", teamleadUser)
                        .setParameter("t", teamUuid)
                        .setParameter("p", partnerPositionUuid)
                        .executeUpdate());
        assertFalse(visibleUuids(teamleadUser).contains(partnerPositionUuid),
                "hiring owner / team lead without a circle row must not see a partner position");
        assertFalse(canRead(teamleadUser, partnerPositionUuid));
    }

    @Test
    void adminSeesEverything_includingPartnerTrack() {
        List<String> visible = visibleUuids(adminUser);
        assertTrue(visible.containsAll(
                List.of(practicePositionUuid, partnerPositionUuid, staffPositionUuid)));
        assertTrue(canRead(adminUser, partnerPositionUuid));
    }

    // ---- Recruiter tier ---------------------------------------------------------

    @Test
    void recruiterSeesAllNonPartnerPositions() {
        List<String> visible = visibleUuids(recruiterUser);
        assertTrue(visible.contains(practicePositionUuid));
        assertTrue(visible.contains(staffPositionUuid));
        assertFalse(visible.contains(partnerPositionUuid));
    }

    // ---- Decision 1: the read tier decides — one tier, company-wide -------------

    /**
     * The redesign's first decision: reading and deciding collapse. A
     * TEAMLEAD acts on every non-partner pipeline, practice irrelevant —
     * the old "reads widely, decides narrowly" split (and the 2026-08-12
     * practice-run bridge built to soften it) is gone.
     */
    @Test
    void teamlead_decidesOnEveryNonPartnerPosition_practiceIrrelevant() {
        for (String uuid : List.of(practicePositionUuid, practiceOnlyPositionUuid,
                staffPositionUuid, otherPracticePositionUuid)) {
            RecruitmentPosition position = position(uuid);
            assertTrue(canRead(teamleadUser, uuid));
            assertTrue(visibility.canDecideOnApplication(teamleadUser, position),
                    "decision 1: one tier — read is decide: " + uuid);
            assertTrue(visibility.canMutatePosition(teamleadUser, position));
            assertTrue(visibility.isRecruiterOrHiringOwner(teamleadUser, position),
                    "the collapse includes the owner's elevated moves (stage skips)");
            assertTrue(visibility.canDecideFinalOutcome(teamleadUser, position),
                    "a team lead keeps all four final outcomes");
        }
        assertFalse(visibility.canDecideOnApplication(teamleadUser, position(partnerPositionUuid)),
                "the partner circle stays a hard filter");
    }

    @Test
    void decidablePositionUuids_matchesTheSingleRowGateForEveryViewer() {
        List<RecruitmentPosition> all = allFixturePositions();
        for (String viewer : List.of(adminUser, recruiterUser, teamleadUser,
                assistantUser, currentLeadUser, plainUser)) {
            Set<String> batched = visibility.decidablePositionUuids(viewer, all);
            for (RecruitmentPosition position : all) {
                assertEquals(visibility.canDecideOnApplication(viewer, position),
                        batched.contains(position.getUuid()),
                        "batched twin drifted from canDecideOnApplication for viewer "
                                + viewer + " on " + position.getUuid());
            }
        }
    }

    // ---- Decision 11: the practice-lead route is gone ---------------------------

    /**
     * The route that let Nicklas Grunnet Sandager (SALES/USER) and Anna
     * Mette Forbord Hansen (no roles at all) hold decision rights over six
     * and five open positions respectively: a {@code practice_lead} row was
     * never role-gated. After decision 11, running a practice grants
     * nothing — read, decide, own: all no.
     */
    @Test
    void practiceLeadRow_grantsNothingAnywhere() {
        assertFalse(visibleUuids(currentLeadUser).contains(practicePositionUuid),
                "a current practice_lead row no longer grants read");
        assertFalse(canRead(currentLeadUser, practiceOnlyPositionUuid));
        assertFalse(visibility.canDecideOnApplication(currentLeadUser, position(practiceOnlyPositionUuid)));
        assertFalse(visibility.canMutatePosition(currentLeadUser, position(practicePositionUuid)));
        assertTrue(visibility.ownPositionUuids(currentLeadUser, allFixturePositions()).isEmpty(),
                "and 'Your pipelines' counts none of them");
    }

    @Test
    void teamleadsLedTeamPractice_noLongerFeedsYourPipelines() {
        Set<String> own = visibility.ownPositionUuids(teamleadUser, allFixturePositions());
        assertFalse(own.contains(practiceOnlyPositionUuid),
                "decision 11: the led-team's practice no longer makes a position 'yours'");
        assertTrue(visibility.canDecideOnApplication(teamleadUser, position(practiceOnlyPositionUuid)),
                "...they still ACT on it — by role now, not by the practice hop");
    }

    // ---- Decisions 2–5: the assistant, scoped to their practice -----------------

    @Test
    void assistant_readsAndDecidesWithinTheirPractice_only() {
        List<String> visible = visibleUuids(assistantUser);
        assertTrue(visible.contains(practicePositionUuid), "their practice's positions");
        assertTrue(visible.contains(practiceOnlyPositionUuid));
        assertFalse(visible.contains(otherPracticePositionUuid), "another practice: invisible");
        assertFalse(visible.contains(staffPositionUuid), "no practice on the row: invisible");
        assertFalse(visible.contains(partnerPositionUuid), "partner: the circle is the only key");

        RecruitmentPosition inPractice = position(practiceOnlyPositionUuid);
        assertTrue(canRead(assistantUser, practiceOnlyPositionUuid));
        assertTrue(visibility.canDecideOnApplication(assistantUser, inPractice),
                "same capability as a team lead, scoped to the practice");
        assertTrue(visibility.canMutatePosition(assistantUser, inPractice),
                "edit/close follows the decision gate");
        assertTrue(visibility.isRecruiterOrHiringOwner(assistantUser, inPractice),
                "skip stages: ◐ practice");
        assertTrue(visibility.canManageCircle(assistantUser, inPractice),
                "manage the hiring circle: ◐ practice");

        RecruitmentPosition outside = position(otherPracticePositionUuid);
        assertFalse(visibility.canDecideOnApplication(assistantUser, outside));
        assertFalse(visibility.canMutatePosition(assistantUser, outside));
        assertFalse(visibility.isRecruiterOrHiringOwner(assistantUser, outside));
    }

    /** Decision 7: an assistant moves stages but never closes an outcome. */
    @Test
    void assistant_neverDecidesFinalOutcomes_evenInTheirPractice() {
        RecruitmentPosition inPractice = position(practiceOnlyPositionUuid);
        assertTrue(visibility.canDecideOnApplication(assistantUser, inPractice),
                "precondition: the stage-move gate is open");
        assertFalse(visibility.canDecideFinalOutcome(assistantUser, inPractice),
                "hire, reject, withdraw, return-to-pool: all four stay closed");
    }

    /** Decisions 8/10: no candidate creation; and no Inbox (decision 12's flip side). */
    @Test
    void assistant_createsNothing_andStaysOutOfTheInbox() {
        assertFalse(visibility.canCreateCandidate(assistantUser),
                "decision 10 — enforced on the role, whatever grants the console holds");
        assertFalse(visibility.isInboxTier(assistantUser),
                "decision 8 makes an assistant Inbox pointless; the tier excludes them");
        assertTrue(visibility.isInboxTier(teamleadUser),
                "decisions 12/13 open the Inbox to team leads");
        assertTrue(visibility.isInboxTier(recruiterUser));
        assertFalse(visibility.isInboxTier(plainUser));
    }

    /** The assistant's practice never opens partner track — even their own practice's. */
    @Test
    void assistantPracticeRoute_neverOpensPartnerTrack() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery(
                                "UPDATE recruitment_positions SET practice_uuid = :pr WHERE uuid = :p")
                        .setParameter("pr", practiceUuid)
                        .setParameter("p", partnerPositionUuid)
                        .executeUpdate());

        RecruitmentPosition partner = position(partnerPositionUuid);
        assertFalse(canRead(assistantUser, partnerPositionUuid));
        assertFalse(visibility.canDecideOnApplication(assistantUser, partner));
        assertFalse(visibility.canManageCircle(assistantUser, partner),
                "the circle-management practice route is non-partner only");
    }

    /** "Your pipelines": the assistant's practice IS their scope, so it is theirs. */
    @Test
    void assistant_ownPositions_areTheirPracticesPositions() {
        Set<String> own = visibility.ownPositionUuids(assistantUser, allFixturePositions());
        assertTrue(own.contains(practiceOnlyPositionUuid));
        assertTrue(own.contains(practicePositionUuid));
        assertFalse(own.contains(otherPracticePositionUuid));
        assertFalse(own.contains(staffPositionUuid));
    }

    /**
     * The empty-practice guard's fail-closed twin: the rail
     * ({@code RoleService}) refuses the assignment, but if the practice is
     * cleared afterwards the assistant must resolve to nothing — a silently
     * empty module, never a wide-open one.
     */
    @Test
    void assistant_withNoPractice_seesAndDecidesNothing() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE user SET practice_uuid = NULL WHERE uuid = :u")
                        .setParameter("u", assistantUser)
                        .executeUpdate());
        assertNull(visibility.practiceOfUser(assistantUser));
        assertTrue(visibleUuids(assistantUser).isEmpty());
        assertFalse(visibility.canDecideOnApplication(assistantUser, position(practiceOnlyPositionUuid)));
        assertTrue(visibility.assistantVisibleCandidateUuids(assistantUser).isEmpty());
    }

    /** The role only adds; wider standing wins. A TEAMLEAD+assistant is a team lead. */
    @Test
    void assistantRole_neverNarrowsWiderStanding() {
        QuarkusTransaction.requiringNew().run(() -> insertRole(teamleadUser, "ASSISTANT_TEAMLEAD"));
        RecruitmentPosition outside = position(otherPracticePositionUuid);
        assertTrue(visibility.canDecideOnApplication(teamleadUser, outside),
                "company-wide decide survives holding the assistant role too");
        assertTrue(visibility.canDecideFinalOutcome(teamleadUser, outside),
                "and so do final outcomes");
        assertTrue(visibility.canCreateCandidate(teamleadUser),
                "intake (via the TEAMLEAD grant) survives too");
    }

    // ---- Position creation (the §8 gap, closed 2026-08-23) ----------------------

    @Test
    void positionCreation_partnerTrackIsRecruiterTierOnly() {
        assertTrue(visibility.canCreatePosition(adminUser, RecruitmentHiringTrack.PARTNER, null));
        assertTrue(visibility.canCreatePosition(recruiterUser, RecruitmentHiringTrack.PARTNER, null));
        assertFalse(visibility.canCreatePosition(teamleadUser, RecruitmentHiringTrack.PARTNER, null),
                "the gap this gate closes: a team lead could open a partner req");
        assertFalse(visibility.canCreatePosition(assistantUser, RecruitmentHiringTrack.PARTNER, practiceUuid));
        assertFalse(visibility.canCreatePosition(plainUser, RecruitmentHiringTrack.PARTNER, null));
    }

    @Test
    void positionCreation_nonPartner_teamleadAnywhere_assistantOwnPracticeOnly() {
        assertTrue(visibility.canCreatePosition(teamleadUser,
                RecruitmentHiringTrack.PRACTICE_TEAM, otherPracticeUuid));
        assertTrue(visibility.canCreatePosition(assistantUser,
                RecruitmentHiringTrack.PRACTICE_TEAM, practiceUuid));
        assertFalse(visibility.canCreatePosition(assistantUser,
                RecruitmentHiringTrack.PRACTICE_TEAM, otherPracticeUuid),
                "◐ practice: not someone else's");
        assertFalse(visibility.canCreatePosition(assistantUser,
                RecruitmentHiringTrack.PRACTICE_TEAM, null),
                "a practice-less req is not in any assistant's scope");
        assertFalse(visibility.canCreatePosition(plainUser,
                RecruitmentHiringTrack.PRACTICE_TEAM, practiceUuid));
    }

    // ---- Involvement tier (unchanged routes: owner, led team, circle) -----------

    @Test
    void hiringOwner_seesOwnStaffPosition() {
        List<String> visible = visibleUuids(plainUser);
        assertTrue(visible.contains(staffPositionUuid));
        assertFalse(visible.contains(practicePositionUuid));
        assertFalse(visible.contains(partnerPositionUuid));
        assertTrue(canRead(plainUser, staffPositionUuid));
    }

    @Test
    void positionMutation_hiringOwnerMayChangeTheirOwn() {
        assertTrue(visibility.canMutatePosition(plainUser, position(staffPositionUuid)),
                "the named hiring owner acts on their own position");
        assertTrue(visibility.canDecideFinalOutcome(plainUser, position(staffPositionUuid)),
                "involvement keeps final outcomes — only the assistant route lacks them");
    }

    @Test
    void positionMutation_circleAloneGrantsNothingWithoutTheTeamleadRole() {
        addCircleMember(practicePositionUuid, plainUser);

        assertFalse(visibility.canMutatePosition(plainUser, position(practicePositionUuid)),
                "the circle grant is teamlead-gated (D11): a plain employee gets the "
                        + "restricted candidate view, not write access to the position");
    }

    @Test
    void positionMutation_recruiterTierAndAdminMayChangeAnyNonPartnerPosition() {
        assertTrue(visibility.canMutatePosition(recruiterUser, position(staffPositionUuid)));
        assertTrue(visibility.canMutatePosition(adminUser, position(staffPositionUuid)));
    }

    @Test
    void positionMutation_partnerTrackKeepsTheCircleManagementRule() {
        addCircleMember(partnerPositionUuid, plainUser); // PARTICIPANT
        RecruitmentPosition partner = position(partnerPositionUuid);

        assertTrue(visibility.canMutatePosition(adminUser, partner));
        assertTrue(visibility.canMutatePosition(recruiterUser, partner), "HR may always manage");
        assertFalse(visibility.canMutatePosition(plainUser, partner),
                "a PARTICIPANT may look but not touch — unchanged by the redesign");
    }

    // ---- Circle management gate ----------------------------------------------------

    @Test
    void circleManagement_ownersRecruitersHrAdmin_andAssistantInPractice() {
        addCircleMember(partnerPositionUuid, plainUser); // PARTICIPANT
        RecruitmentPosition partner = position(partnerPositionUuid);

        assertTrue(visibility.canManageCircle(adminUser, partner));
        assertTrue(visibility.canManageCircle(recruiterUser, partner), "HR may always manage");
        assertFalse(visibility.canManageCircle(plainUser, partner),
                "a PARTICIPANT can see the position but not widen the circle");
        assertFalse(visibility.canManageCircle(teamleadUser, partner),
                "no seat, no partner-circle management — decision 1 does not reach here");
        assertFalse(visibility.canManageCircle(assistantUser, partner),
                "the assistant practice route never fires on partner track");
    }

    // ---- "Your pipelines" ownership (landing card) -----------------------------------

    @Test
    void ownPositions_namedOwnerAndCircleCount_roleTiersDoNot() {
        assertTrue(visibility.ownPositionUuids(plainUser, allFixturePositions())
                        .contains(staffPositionUuid),
                "the named hiring owner");
        assertTrue(visibility.ownPositionUuids(recruiterUser, allFixturePositions()).isEmpty(),
                "HR reads everything and owns nothing");

        addCircleMember(practicePositionUuid, plainUser);
        assertTrue(visibility.ownPositionUuids(plainUser, allFixturePositions())
                        .contains(practicePositionUuid),
                "being invited onto a hire puts it on your landing page");
    }

    // ---- Bulk tagging (decision 15) --------------------------------------------------

    @Test
    void bulkTagging_recruitersTeamleadsAndAssistants_notPlainEmployees() {
        assertTrue(visibility.canBulkTag(adminUser));
        assertTrue(visibility.canBulkTag(recruiterUser));
        assertTrue(visibility.canBulkTag(teamleadUser), "decision 15: ● for TEAMLEAD");
        assertTrue(visibility.canBulkTag(assistantUser),
                "◐ practice — target scoping happens per candidate in the service");
        assertFalse(visibility.canBulkTag(plainUser));
        assertFalse(visibility.canBulkTag(currentLeadUser),
                "a practice_lead row is not a role (decision 11)");
    }

    // ---- Query filters on top of visibility ---------------------------------------

    @Test
    void listFilters_narrowByPracticeTrackAndStatus() {
        assertEquals(List.of(practicePositionUuid),
                visibility.filterPositions(adminUser, practiceUuid, null, null)
                        .stream().map(RecruitmentPosition::getUuid)
                        .filter(this::isFixture).toList());
        assertEquals(List.of(partnerPositionUuid),
                visibility.filterPositions(adminUser, null, RecruitmentHiringTrack.PARTNER, null)
                        .stream().map(RecruitmentPosition::getUuid)
                        .filter(this::isFixture).toList());
    }

    // ---- Helpers -------------------------------------------------------------------

    private boolean isFixture(String uuid) {
        return uuid.equals(practicePositionUuid) || uuid.equals(partnerPositionUuid)
                || uuid.equals(staffPositionUuid);
    }

    private List<String> visibleUuids(String viewer) {
        em.clear();
        return visibility.filterPositions(viewer, null, null, null).stream()
                .map(RecruitmentPosition::getUuid)
                .filter(uuid -> uuid.equals(practicePositionUuid)
                        || uuid.equals(partnerPositionUuid)
                        || uuid.equals(staffPositionUuid)
                        || uuid.equals(practiceOnlyPositionUuid)
                        || uuid.equals(otherPracticePositionUuid))
                .toList();
    }

    private boolean canRead(String viewer, String positionUuid) {
        return visibility.canReadPosition(viewer, position(positionUuid));
    }

    private RecruitmentPosition position(String uuid) {
        em.clear();
        return RecruitmentPosition.findById(uuid);
    }

    /** The fixture positions, as the landing service would pass them. */
    private List<RecruitmentPosition> allFixturePositions() {
        em.clear();
        return RecruitmentPosition.list("uuid in ?1",
                List.of(practicePositionUuid, partnerPositionUuid, staffPositionUuid,
                        practiceOnlyPositionUuid, otherPracticePositionUuid));
    }

    private void addCircleMember(String positionUuid, String userUuid) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_circle_members
                                    (position_uuid, user_uuid, role_in_circle, added_at, added_by_uuid)
                                VALUES (:p, :u, 'PARTICIPANT', NOW(3), :by)
                                """)
                        .setParameter("p", positionUuid)
                        .setParameter("u", userUuid)
                        .setParameter("by", adminUser)
                        .executeUpdate());
    }

    private void insertUser(String uuid) {
        em.createNativeQuery("""
                        INSERT INTO user (uuid, firstname, lastname, email, username, password, type,
                                          created, cpr, birthday)
                        VALUES (:uuid, 'Vis', 'Fixture', :email, :username, 'x', 'CONSULTANT',
                                NOW(), '0000000000', '2000-01-01')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("email", uuid + "@example.com")
                .setParameter("username", uuid)
                .executeUpdate();
    }

    /** Decision 3: the assistant's scope is {@code user.practice_uuid} — the membership field. */
    private void setUserPractice(String userUuid, String practiceUuid) {
        em.createNativeQuery("UPDATE user SET practice_uuid = :p WHERE uuid = :u")
                .setParameter("p", practiceUuid)
                .setParameter("u", userUuid)
                .executeUpdate();
    }

    private void insertRole(String userUuid, String role) {
        em.createNativeQuery("INSERT INTO roles (uuid, role, useruuid) VALUES (:uuid, :role, :user)")
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("role", role)
                .setParameter("user", userUuid)
                .executeUpdate();
    }

    private void insertPractice(String uuid) {
        em.createNativeQuery("""
                        INSERT INTO practice (code, uuid, name, active, sort_order,
                                              created_at, updated_at, created_by)
                        VALUES (:code, :uuid, 'Visibility Fixture', 1, 999, NOW(), NOW(), 'test')
                        """)
                .setParameter("code", "V" + uuid.substring(0, 7))
                .setParameter("uuid", uuid)
                .executeUpdate();
    }

    private void insertPracticeLead(String userUuid, String practiceUuid, String enddate) {
        em.createNativeQuery("""
                        INSERT INTO practice_lead (uuid, practice_uuid, useruuid, startdate, enddate,
                                                   created_at, updated_at, created_by)
                        VALUES (:uuid, :practice, :user, '2024-01-01', :enddate, NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("practice", practiceUuid)
                .setParameter("user", userUuid)
                .setParameter("enddate", enddate)
                .executeUpdate();
    }

    private void insertTeam(String uuid, String practiceUuid) {
        em.createNativeQuery("""
                        INSERT INTO team (uuid, name, shortname, practice_uuid)
                        VALUES (:uuid, 'Visibility Fixture Team', 'VFT', :practice)
                        """)
                .setParameter("uuid", uuid)
                .setParameter("practice", practiceUuid)
                .executeUpdate();
    }

    private void insertTeamLeader(String userUuid, String teamUuid) {
        em.createNativeQuery("""
                        INSERT INTO teamroles (uuid, teamuuid, useruuid, startdate, enddate, membertype)
                        VALUES (:uuid, :team, :user, '2024-01-01', NULL, 'LEADER')
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("team", teamUuid)
                .setParameter("user", userUuid)
                .executeUpdate();
    }

    private void insertPosition(String uuid, String title, String track,
                                String practiceUuid, String teamUuid, String ownerUuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, practice_uuid, team_uuid, hiring_owner_uuid,
                             demand_rag, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :title, :track, :practice, :team, :owner,
                                'GREEN', 'OPEN', NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("title", title)
                .setParameter("track", track)
                .setParameter("practice", practiceUuid)
                .setParameter("team", teamUuid)
                .setParameter("owner", ownerUuid)
                .executeUpdate();
    }
}
