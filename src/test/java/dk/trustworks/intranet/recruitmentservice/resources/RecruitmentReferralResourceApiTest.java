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
 * P6 DoD (API, end-to-end through the resource):
 * <ul>
 *   <li>scope matrix: an interviewer-scoped caller gets 403 on every
 *       referral surface, and no response ever serializes the referrer's
 *       identity to them (plan §P6 DoD);</li>
 *   <li>recruiter tier: a TEAMLEAD-role user gets 403 on
 *       pending/triage/triage-queue; an HR-role user gets 200 (roles
 *       resolved from real {@code roles} fixture rows via
 *       X-Requested-By);</li>
 *   <li>validation is explicit resource/service checks — the module has
 *       no active bean-validation extension (findings §P4): missing
 *       candidateName/whyText/relation → 400; dismiss without a reason →
 *       400; create without firstName/lastName → 400;</li>
 *   <li>"My referrals" rows are deliberately minimal: no candidateUuid
 *       key, no position facts — the referrer never gets a handle to the
 *       candidate record;</li>
 *   <li>a second triage of the same referral → 409 (one-shot).</li>
 * </ul>
 */
@QuarkusTest
class RecruitmentReferralResourceApiTest {

    private static final String FLAG = "recruitment.pipeline.enabled";

    @Inject
    EntityManager em;

    private String referrerUser;
    private String hrUser;
    private String teamleadUser;
    private String submittedReferralUuid;
    private String triagedReferralUuid;
    private String candidateUuid;
    private String previousFlagValue;

