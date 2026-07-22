package dk.trustworks.intranet.recruitmentservice.resources;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * P4 DoD (API, end-to-end through the resource):
 * <ul>
 *   <li>reject without a reason code → 400 (bean validation);</li>
 *   <li>partner-referral guard: a teamlead's reject → 403 with a guidance
 *       payload; a recruiter's reject → 200 (roles resolved from real
 *       {@code roles}/{@code teamroles} fixture rows via X-Requested-By);</li>
 *   <li>a partner-track application answers 404 for a viewer outside the
 *       circle — existence never leaks.</li>
 * </ul>
 */
@QuarkusTest
class RecruitmentApplicationResourceApiTest {

    private static final String FLAG = "recruitment.pipeline.enabled";

    @Inject
    EntityManager em;

    private String candidateUuid;
    private String positionUuid;
    private String partnerPositionUuid;
    private String applicationUuid;
    private String partnerApplicationUuid;
    private String teamUuid;
    private String teamleadUser;
    private String recruiterUser;
    private String previousFlagValue;

    @BeforeEach
    void seed() {
        candidateUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        partnerPositionUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        partnerApplicationUuid = UUID.randomUUID().toString();
        teamUuid = UUID.randomUUID().toString();
        teamleadUser = UUID.randomUUID().toString();
        recruiterUser = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            insertUser(teamleadUser);
            insertUser(recruiterUser);
            em.createNativeQuery("INSERT INTO roles (uuid, role, useruuid) VALUES (:u, 'TEAMLEAD', :user)")
                    .setParameter("u", UUID.randomUUID().toString())
                    .setParameter("user", teamleadUser).executeUpdate();
            em.createNativeQuery("INSERT INTO roles (uuid, role, useruuid) VALUES (:u, 'HR', :user)")
                    .setParameter("u", UUID.randomUUID().toString())
                    .setParameter("user", recruiterUser).executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO teamroles (uuid, teamuuid, useruuid, startdate, enddate, membertype)
                            VALUES (:u, :team, :user, '2024-01-01', NULL, 'LEADER')
                            """)
                    .setParameter("u", UUID.randomUUID().toString())
                    .setParameter("team", teamUuid)
                    .setParameter("user", teamleadUser).executeUpdate();

            // Partner-referral candidate: the sponsor mandate drives the guard.
            em.createNativeQuery("""
                            INSERT INTO recruitment_candidates
                                (uuid, first_name, last_name, status, source, sponsoring_partner_uuid,
                                 created_by_useruuid, created_at, updated_at)
                            VALUES (:uuid, 'Api', 'Fixture', 'ACTIVE', 'PARTNER_REFERRAL', :sponsor,
                                    :actor, NOW(), NOW())
                            """)
                    .setParameter("uuid", candidateUuid)
                    .setParameter("sponsor", UUID.randomUUID().toString())
                    .setParameter("actor", recruiterUser).executeUpdate();

            insertPosition(positionUuid, "Consultant", "PRACTICE_TEAM", teamUuid);
            insertPosition(partnerPositionUuid, "Partner hire", "PARTNER", null);
            insertApplication(applicationUuid, candidateUuid, positionUuid);
            insertApplication(partnerApplicationUuid, candidateUuid, partnerPositionUuid);

            List<?> current = em.createNativeQuery(
                            "SELECT setting_value FROM app_settings WHERE setting_key = :key")
                    .setParameter("key", FLAG).getResultList();
            previousFlagValue = current.isEmpty() ? null : (String) current.get(0);
            if (previousFlagValue == null) {
                em.createNativeQuery("""
                                INSERT INTO app_settings (setting_key, setting_value, category)
                                VALUES (:key, 'true', 'recruitment')
                                """)
                        .setParameter("key", FLAG).executeUpdate();
            } else {
                em.createNativeQuery(
                                "UPDATE app_settings SET setting_value = 'true' WHERE setting_key = :key")
                        .setParameter("key", FLAG).executeUpdate();
            }
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_consents WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_applications WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid IN :p")
                    .setParameter("p", List.of(positionUuid, partnerPositionUuid)).executeUpdate();
            List<String> users = List.of(teamleadUser, recruiterUser);
            em.createNativeQuery("DELETE FROM teamroles WHERE useruuid IN :u")
                    .setParameter("u", users).executeUpdate();
            em.createNativeQuery("DELETE FROM roles WHERE useruuid IN :u")
                    .setParameter("u", users).executeUpdate();
            em.createNativeQuery("DELETE FROM user WHERE uuid IN :u")
                    .setParameter("u", users).executeUpdate();
            if (previousFlagValue == null) {
                em.createNativeQuery("DELETE FROM app_settings WHERE setting_key = :key")
                        .setParameter("key", FLAG).executeUpdate();
            } else {
                em.createNativeQuery(
                                "UPDATE app_settings SET setting_value = :value WHERE setting_key = :key")
                        .setParameter("value", previousFlagValue)
                        .setParameter("key", FLAG).executeUpdate();
            }
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void rejectWithoutReasonCode_is400() {
        given()
                .contentType("application/json")
                .header("X-Requested-By", recruiterUser)
                .body("{\"note\": \"no code supplied\"}")
                .when()
                .post("/recruitment/applications/{uuid}/reject", applicationUuid)
                .then()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void partnerReferralReject_byTeamlead_is403WithGuidance() {
        given()
                .contentType("application/json")
                .header("X-Requested-By", teamleadUser)
                .body("{\"reasonCode\": \"CULTURE_FIT\"}")
                .when()
                .post("/recruitment/applications/{uuid}/reject", applicationUuid)
                .then()
                .statusCode(403)
                .body("guidance", Matchers.containsString("Escalate to the recruiter"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void partnerReferralReject_byRecruiter_succeeds() {
        given()
                .contentType("application/json")
                .header("X-Requested-By", recruiterUser)
                .body("{\"reasonCode\": \"CULTURE_FIT\", \"note\": \"not the right DNA match\"}")
                .when()
                .post("/recruitment/applications/{uuid}/reject", applicationUuid)
                .then()
                .statusCode(200)
                .body("terminal", Matchers.equalTo("REJECTED"))
                .body("rejectionReasonCode", Matchers.equalTo("CULTURE_FIT"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void partnerTrackApplication_answers404OutsideTheCircle() {
        // The recruiter (HR) is NOT in the partner position's circle — the
        // application must not even reveal its existence.
        given()
                .contentType("application/json")
                .header("X-Requested-By", recruiterUser)
                .body("{\"stage\": \"INTERVIEW_1\"}")
                .when()
                .post("/recruitment/applications/{uuid}/stage", partnerApplicationUuid)
                .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void teamleadOfThePositionsTeam_mayAdvanceSingleStage() {
        given()
                .contentType("application/json")
                .header("X-Requested-By", teamleadUser)
                .body("{\"stage\": \"INTERVIEW_1\"}")
                .when()
                .post("/recruitment/applications/{uuid}/stage", applicationUuid)
                .then()
                .statusCode(200)
                .body("stage", Matchers.equalTo("INTERVIEW_1"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void writeEndpoints_requireWriteScope() {
        given()
                .contentType("application/json")
                .header("X-Requested-By", recruiterUser)
                .body("{\"stage\": \"INTERVIEW_1\"}")
                .when()
                .post("/recruitment/applications/{uuid}/stage", applicationUuid)
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void listForCandidate_returnsVisibleApplicationsWithPositionFacts() {
        given()
                .header("X-Requested-By", recruiterUser)
                .when()
                .get("/recruitment/candidates/{uuid}/applications", candidateUuid)
                .then()
                .statusCode(200)
                .body("applications.size()", Matchers.equalTo(1))
                .body("applications[0].uuid", Matchers.equalTo(applicationUuid))
                .body("applications[0].positionTitle", Matchers.equalTo("Consultant"))
                .body("applications[0].stage", Matchers.equalTo("SCREENING"));
    }

    // ---- Fixture helpers -------------------------------------------------------------

    private void insertUser(String uuid) {
        em.createNativeQuery("""
                        INSERT INTO user (uuid, firstname, lastname, email, username, password, type,
                                          created, cpr, birthday)
                        VALUES (:uuid, 'Api', 'Fixture', :email, :username, 'x', 'CONSULTANT',
                                NOW(), '0000000000', '2000-01-01')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("email", uuid + "@example.com")
                .setParameter("username", uuid)
                .executeUpdate();
    }

    private void insertPosition(String uuid, String title, String track, String teamUuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, team_uuid, stage_set,
                             demand_rag, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :title, :track, :team,
                                '["SCREENING","INTERVIEW_1","INTERVIEW_2","OFFER","HIRED"]',
                                'GREEN', 'OPEN', NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("title", title)
                .setParameter("track", track)
                .setParameter("team", teamUuid)
                .executeUpdate();
    }

    private void insertApplication(String uuid, String candidateUuid, String positionUuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_applications
                            (uuid, candidate_uuid, position_uuid, stage,
                             stage_entered_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :candidate, :position, 'SCREENING',
                                NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("candidate", candidateUuid)
                .setParameter("position", positionUuid)
                .executeUpdate();
    }
}
