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
 * P2 DoD (authz, query-level via {@code RecruitmentVisibility}):
 * <ul>
 *   <li>a partner-track position is invisible in list/get for a non-circle
 *       recruiter (HR) and teamlead — and visible after
 *       {@code CIRCLE_MEMBER_ADDED};</li>
 *   <li>a <em>current</em> practice lead ({@code enddate IS NULL}) has read
 *       access to their practice's non-partner positions; a former lead
 *       ({@code enddate} set) does not;</li>
 *   <li>ADMIN sees everything; the circle is a hard filter that ownership
 *       does not bypass.</li>
 * </ul>
 * Fixtures are raw rows (users, roles, practice, practice_lead, teamroles,
 * positions, circle members) so the helper is tested in isolation from the
 * command handlers.
 */
@QuarkusTest
class RecruitmentVisibilityIntegrationTest {

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String adminUser;
    private String recruiterUser;   // HR role — recruiter tier
    private String teamleadUser;    // leads teamUuid via teamroles LEADER
    private String currentLeadUser; // practice_lead row, enddate IS NULL
    private String formerLeadUser;  // practice_lead row, enddate set
    private String plainUser;       // no roles, no leads, no circles
    private String teamUuid;

    private String practicePositionUuid; // PRACTICE_TEAM on practiceUuid + teamUuid
    private String partnerPositionUuid;  // PARTNER
    private String staffPositionUuid;    // STAFF_ROLE owned by plainUser
    /**
     * PRACTICE_TEAM on practiceUuid with NO team and NO named owner — the
     * production shape (every prod position has {@code team_uuid} NULL and
     * almost none has an owner), and therefore the row that isolates the
     * practice route from the pre-existing team and owner routes.
     */
    private String practiceOnlyPositionUuid;

    private final List<Runnable> cleanupSteps = new ArrayList<>();

