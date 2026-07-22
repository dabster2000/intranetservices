package dk.trustworks.intranet.recruitmentservice.security;

import dk.trustworks.intranet.recruitmentservice.dto.CandidateApplicationInfo;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentApplicationService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 DoD (authz): one candidate simultaneously on a normal and a
 * partner-track application — a non-circle viewer's application list
 * excludes the partner one (the P2 circle filter reused at the
 * application level), a circle member and admin see both. Also locks the
 * decision tier ({@code canDecideOnApplication}): practice leads read but
 * never decide; circle PARTICIPANTs see but never decide.
 */
@QuarkusTest
class RecruitmentApplicationVisibilityIntegrationTest {

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentApplicationService applicationService;

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String adminUser;
    private String recruiterUser;    // HR — recruiter tier, NOT in the circle
    private String circleUser;       // HR + circle PARTICIPANT on the partner position
    private String practiceLeadUser; // current lead of practiceUuid, no roles
    private String teamleadUser;     // leads the normal position's team
    private String teamUuid;
    private String candidateUuid;

    private String normalPositionUuid;  // PRACTICE_TEAM on practiceUuid + teamUuid
    private String partnerPositionUuid; // PARTNER
    private String normalApplicationUuid;
    private String partnerApplicationUuid;
    private String terminalApplicationUuid; // rejected — excluded from open info

    @BeforeEach
    void seedFixtures() {
        practiceUuid = UUID.randomUUID().toString();
        adminUser = UUID.randomUUID().toString();
        recruiterUser = UUID.randomUUID().toString();
        circleUser = UUID.randomUUID().toString();
        practiceLeadUser = UUID.randomUUID().toString();
        teamleadUser = UUID.randomUUID().toString();
        teamUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        normalPositionUuid = UUID.randomUUID().toString();
        partnerPositionUuid = UUID.randomUUID().toString();
        normalApplicationUuid = UUID.randomUUID().toString();
        partnerApplicationUuid = UUID.randomUUID().toString();
        terminalApplicationUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            for (String user : List.of(adminUser, recruiterUser, circleUser,
                    practiceLeadUser, teamleadUser)) {
                insertUser(user);
            }
            insertRole(adminUser, "ADMIN");
            insertRole(recruiterUser, "HR");
            insertRole(circleUser, "HR");
            insertRole(teamleadUser, "TEAMLEAD");

            insertPractice(practiceUuid);
            insertPracticeLead(practiceLeadUser, practiceUuid);
            insertTeamLeader(teamleadUser, teamUuid);

            insertPosition(normalPositionUuid, "Consultant", "PRACTICE_TEAM", practiceUuid, teamUuid);
            insertPosition(partnerPositionUuid, "Partner hire", "PARTNER", null, null);
            insertCircleMember(partnerPositionUuid, circleUser, "PARTICIPANT");

            insertCandidate(candidateUuid);
            insertApplication(normalApplicationUuid, candidateUuid, normalPositionUuid, null);
            insertApplication(partnerApplicationUuid, candidateUuid, partnerPositionUuid, null);
            insertApplication(terminalApplicationUuid, candidateUuid, normalPositionUuid, "REJECTED");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_applications WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            List<String> positions = List.of(normalPositionUuid, partnerPositionUuid);
            em.createNativeQuery("DELETE FROM recruitment_circle_members WHERE position_uuid IN :p")
                    .setParameter("p", positions).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid IN :p")
                    .setParameter("p", positions).executeUpdate();
            List<String> users = List.of(adminUser, recruiterUser, circleUser,
                    practiceLeadUser, teamleadUser);
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

    // ---- Filtered application list (the DoD case) --------------------------------

    @Test
    void nonCircleViewer_applicationListExcludesThePartnerOne() {
        List<String> visible = visibleApplicationUuids(recruiterUser);
        assertTrue(visible.contains(normalApplicationUuid));
        assertFalse(visible.contains(partnerApplicationUuid),
                "a non-circle recruiter must not see the partner-track application");
    }

    @Test
    void circleMemberAndAdmin_seeBothApplications() {
        List<String> circleVisible = visibleApplicationUuids(circleUser);
        assertTrue(circleVisible.contains(normalApplicationUuid));
        assertTrue(circleVisible.contains(partnerApplicationUuid));

        List<String> adminVisible = visibleApplicationUuids(adminUser);
        assertTrue(adminVisible.containsAll(
                List.of(normalApplicationUuid, partnerApplicationUuid)));
    }

    @Test
    void canReadApplication_followsThePositionRule() {
        assertFalse(visibility.canReadApplication(recruiterUser, application(partnerApplicationUuid)),
                "single-row reads apply the same circle filter");
        assertTrue(visibility.canReadApplication(circleUser, application(partnerApplicationUuid)));
        assertTrue(visibility.canReadApplication(recruiterUser, application(normalApplicationUuid)));
    }

    // ---- Candidate-list open-application info -------------------------------------

    @Test
    void openApplicationInfo_isViewerFiltered_andExcludesTerminals() {
        Map<String, List<CandidateApplicationInfo>> forRecruiter =
                applicationService.openApplicationInfoByCandidate(
                        recruiterUser, List.of(candidateUuid));
        List<String> uuids = forRecruiter.getOrDefault(candidateUuid, List.of()).stream()
                .map(CandidateApplicationInfo::uuid).toList();
        assertTrue(uuids.contains(normalApplicationUuid));
        assertFalse(uuids.contains(partnerApplicationUuid), "partner app hidden outside the circle");
        assertFalse(uuids.contains(terminalApplicationUuid), "terminal apps are not 'active'");

        Map<String, List<CandidateApplicationInfo>> forCircle =
                applicationService.openApplicationInfoByCandidate(
                        circleUser, List.of(candidateUuid));
        List<String> circleUuids = forCircle.getOrDefault(candidateUuid, List.of()).stream()
                .map(CandidateApplicationInfo::uuid).toList();
        assertTrue(circleUuids.containsAll(List.of(normalApplicationUuid, partnerApplicationUuid)));

        // Position titles ride along for the list rows.
        assertEquals("Consultant", forRecruiter.get(candidateUuid).stream()
                .filter(i -> i.uuid().equals(normalApplicationUuid))
                .findFirst().orElseThrow().positionTitle());
    }

    // ---- Decision tier ---------------------------------------------------------------

    @Test
    void decisionTier_practiceLeadReadsButNeverDecides() {
        RecruitmentPosition normal = position(normalPositionUuid);
        assertTrue(visibility.canReadPosition(practiceLeadUser, normal),
                "practice lead has read access (P2)");
        assertFalse(visibility.canDecideOnApplication(practiceLeadUser, normal),
                "spec §7.2: stage moves/decisions are not a practice-lead capability");
    }

    @Test
    void decisionTier_teamleadRecruiterAdminDecideOnNormalTrack() {
        RecruitmentPosition normal = position(normalPositionUuid);
        assertTrue(visibility.canDecideOnApplication(teamleadUser, normal),
                "leader of the position's team decides");
        assertTrue(visibility.canDecideOnApplication(recruiterUser, normal));
        assertTrue(visibility.canDecideOnApplication(adminUser, normal));
    }

    @Test
    void decisionTier_partnerTrack_circleParticipantSeesButDoesNotDecide() {
        RecruitmentPosition partner = position(partnerPositionUuid);
        assertTrue(visibility.canReadPosition(circleUser, partner));
        // circleUser holds HR — HR may manage circles, hence decide. Strip the
        // role to isolate the PARTICIPANT rule.
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("DELETE FROM roles WHERE useruuid = :u")
                        .setParameter("u", circleUser).executeUpdate());
        assertTrue(visibility.canReadPosition(circleUser, partner),
                "circle membership alone still grants read");
        assertFalse(visibility.canDecideOnApplication(circleUser, partner),
                "a PARTICIPANT may look but not decide (P2 rule carried to applications)");
    }