    @BeforeEach
    void seed() {
        referrerUser = UUID.randomUUID().toString();
        hrUser = UUID.randomUUID().toString();
        teamleadUser = UUID.randomUUID().toString();
        submittedReferralUuid = UUID.randomUUID().toString();
        triagedReferralUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            insertUser(referrerUser);
            insertUser(hrUser);
            insertUser(teamleadUser);
            insertRole(hrUser, "HR");
            insertRole(teamleadUser, "TEAMLEAD");

            // A triaged referral needs its candidate (real FK).
            em.createNativeQuery("""
                            INSERT INTO recruitment_candidates
                                (uuid, first_name, last_name, status, source, referred_by_user_uuid,
                                 created_by_useruuid, created_at, updated_at)
                            VALUES (:uuid, 'Api', 'Fixture', 'ACTIVE', 'REFERRAL', :referrer,
                                    :actor, NOW(), NOW())
                            """)
                    .setParameter("uuid", candidateUuid)
                    .setParameter("referrer", referrerUser)
                    .setParameter("actor", hrUser).executeUpdate();

            insertReferral(submittedReferralUuid, referrerUser, "SUBMITTED", null);
            insertReferral(triagedReferralUuid, referrerUser, "TRIAGED", candidateUuid);

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
            // Referral events (submitted through the API in these tests)
            // are only traceable via payload.referral_uuid / referrer actor.
            em.createNativeQuery("DELETE FROM recruitment_events WHERE actor_uuid IN :u")
                    .setParameter("u", List.of(referrerUser, hrUser, teamleadUser)).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_referrals WHERE referrer_uuid IN :u")
                    .setParameter("u", List.of(referrerUser, hrUser, teamleadUser)).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            List<String> users = List.of(referrerUser, hrUser, teamleadUser);
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

    // ---- Submit ---------------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:refer"})
    void submit_validRequest_is201WithUuid() {
        given()
                .contentType("application/json")
                .header("X-Requested-By", referrerUser)
                .body("""
                        {"candidateName": "Jane Larsen",
                         "linkedinUrl": "https://www.linkedin.com/in/janelarsen",
                         "referrerRelation": "FORMER_COLLEAGUE",
                         "whyText": "Ran the platform migration at her last job."}
                        """)
                .when()
                .post("/recruitment/referrals")
                .then()
                .statusCode(201)
                .header("Location", Matchers.containsString("/recruitment/referrals/"))
                .body("uuid", Matchers.notNullValue());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:refer"})
    void submit_javascriptSchemeLinkedin_is400() {
        // Stored-XSS guard: a javascript: URI that merely mentions
        // linkedin.com must never reach the recruiter grid's href.
        given()
                .contentType("application/json")
                .header("X-Requested-By", referrerUser)
                .body("""
                        {"candidateName": "Jane Larsen",
                         "linkedinUrl": "javascript:alert(1)//linkedin.com",
                         "referrerRelation": "COLLEAGUE",
                         "whyText": "why"}
                        """)
                .when()
                .post("/recruitment/referrals")
                .then()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:refer"})
    void submit_schemelessLinkedin_is201() {
        // "www.linkedin.com/in/jane" pastes must keep working — the
        // service normalizes them to https (asserted in the service test).
        given()
                .contentType("application/json")
                .header("X-Requested-By", referrerUser)
                .body("""
                        {"candidateName": "Jane Larsen",
                         "linkedinUrl": "www.linkedin.com/in/jane",
                         "referrerRelation": "COLLEAGUE",
                         "whyText": "why"}
                        """)
                .when()
                .post("/recruitment/referrals")
                .then()
                .statusCode(201)
                .body("uuid", Matchers.notNullValue());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:refer"})
    void submit_missingCandidateName_is400() {
        given()
                .contentType("application/json")
                .header("X-Requested-By", referrerUser)
                .body("{\"referrerRelation\": \"COLLEAGUE\", \"whyText\": \"why\"}")
                .when()
                .post("/recruitment/referrals")
                .then()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:refer"})
    void submit_missingWhyText_is400() {
        given()
                .contentType("application/json")
                .header("X-Requested-By", referrerUser)
                .body("{\"candidateName\": \"Jane Larsen\", \"referrerRelation\": \"COLLEAGUE\"}")
                .when()
                .post("/recruitment/referrals")
                .then()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:refer"})
    void submit_missingOrUnknownRelation_is400() {
        given()
                .contentType("application/json")
                .header("X-Requested-By", referrerUser)
                .body("{\"candidateName\": \"Jane Larsen\", \"whyText\": \"why\"}")
                .when()
                .post("/recruitment/referrals")
                .then()
                .statusCode(400);
        given()
                .contentType("application/json")
                .header("X-Requested-By", referrerUser)
                .body("{\"candidateName\": \"Jane\", \"referrerRelation\": \"BUDDY\", \"whyText\": \"why\"}")
                .when()
                .post("/recruitment/referrals")
                .then()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:refer"})
    void submit_withoutRequestedByHeader_is400() {
        given()
                .contentType("application/json")
                .body("{\"candidateName\": \"Jane\", \"referrerRelation\": \"COLLEAGUE\", \"whyText\": \"why\"}")
                .when()
                .post("/recruitment/referrals")
                .then()
                .statusCode(400);
    }

    // ---- Mine -----------------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:refer"})
    void mine_returnsOwnRows_withDerivedStatus_andNoCandidateHandle() {
        given()
                .header("X-Requested-By", referrerUser)
                .when()
                .get("/recruitment/referrals/mine")
                .then()
                .statusCode(200)
                .body("totalCount", Matchers.equalTo(2))
                .body("referrals.uuid", Matchers.containsInAnyOrder(
                        submittedReferralUuid, triagedReferralUuid))
                .body("referrals.find { it.uuid == '%s' }.derivedStatus"
                        .formatted(submittedReferralUuid), Matchers.equalTo("AWAITING_TRIAGE"))
                .body("referrals.find { it.uuid == '%s' }.derivedStatus"
                        .formatted(triagedReferralUuid), Matchers.equalTo("UNDER_REVIEW"))
                // The referrer never gets a handle to the candidate record —
                // no candidate uuid, no position facts, no stage codes.
                .body("referrals[0]", Matchers.not(Matchers.hasKey("candidateUuid")))
                .body("referrals[0]", Matchers.not(Matchers.hasKey("positionUuid")))
                .body("referrals[0]", Matchers.not(Matchers.hasKey("stage")))
                .body("referrals[0]", Matchers.hasKey("candidateName"))
                .body("referrals[0]", Matchers.hasKey("derivedStatus"));

        // Another employee sees none of these rows.
        given()
                .header("X-Requested-By", teamleadUser)
                .when()
                .get("/recruitment/referrals/mine")
                .then()
                .statusCode(200)
                .body("totalCount", Matchers.equalTo(0));
    }

    // ---- Scope matrix: interviewer-scoped caller ---------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:interview"})
    void interviewScope_isDeniedEverywhere_andNeverSeesTheReferrerIdentity() {
        String mine = given().header("X-Requested-By", hrUser)
                .get("/recruitment/referrals/mine")
                .then().statusCode(403).extract().asString();
        String pending = given().header("X-Requested-By", hrUser)
                .get("/recruitment/referrals/pending")
                .then().statusCode(403).extract().asString();
        String triage = given().contentType("application/json")
                .header("X-Requested-By", hrUser)
                .body("{\"action\": \"DISMISS\", \"dismissReason\": \"OTHER\"}")
                .post("/recruitment/referrals/{uuid}/triage", submittedReferralUuid)
                .then().statusCode(403).extract().asString();
        String queue = given().header("X-Requested-By", hrUser)
                .get("/recruitment/candidates/triage-queue")
                .then().statusCode(403).extract().asString();

        // The plan §P6 DoD: the referrer's identity is absent from every
        // interviewer-scoped response path.
        for (String body : List.of(mine, pending, triage, queue)) {
            org.junit.jupiter.api.Assertions.assertFalse(body.contains(referrerUser),
                    "an interviewer-scoped response must never carry the referrer identity");
        }
    }

    // ---- Recruiter tier ---------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client",
            roles = {"recruitment:refer", "recruitment:read", "recruitment:write"})
    void teamleadRole_is403OnRecruiterSurfaces() {
        given().header("X-Requested-By", teamleadUser)
                .get("/recruitment/referrals/pending")
                .then().statusCode(403);
        given().contentType("application/json")
                .header("X-Requested-By", teamleadUser)
                .body("{\"action\": \"DISMISS\", \"dismissReason\": \"OTHER\"}")
                .post("/recruitment/referrals/{uuid}/triage", submittedReferralUuid)
                .then().statusCode(403);
        given().header("X-Requested-By", teamleadUser)
                .get("/recruitment/candidates/triage-queue")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "bff-client",
            roles = {"recruitment:refer", "recruitment:read", "recruitment:write"})
    void hrRole_readsThePendingQueue_withFullReferralFacts() {
        given()
                .header("X-Requested-By", hrUser)
                .when()
                .get("/recruitment/referrals/pending")
                .then()
                .statusCode(200)
                .body("referrals.uuid", Matchers.hasItem(submittedReferralUuid))
                .body("referrals.uuid", Matchers.not(Matchers.hasItem(triagedReferralUuid)))
                .body("referrals.find { it.uuid == '%s' }.referrerUuid"
                        .formatted(submittedReferralUuid), Matchers.equalTo(referrerUser))
                .body("referrals.find { it.uuid == '%s' }.whyText"
                        .formatted(submittedReferralUuid), Matchers.notNullValue());
    }

    @Test
    @TestSecurity(user = "bff-client",
            roles = {"recruitment:refer", "recruitment:read", "recruitment:write"})
    void hrRole_readsTheTriageQueue() {
        given()
                .header("X-Requested-By", hrUser)
                .when()
                .get("/recruitment/candidates/triage-queue")
                .then()
                .statusCode(200)
                .body("$", Matchers.hasKey("candidates"))
                .body("$", Matchers.hasKey("totalCount"));
    }

    // ---- Triage validation + one-shot --------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client",
            roles = {"recruitment:refer", "recruitment:read", "recruitment:write"})
    void triage_validation400s_thenDismiss200_thenSecondTriage409() {
        // Dismiss without a reason → 400, nothing mutated.
        given().contentType("application/json")
                .header("X-Requested-By", hrUser)
                .body("{\"action\": \"DISMISS\"}")
                .post("/recruitment/referrals/{uuid}/triage", submittedReferralUuid)
                .then().statusCode(400);

        // Create without firstName/lastName → 400, nothing mutated.
        given().contentType("application/json")
                .header("X-Requested-By", hrUser)
                .body("{\"action\": \"CREATE_CANDIDATE\"}")
                .post("/recruitment/referrals/{uuid}/triage", submittedReferralUuid)
                .then().statusCode(400);

        // Unknown action → 400.
        given().contentType("application/json")
                .header("X-Requested-By", hrUser)
                .body("{\"action\": \"ESCALATE\"}")
                .post("/recruitment/referrals/{uuid}/triage", submittedReferralUuid)
                .then().statusCode(400);

        // A valid dismiss succeeds…
        given().contentType("application/json")
                .header("X-Requested-By", hrUser)
                .body("{\"action\": \"DISMISS\", \"dismissReason\": \"NOT_RELEVANT\"}")
                .post("/recruitment/referrals/{uuid}/triage", submittedReferralUuid)
                .then()
                .statusCode(200)
                .body("referralUuid", Matchers.equalTo(submittedReferralUuid))
                .body("status", Matchers.equalTo("CLOSED"))
                .body("candidateUuid", Matchers.nullValue());

        // …and the referral is consumed: any second triage conflicts.
        given().contentType("application/json")
                .header("X-Requested-By", hrUser)
                .body("{\"action\": \"DISMISS\", \"dismissReason\": \"OTHER\"}")
                .post("/recruitment/referrals/{uuid}/triage", submittedReferralUuid)
                .then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "bff-client",
            roles = {"recruitment:refer", "recruitment:read", "recruitment:write"})
    void triage_unknownReferral_is404() {
        given().contentType("application/json")
                .header("X-Requested-By", hrUser)
                .body("{\"action\": \"DISMISS\", \"dismissReason\": \"OTHER\"}")
                .post("/recruitment/referrals/{uuid}/triage", UUID.randomUUID().toString())
                .then().statusCode(404);
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

    private void insertRole(String userUuid, String role) {
        em.createNativeQuery("INSERT INTO roles (uuid, role, useruuid) VALUES (:u, :role, :user)")
                .setParameter("u", UUID.randomUUID().toString())
                .setParameter("role", role)
                .setParameter("user", userUuid)
                .executeUpdate();
    }

    private void insertReferral(String uuid, String referrerUuid, String status,
                                String candidateUuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_referrals
                            (uuid, referrer_uuid, referrer_relation, candidate_name, why_text,
                             candidate_uuid, status, submitted_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :referrer, 'COLLEAGUE', 'Api Fixture Candidate',
                                'Strong PM, great with stakeholders.', :candidate, :status,
                                NOW(3), NOW(), NOW(), :referrer)
                        """)
                .setParameter("uuid", uuid)
                .setParameter("referrer", referrerUuid)
                .setParameter("candidate", candidateUuid)
                .setParameter("status", status)
                .executeUpdate();
    }
}