    @BeforeEach
    void seedFixtures() {
        practiceUuid = UUID.randomUUID().toString();
        adminUser = UUID.randomUUID().toString();
        recruiterUser = UUID.randomUUID().toString();
        teamleadUser = UUID.randomUUID().toString();
        currentLeadUser = UUID.randomUUID().toString();
        formerLeadUser = UUID.randomUUID().toString();
        plainUser = UUID.randomUUID().toString();
        teamUuid = UUID.randomUUID().toString();
        practicePositionUuid = UUID.randomUUID().toString();
        partnerPositionUuid = UUID.randomUUID().toString();
        staffPositionUuid = UUID.randomUUID().toString();
        practiceOnlyPositionUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            for (String user : List.of(adminUser, recruiterUser, teamleadUser,
                    currentLeadUser, formerLeadUser, plainUser)) {
                insertUser(user);
            }
            insertRole(adminUser, "ADMIN");
            insertRole(recruiterUser, "HR");
            insertRole(teamleadUser, "TEAMLEAD");

            insertPractice(practiceUuid);
            insertPracticeLead(currentLeadUser, practiceUuid, null);
            insertPracticeLead(formerLeadUser, practiceUuid, "2025-12-31");
            // The team belongs to the practice — the hop
            // practicesOfCurrentlyLedTeams walks for "Your pipelines".
            insertTeam(teamUuid, practiceUuid);
            insertTeamLeader(teamleadUser, teamUuid);

            insertPosition(practicePositionUuid, "Consultant", "PRACTICE_TEAM", practiceUuid, teamUuid, null);
            insertPosition(partnerPositionUuid, "Partner hire", "PARTNER", null, null, null);
            insertPosition(staffPositionUuid, "Office manager", "STAFF_ROLE", null, null, plainUser);
            insertPosition(practiceOnlyPositionUuid, "Consultant (no team)", "PRACTICE_TEAM",
                    practiceUuid, null, null);
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            List<String> positions = List.of(practicePositionUuid, partnerPositionUuid,
                    staffPositionUuid, practiceOnlyPositionUuid);
            em.createNativeQuery("DELETE FROM recruitment_circle_members WHERE position_uuid IN :p")
                    .setParameter("p", positions).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid IN :p")
                    .setParameter("p", positions).executeUpdate();
            List<String> users = List.of(adminUser, recruiterUser, teamleadUser,
                    currentLeadUser, formerLeadUser, plainUser);
            em.createNativeQuery("DELETE FROM practice_lead WHERE useruuid IN :u")
                    .setParameter("u", users).executeUpdate();
            em.createNativeQuery("DELETE FROM teamroles WHERE useruuid IN :u")
                    .setParameter("u", users).executeUpdate();
            em.createNativeQuery("DELETE FROM roles WHERE useruuid IN :u")
                    .setParameter("u", users).executeUpdate();
            em.createNativeQuery("DELETE FROM team WHERE uuid = :t")
                    .setParameter("t", teamUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM practice WHERE uuid = :p")
                    .setParameter("p", practiceUuid).executeUpdate();
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

    // ---- Involvement tier ---------------------------------------------------------

    /**
     * Go-live decision D3 (2026-08-10) moved {@code TEAMLEAD} into
     * {@link RecruitmentVisibility#POSITION_READ_ROLES}, so a teamlead READS
     * every non-partner position — including ones they are in no way involved
     * with. This test asserted the opposite until 2026-08-11 and had been
     * failing silently ever since (it is a {@code @QuarkusTest}, excluded from
     * the CI deploy gate), because D3 changed the rule without changing it.
     * <p>
     * Reaffirmed as intended on 2026-08-11: the landing page narrows what it
     * <em>leads with</em> ({@code ownPositionUuids}), while the positions list
     * and the board picker deliberately stay wide so a teamlead can still go
     * look at another practice's board on purpose. Acting on one they do not
     * own is a separate, narrower question — {@code canDecideOnApplication}
     * and {@code canMutatePosition}.
     */
    @Test
    void teamlead_readsEveryNonPartnerPosition_evenUninvolved() {
        List<String> visible = visibleUuids(teamleadUser);
        assertTrue(visible.contains(practicePositionUuid), "position targeting the led team");
        assertTrue(visible.contains(staffPositionUuid),
                "D3: the read tier is company-wide for non-partner positions");
        assertFalse(visible.contains(partnerPositionUuid),
                "the partner circle stays a hard filter for every role but ADMIN");

        // ...and the two narrower questions still answer no on that same row.
        assertFalse(visibility.canMutatePosition(teamleadUser, position(staffPositionUuid)),
                "reading it is not changing it");
        assertFalse(visibility.ownPositionUuids(teamleadUser, allFixturePositions())
                        .contains(staffPositionUuid),
                "and it is not one of 'Your pipelines' either");
    }

    // ---- The practice route (decided 2026-08-12) -------------------------------------

    /**
     * The change that started it all: a team lead had decision rights only
     * where someone had named them hiring owner or where the position carried
     * their team — and in production no position carries a team, so they were
     * read-only on their own practice's pipelines. Leading a team that belongs
     * to the practice is now itself the involvement.
     */
    @Test
    void teamlead_decidesOnTheirPracticesPositions_withNoTeamAndNoOwnerOnTheRow() {
        RecruitmentPosition ownPractice = position(practiceOnlyPositionUuid);
        assertNull(ownPractice.getTeamUuid(), "the fixture must isolate the practice route");
        assertNull(ownPractice.getHiringOwnerUuid());

        assertTrue(visibility.canDecideOnApplication(teamleadUser, ownPractice),
                "leading a team in the practice grants the owner's pipeline rights");
        assertTrue(visibility.canMutatePosition(teamleadUser, ownPractice),
                "and the same authority edits or closes the position");
        assertTrue(visibility.isRecruiterOrHiringOwner(teamleadUser, ownPractice),
                "including the owner's elevated moves — a forward stage skip");
    }

    /** Same rule, same set: what the landing page calls yours is what you may act on. */
    @Test
    void practiceRoute_matchesTheYourPipelinesScoping() {
        assertTrue(visibility.ownPositionUuids(teamleadUser, allFixturePositions())
                        .contains(practiceOnlyPositionUuid),
                "'Your pipelines' already counted it as theirs");
        assertTrue(visibility.ownPractices(teamleadUser).contains(practiceUuid));
    }

    /** The boundary: another practice's position stays read-only. */
    @Test
    void teamlead_cannotDecideOutsideTheirPractice() {
        RecruitmentPosition elsewhere = position(staffPositionUuid);
        assertTrue(canRead(teamleadUser, staffPositionUuid), "D3 still shows it to them");
        assertFalse(visibility.canDecideOnApplication(teamleadUser, elsewhere));
        assertFalse(visibility.canMutatePosition(teamleadUser, elsewhere));
        assertFalse(visibility.isRecruiterOrHiringOwner(teamleadUser, elsewhere));
    }

    /**
     * Running a practice must never become a back door into a confidential
     * hire: on partner track the circle is still the only key.
     */
    @Test
    void practiceRoute_neverOpensPartnerTrack() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery(
                                "UPDATE recruitment_positions SET practice_uuid = :pr WHERE uuid = :p")
                        .setParameter("pr", practiceUuid)
                        .setParameter("p", partnerPositionUuid)
                        .executeUpdate());

        RecruitmentPosition partner = position(partnerPositionUuid);
        assertFalse(visibility.canDecideOnApplication(teamleadUser, partner),
                "a practice lead is not a circle member");
        assertFalse(visibility.canMutatePosition(teamleadUser, partner));
        assertFalse(canRead(teamleadUser, partnerPositionUuid),
                "and they cannot even see it");
    }