    // ---- Helpers ----------------------------------------------------------------------

    private List<String> visibleApplicationUuids(String viewer) {
        em.clear();
        return visibility.filterApplications(viewer, candidateUuid).stream()
                .map(RecruitmentApplication::getUuid)
                .toList();
    }

    private RecruitmentApplication application(String uuid) {
        em.clear();
        return RecruitmentApplication.findById(uuid);
    }

    private RecruitmentPosition position(String uuid) {
        em.clear();
        return RecruitmentPosition.findById(uuid);
    }

    private void insertUser(String uuid) {
        em.createNativeQuery("""
                        INSERT INTO user (uuid, firstname, lastname, email, username, password, type,
                                          created, cpr, birthday)
                        VALUES (:uuid, 'AppVis', 'Fixture', :email, :username, 'x', 'CONSULTANT',
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
                        VALUES (:code, :uuid, 'AppVis Fixture', 1, 999, NOW(), NOW(), 'test')
                        """)
                .setParameter("code", "W" + uuid.substring(0, 7))
                .setParameter("uuid", uuid)
                .executeUpdate();
    }

    private void insertPracticeLead(String userUuid, String practiceUuid) {
        em.createNativeQuery("""
                        INSERT INTO practice_lead (uuid, practice_uuid, useruuid, startdate, enddate,
                                                   created_at, updated_at, created_by)
                        VALUES (:uuid, :practice, :user, '2024-01-01', NULL, NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("practice", practiceUuid)
                .setParameter("user", userUuid)
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

    private void insertCircleMember(String positionUuid, String userUuid, String role) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_circle_members
                            (position_uuid, user_uuid, role_in_circle, added_at, added_by_uuid)
                        VALUES (:p, :u, :role, NOW(3), :by)
                        """)
                .setParameter("p", positionUuid)
                .setParameter("u", userUuid)
                .setParameter("role", role)
                .setParameter("by", adminUser)
                .executeUpdate();
    }

    private void insertCandidate(String uuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_candidates
                            (uuid, first_name, last_name, status, source, created_by_useruuid,
                             created_at, updated_at)
                        VALUES (:uuid, 'AppVis', 'Fixture', 'ACTIVE', 'OTHER', :actor, NOW(), NOW())
                        """)
                .setParameter("uuid", uuid)
                .setParameter("actor", adminUser)
                .executeUpdate();
    }

    private void insertPosition(String uuid, String title, String track,
                                String practiceUuid, String teamUuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, practice_uuid, team_uuid, stage_set,
                             demand_rag, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :title, :track, :practice, :team,
                                '["SCREENING","INTERVIEW_1","INTERVIEW_2","OFFER","HIRED"]',
                                'GREEN', 'OPEN', NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("title", title)
                .setParameter("track", track)
                .setParameter("practice", practiceUuid)
                .setParameter("team", teamUuid)
                .executeUpdate();
    }

    private void insertApplication(String uuid, String candidateUuid, String positionUuid,
                                   String terminal) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_applications
                            (uuid, candidate_uuid, position_uuid, stage, terminal,
                             stage_entered_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :candidate, :position, 'SCREENING', :terminal,
                                NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("candidate", candidateUuid)
                .setParameter("position", positionUuid)
                .setParameter("terminal", terminal)
                .executeUpdate();
    }
}
