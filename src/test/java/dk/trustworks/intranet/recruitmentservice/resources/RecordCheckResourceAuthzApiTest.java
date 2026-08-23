package dk.trustworks.intranet.recruitmentservice.resources;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Direct-call regression coverage for the dossier-owned record-check API. */
@QuarkusTest
class RecordCheckResourceAuthzApiTest {

    private static final String RATE_KEY = "recruitment.record-check.sample-rate";

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String positionUuid;
    private String otherPositionUuid;
    private String partnerPositionUuid;
    private String candidateUuid;
    private String otherCandidateUuid;
    private String applicationUuid;
    private String otherApplicationUuid;
    private String partnerApplicationUuid;
    private String assistant;
    private String namedTeamlead;
    private String otherTeamlead;
    private String hr;
    private String admin;
    private String previousRate;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        otherPositionUuid = UUID.randomUUID().toString();
        partnerPositionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        otherCandidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        otherApplicationUuid = UUID.randomUUID().toString();
        partnerApplicationUuid = UUID.randomUUID().toString();
        assistant = UUID.randomUUID().toString();
        namedTeamlead = UUID.randomUUID().toString();
        otherTeamlead = UUID.randomUUID().toString();
        hr = UUID.randomUUID().toString();
        admin = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, assistant, "Rikke", "Assistant");
            P8ProfileFixtures.insertUser(em, namedTeamlead, "Tim", "Owner");
            P8ProfileFixtures.insertUser(em, otherTeamlead, "Otto", "Owner");
            P8ProfileFixtures.insertUser(em, hr, "Helle", "HR");
            P8ProfileFixtures.insertUser(em, admin, "Alma", "Admin");
            P8ProfileFixtures.insertRole(em, assistant, "ASSISTANT_TEAMLEAD");
            P8ProfileFixtures.insertRole(em, namedTeamlead, "TEAMLEAD");
            P8ProfileFixtures.insertRole(em, otherTeamlead, "TEAMLEAD");
            P8ProfileFixtures.insertRole(em, hr, "HR");
            P8ProfileFixtures.insertRole(em, admin, "ADMIN");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            em.createNativeQuery("UPDATE user SET practice_uuid = :practice WHERE uuid = :user")
                    .setParameter("practice", practiceUuid)
                    .setParameter("user", assistant)
                    .executeUpdate();

            P8ProfileFixtures.insertPosition(em, positionUuid, "Consultant",
                    "PRACTICE_TEAM", practiceUuid, null, namedTeamlead);
            P8ProfileFixtures.insertPosition(em, otherPositionUuid, "Other consultant",
                    "PRACTICE_TEAM", practiceUuid, null, otherTeamlead);
            P8ProfileFixtures.insertPosition(em, partnerPositionUuid, "Confidential partner",
                    "PARTNER", practiceUuid, null, namedTeamlead);
            P8ProfileFixtures.insertCandidate(em, candidateUuid, "Ada", "Candidate",
                    "ACTIVE", null, null, hr);
            P8ProfileFixtures.insertCandidate(em, otherCandidateUuid, "Oda", "Candidate",
                    "ACTIVE", null, null, hr);
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid, candidateUuid,
                    positionUuid, "OFFER");
            P8ProfileFixtures.insertOpenApplication(em, otherApplicationUuid, otherCandidateUuid,
                    otherPositionUuid, "OFFER");
            // Mixed-scope candidate: the named owner reads the dossier through
            // the ordinary application but has no circle seat on this hidden
            // partner application, which triggered the record-check draw.
            P8ProfileFixtures.insertOpenApplication(em, partnerApplicationUuid, candidateUuid,
                    partnerPositionUuid, "OFFER");
            insertRecordCheck(candidateUuid, partnerApplicationUuid);
            insertRecordCheck(otherCandidateUuid, otherApplicationUuid);
            previousRate = P8ProfileFixtures.setFlag(em, RATE_KEY, "20");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_record_checks WHERE candidate_uuid IN :c")
                    .setParameter("c", List.of(candidateUuid, otherCandidateUuid))
                    .executeUpdate();
            P8ProfileFixtures.restoreFlag(em, RATE_KEY, previousRate);
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid, otherCandidateUuid),
                    List.of(positionUuid, otherPositionUuid, partnerPositionUuid),
                    List.of(assistant, namedTeamlead, otherTeamlead, hr, admin),
                    practiceUuid);
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void statusUsesCandidateScopedDossierCapability_andUniform404() {
        given().header("X-Requested-By", assistant)
                .when().get("/recruitment/record-checks/{candidateUuid}", candidateUuid)
                .then().statusCode(404);
        given().header("X-Requested-By", namedTeamlead)
                .when().get("/recruitment/record-checks/{candidateUuid}", otherCandidateUuid)
                .then().statusCode(404);
        given().header("X-Requested-By", namedTeamlead)
                .when().get("/recruitment/record-checks/{candidateUuid}", UUID.randomUUID())
                .then().statusCode(404);

        given().header("X-Requested-By", namedTeamlead)
                .when().get("/recruitment/record-checks/{candidateUuid}", candidateUuid)
                .then().statusCode(200)
                .body("selected", equalTo(true))
                .body("viewerCanRecordOutcome", equalTo(false))
                .body("$", not(hasKey("applicationUuid")))
                .body("$", not(hasKey("verifiedBy")))
                .body("$", not(hasKey("createdBy")));
        for (String eligibleViewer : List.of(hr, admin)) {
            given().header("X-Requested-By", eligibleViewer)
                    .when().get("/recruitment/record-checks/{candidateUuid}", candidateUuid)
                    .then().statusCode(200)
                    .body("selected", equalTo(true))
                    .body("viewerCanRecordOutcome", equalTo(true));
        }
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void outcomesAndSettingsAreHrAdminOnly_evenWithMachineWriteScope() {
        for (String deniedViewer : List.of(assistant, namedTeamlead)) {
            given().header("X-Requested-By", deniedViewer)
                    .contentType(ContentType.JSON)
                    .body(Map.of("outcome", "VERIFIED_CLEAN"))
                    .when().post("/recruitment/record-checks/{candidateUuid}/outcome", candidateUuid)
                    .then().statusCode(403);
            given().header("X-Requested-By", deniedViewer)
                    .when().get("/recruitment/record-checks/settings")
                    .then().statusCode(403);
            given().header("X-Requested-By", deniedViewer)
                    .contentType(ContentType.JSON)
                    .body(Map.of("sampleRatePercent", 37))
                    .when().put("/recruitment/record-checks/settings")
                    .then().statusCode(403);
        }

        given().header("X-Requested-By", hr)
                .contentType(ContentType.JSON)
                .body(Map.of("outcome", "VERIFIED_CLEAN"))
                .when().post("/recruitment/record-checks/{candidateUuid}/outcome", candidateUuid)
                .then().statusCode(200)
                .body("outcome", equalTo("VERIFIED_CLEAN"))
                .body("viewerCanRecordOutcome", equalTo(true))
                .body("$", not(hasKey("verifiedBy")));

        Object[] outcomeEvent = QuarkusTransaction.requiringNew().call(() ->
                (Object[]) em.createNativeQuery("""
                                SELECT position_uuid, visibility
                                FROM recruitment_events
                                WHERE candidate_uuid = :candidate
                                  AND event_type = 'RECORD_CHECK_OUTCOME_RECORDED'
                                ORDER BY seq DESC
                                LIMIT 1
                                """)
                        .setParameter("candidate", candidateUuid)
                        .getSingleResult());
        assertEquals(partnerPositionUuid, outcomeEvent[0]);
        assertEquals("CIRCLE", outcomeEvent[1]);
        given().header("X-Requested-By", admin)
                .contentType(ContentType.JSON)
                .body(Map.of("outcome", "NOT_CLEAN"))
                .when().post("/recruitment/record-checks/{candidateUuid}/outcome", candidateUuid)
                .then().statusCode(200)
                .body("outcome", equalTo("NOT_CLEAN"))
                .body("viewerCanRecordOutcome", equalTo(true));

        given().header("X-Requested-By", hr)
                .when().get("/recruitment/record-checks/settings")
                .then().statusCode(200)
                .body("sampleRatePercent", equalTo(20));
        given().header("X-Requested-By", admin)
                .contentType(ContentType.JSON)
                .body(Map.of("sampleRatePercent", 37))
                .when().put("/recruitment/record-checks/settings")
                .then().statusCode(200)
                .body("sampleRatePercent", equalTo(37));
    }

    private void insertRecordCheck(String candidate, String application) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_record_checks
                            (uuid, candidate_uuid, application_uuid, drawn_at, rate_applied,
                             selected, outcome, created_at, updated_at, created_by)
                        VALUES (:uuid, :candidate, :application, UTC_TIMESTAMP(3), 20,
                                1, 'PENDING', NOW(), NOW(), :actor)
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("candidate", candidate)
                .setParameter("application", application)
                .setParameter("actor", hr)
                .executeUpdate();
    }
}