    /**
     * A registered practice lead was READ-only until 2026-08-12; Hans chose
     * to line them up with the landing page's ownership rule, so they act too.
     * A former lead still does not — the grant is temporal on both sides.
     */
    @Test
    void currentPracticeLead_decides_formerDoesNot() {
        RecruitmentPosition ownPractice = position(practiceOnlyPositionUuid);
        assertTrue(visibility.canDecideOnApplication(currentLeadUser, ownPractice));
        assertFalse(visibility.canDecideOnApplication(formerLeadUser, ownPractice),
                "a practice_lead row with enddate set grants nothing");
    }

    /** A plain employee gains nothing: they lead no team and no practice. */
    @Test
    void plainEmployee_gainsNothingFromThePracticeRoute() {
        assertFalse(visibility.canDecideOnApplication(plainUser, position(practiceOnlyPositionUuid)));
        assertTrue(visibility.ownPractices(plainUser).isEmpty());
    }

    @Test
    void hiringOwner_seesOwnStaffPosition() {
        List<String> visible = visibleUuids(plainUser);
        assertTrue(visible.contains(staffPositionUuid));
        assertFalse(visible.contains(practicePositionUuid));
        assertFalse(visible.contains(partnerPositionUuid));
        assertTrue(canRead(plainUser, staffPositionUuid));
    }

    // ---- Practice-lead read access (temporal) ----------------------------------------

    @Test
    void currentPracticeLead_readsTheirPracticesNonPartnerPositions() {
        assertTrue(visibility.isCurrentPracticeLead(currentLeadUser, practiceUuid));
        assertTrue(visibleUuids(currentLeadUser).contains(practicePositionUuid));
        assertTrue(canRead(currentLeadUser, practicePositionUuid));
    }

    @Test
    void formerPracticeLead_hasNoReadAccess() {
        assertFalse(visibility.isCurrentPracticeLead(formerLeadUser, practiceUuid),
                "a practice_lead row with enddate set is not a current lead");
        assertFalse(visibleUuids(formerLeadUser).contains(practicePositionUuid));
        assertFalse(canRead(formerLeadUser, practicePositionUuid));
    }

