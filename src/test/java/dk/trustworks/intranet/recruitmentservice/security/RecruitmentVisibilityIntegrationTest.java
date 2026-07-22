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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            insertTeamLeader(teamleadUser, teamUuid);

            insertPosition(practicePositionUuid, "Consultant", "PRACTICE_TEAM", practiceUuid, teamUuid, null);
            insertPosition(partnerPositionUuid, "Partner hire", "PARTNER", null, null, null);
            insertPosition(staffPositionUuid, "Office manager", "STAFF_ROLE", null, null, plainUser);
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            List<String> positions = List.of(practicePositionUuid, partnerPositionUuid, staffPositionUuid);
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

    @Test
    void teamlead_seesLedTeamPositions_only() {
        List<String> visible = visibleUuids(teamleadUser);
        assertTrue(visible.contains(practicePositionUuid), "position targeting the led team");
        assertFalse(visible.contains(staffPositionUuid), "someone else's staff position");
        assertFalse(visible.contains(partnerPositionUuid));
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
