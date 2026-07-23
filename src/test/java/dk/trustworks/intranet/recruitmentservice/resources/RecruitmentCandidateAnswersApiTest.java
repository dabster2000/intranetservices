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

import static dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures.PIPELINE_FLAG;
import static io.restassured.RestAssured.given;

/**
 * P8 DoD (answers): both V437 ownership legs — application-scoped
 * (position forms) and candidate-scoped (unsolicited applicants) — with
 * Danish labels from {@code PublicApplyQuestions}, unknown-key fallback to
 * the key itself, and the two authorization rules: application answers are
 * exactly as visible as the application (partner-track → 404 outside the
 * circle), candidate answers require profile access.
 */
@QuarkusTest
class RecruitmentCandidateAnswersApiTest {

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String hrUser;
    private String plainUser;

    private String normalPosition;
    private String partnerPosition;
    private String appCandidate;
    private String unsolicitedCandidate;
    private String partnerCandidate;
    private String normalApplication;
    private String partnerApplication;

    private String previousFlag;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        hrUser = UUID.randomUUID().toString();
        plainUser = UUID.randomUUID().toString();
        normalPosition = UUID.randomUUID().toString();
        partnerPosition = UUID.randomUUID().toString();
        appCandidate = UUID.randomUUID().toString();
        unsolicitedCandidate = UUID.randomUUID().toString();
        partnerCandidate = UUID.randomUUID().toString();
        normalApplication = UUID.randomUUID().toString();
        partnerApplication = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, hrUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, plainUser, "Palle", "Plain");
            P8ProfileFixtures.insertRole(em, hrUser, "HR");
            P8ProfileFixtures.insertPractice(em, practiceUuid);

            P8ProfileFixtures.insertPosition(em, normalPosition, "Consultant",
                    "PRACTICE_TEAM", practiceUuid, null, null);
            P8ProfileFixtures.insertPosition(em, partnerPosition, "Partner hire",
                    "PARTNER", null, null, null);

            P8ProfileFixtures.insertCandidate(em, appCandidate,
                    "PII_SENTINEL Anna", "PII_SENTINEL Ager", "ACTIVE", null, null, hrUser);
            P8ProfileFixtures.insertCandidate(em, unsolicitedCandidate,
                    "PII_SENTINEL Uffe", "PII_SENTINEL Uopfordret", "ACTIVE", null, null,
                    "public-form");
            P8ProfileFixtures.insertCandidate(em, partnerCandidate,
                    "PII_SENTINEL Gro", "PII_SENTINEL Gram", "ACTIVE", null, null, hrUser);

            P8ProfileFixtures.insertOpenApplication(em, normalApplication, appCandidate,
                    normalPosition, "SCREENING");
            P8ProfileFixtures.insertOpenApplication(em, partnerApplication, partnerCandidate,
                    partnerPosition, "SCREENING");

            // Position-form leg: a known key + an unknown legacy key.
            P8ProfileFixtures.insertAnswer(em, normalApplication, null,
                    "WHY_TRUSTWORKS", "PII_SENTINEL fordi kulturen passer mig");
            P8ProfileFixtures.insertAnswer(em, normalApplication, null,
                    "LEGACY_KEY", "PII_SENTINEL et gammelt svar");
            P8ProfileFixtures.insertAnswer(em, partnerApplication, null,
                    "WHY_TRUSTWORKS", "PII_SENTINEL fortroligt partnersvar");
            // Candidate-scoped leg (unsolicited, V437 XOR).
            P8ProfileFixtures.insertAnswer(em, null, unsolicitedCandidate,
                    "BEST_TASKS", "PII_SENTINEL workshops og design");
            P8ProfileFixtures.insertAnswer(em, null, unsolicitedCandidate,
                    "OLD_QUESTION", "PII_SENTINEL fallback-svar");

            previousFlag = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(appCandidate, unsolicitedCandidate, partnerCandidate),
                    List.of(normalPosition, partnerPosition),
                    List.of(hrUser, plainUser),
                    practiceUuid);
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousFlag);
        });
    }

    // ---- Application-scoped leg ---------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void applicationAnswers_labelledInDisplayOrder_unknownKeyFallsBackToKey() {
        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/applications/{uuid}/answers", normalApplication)
                .then()
                .statusCode(200)
                .body("answers", Matchers.hasSize(2))
                .body("answers[0].questionKey", Matchers.equalTo("WHY_TRUSTWORKS"))
                .body("answers[0].label", Matchers.equalTo("Hvorfor Trustworks?"))
                .body("answers[0].answer", Matchers.containsString("kulturen"))
                // Unknown keys sort after the known set and label as the key.
                .body("answers[1].questionKey", Matchers.equalTo("LEGACY_KEY"))
                .body("answers[1].label", Matchers.equalTo("LEGACY_KEY"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void partnerApplicationAnswers_answer404OutsideTheCircle() {
        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/applications/{uuid}/answers", partnerApplication)
                .then().statusCode(404);
    }

    // ---- Candidate-scoped leg (unsolicited) ----------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void candidateScopedAnswers_serveTheUnsolicitedLeg_withLabelsAndFallback() {
        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/answers", unsolicitedCandidate)
                .then()
                .statusCode(200)
                .body("answers", Matchers.hasSize(2))
                .body("answers[0].questionKey", Matchers.equalTo("BEST_TASKS"))
                .body("answers[0].label",
                        Matchers.equalTo("Hvilke opgaver trives du bedst med?"))
                .body("answers[1].questionKey", Matchers.equalTo("OLD_QUESTION"))
                .body("answers[1].label", Matchers.equalTo("OLD_QUESTION"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void candidateScopedAnswers_require_profileAccess() {
        given().header("X-Requested-By", plainUser)
                .when().get("/recruitment/candidates/{uuid}/answers", unsolicitedCandidate)
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void candidateWithApplicationScopedAnswersOnly_answersEmptyCandidateLeg() {
        // The two legs never mix: appCandidate's answers are application-
        // scoped, so the candidate-scoped endpoint returns an empty list.
        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/answers", appCandidate)
                .then()
                .statusCode(200)
                .body("answers", Matchers.hasSize(0));
    }
}