    @Test
    void practiceLeadGrant_doesNotExtendToPartnerTrackOfTheSamePractice() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_positions SET practice_uuid = :pr WHERE uuid = :p")
                        .setParameter("pr", practiceUuid)
                        .setParameter("p", partnerPositionUuid)
                        .executeUpdate());
        assertFalse(visibleUuids(currentLeadUser).contains(partnerPositionUuid),
                "practice-lead read access covers non-partner positions only");
        assertFalse(canRead(currentLeadUser, partnerPositionUuid));
    }

    // ---- Circle management gate ----------------------------------------------------

    @Test
    void circleManagement_ownersRecruitersHrAndAdmin_only() {
        addCircleMember(partnerPositionUuid, plainUser); // PARTICIPANT
        RecruitmentPosition partner = position(partnerPositionUuid);

        assertTrue(visibility.canManageCircle(adminUser, partner));
        assertTrue(visibility.canManageCircle(recruiterUser, partner), "HR may always manage");
        assertFalse(visibility.canManageCircle(plainUser, partner),
                "a PARTICIPANT can see the position but not widen the circle");
        assertFalse(visibility.canManageCircle(teamleadUser, partner));
    }

    // ---- Position mutation gate (edit / close) --------------------------------------
    // Go-live spec §3, "Positions — create/edit/close": ADMIN/HR/RECRUITMENT
    // everywhere, TEAMLEAD own only. Reading is not enough — D3 shows every
    // teamlead every non-partner position, so without this gate the read tier
    // doubles as a company-wide write tier.

    @Test
    void positionMutation_teamleadMayNotChangeAPositionTheyMerelyRead() {
        RecruitmentPosition someoneElses = position(staffPositionUuid);

        assertTrue(visibility.canReadPosition(teamleadUser, someoneElses),
                "D3: a teamlead reads every non-partner position");
        assertFalse(visibility.canMutatePosition(teamleadUser, someoneElses),
                "...but reading it must not let them edit or close it");
    }

    @Test
    void positionMutation_hiringOwnerMayChangeTheirOwn() {
        assertTrue(visibility.canMutatePosition(plainUser, position(staffPositionUuid)),
                "the named hiring owner acts on their own position");
    }

    @Test
    void positionMutation_currentLeadOfThePositionsTeamMayChange() {
        assertTrue(visibility.canMutatePosition(teamleadUser, position(practicePositionUuid)),
                "the position targets the team this user currently leads");
    }

    @Test
    void positionMutation_teamleadInTheCircleMayChange() {
        assertFalse(visibility.canMutatePosition(teamleadUser, position(staffPositionUuid)));

        addCircleMember(staffPositionUuid, teamleadUser);

        assertTrue(visibility.canMutatePosition(teamleadUser, position(staffPositionUuid)),
                "circle membership grants a TEAMLEAD decision rights (D4)");
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

    /**
     * Reversed on 2026-08-12. Spec §7.2 had practice-lead access as read-only,
     * which left the people actually running a practice unable to move a
     * candidate through it; Hans chose to line the decision gate up with the
     * landing page's "All open pipelines" ownership rule, and a current
     * practice lead is one of that rule's four routes. Their <em>own</em>
     * practice only — the boundary is asserted by
     * {@link #teamlead_cannotDecideOutsideTheirPractice} — and a former lead
     * still gets nothing ({@link #currentPracticeLead_decides_formerDoesNot}).
     */
    @Test
    void positionMutation_currentPracticeLeadMayChangeTheirOwnPractices() {
        RecruitmentPosition theirPractices = position(practicePositionUuid);

        assertTrue(visibility.canReadPosition(currentLeadUser, theirPractices));
        assertTrue(visibility.canMutatePosition(currentLeadUser, theirPractices),
                "running the practice now carries the position owner's rights");
        assertFalse(visibility.canMutatePosition(formerLeadUser, theirPractices),
                "but only while they are actually leading it");
    }

    @Test
    void positionMutation_partnerTrackKeepsTheCircleManagementRule() {
        addCircleMember(partnerPositionUuid, plainUser); // PARTICIPANT
        RecruitmentPosition partner = position(partnerPositionUuid);

        assertTrue(visibility.canMutatePosition(adminUser, partner));
        assertTrue(visibility.canMutatePosition(recruiterUser, partner), "HR may always manage");
        assertFalse(visibility.canMutatePosition(plainUser, partner),
                "a PARTICIPANT may look but not touch — unchanged by the new gate");
    }

    // ---- "Your pipelines" ownership (landing card, 2026-08-11) -----------------------
    // Presentation-only: which positions the landing LEADS with. Never widens
    // or narrows read access.

    @Test
    void ownPositions_teamleadGetsTheirLedTeamsPractice_notTheWholeCompany() {
        // The hop that matters in practice: this user has no practice_lead row
        // and owns nothing — they simply lead a team, and that team belongs to
        // a practice. Before this rule their card led with every non-partner
        // position in the company.
        Set<String> own = visibility.ownPositionUuids(teamleadUser, allFixturePositions());

        assertTrue(own.contains(practicePositionUuid), "the led team's practice");
        assertFalse(own.contains(staffPositionUuid), "someone else's staff position");
        assertTrue(visibility.canReadPosition(teamleadUser, position(staffPositionUuid)),
                "read access is untouched — ownership is presentation only");
    }

    @Test
    void ownPositions_hiringOwnerAndPracticeLeadBothCount() {
        assertTrue(visibility.ownPositionUuids(plainUser, allFixturePositions())
                        .contains(staffPositionUuid),
                "the named hiring owner");
        assertTrue(visibility.ownPositionUuids(currentLeadUser, allFixturePositions())
                        .contains(practicePositionUuid),
                "a registered lead of the position's practice");
    }

    @Test
    void ownPositions_formerLeadKeepsNothing() {
        assertTrue(visibility.ownPositionUuids(formerLeadUser, allFixturePositions()).isEmpty(),
                "a practice_lead row with enddate set stops counting, like everywhere else");
    }

    @Test
    void ownPositions_anInvitedCircleMemberCountsToo() {
        assertFalse(visibility.ownPositionUuids(plainUser, allFixturePositions())
                .contains(practicePositionUuid));

        addCircleMember(practicePositionUuid, plainUser);

        assertTrue(visibility.ownPositionUuids(plainUser, allFixturePositions())
                        .contains(practicePositionUuid),
                "being invited onto a hire puts it on your landing page");
    }

    @Test
    void ownPositions_areEmptyForSomeoneWithNoInvolvement() {
        // The case the landing page must not turn into a redirect: a viewer
        // who reads widely by role but owns nothing gets an empty card, not
        // an empty page. (recruiterUser is HR — reads everything, owns none.)
        assertTrue(visibility.ownPositionUuids(recruiterUser, allFixturePositions()).isEmpty());
        assertTrue(visibility.filterPositions(recruiterUser, null, null, null).stream()
                        .anyMatch(p -> p.getUuid().equals(practicePositionUuid)),
                "...while still reading the very position that is not theirs");
    }

    @Test
    void ownPositions_practicesOfLedTeams_isNotThePracticeLeadTable() {
        // Two different mechanisms, deliberately: teamleadUser leads a TEAM
        // (teamroles), currentLeadUser leads a PRACTICE (practice_lead).
        // Neither implies the other.
        assertTrue(visibility.practicesOfCurrentlyLedTeams(teamleadUser).contains(practiceUuid));
        assertTrue(visibility.currentlyLedPractices(teamleadUser).isEmpty(),
                "leading a team does not create a practice_lead row");
        assertTrue(visibility.practicesOfCurrentlyLedTeams(currentLeadUser).isEmpty(),
                "leading a practice does not make you a team lead");
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
                .filter(this::isFixture)
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
                        practiceOnlyPositionUuid));
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
