package dk.trustworks.intranet.recruitmentservice.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class ApplicationResourceTest {

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:write"})
    void createTransitionAndWithdraw() {
        String roleUuid = createSourcingRole();
        String candidateUuid = createCandidate();

        String appUuid = given().contentType("application/json")
                .body("""
                    {"candidateUuid":"%s","roleUuid":"%s","applicationType":"JOB_AD"}
                    """.formatted(candidateUuid, roleUuid))
                .when().post("/api/recruitment/applications")
                .then().statusCode(201)
                .body("stage", equalTo("SOURCED"))
                .extract().path("uuid");

        given().contentType("application/json")
                .body("{\"toStage\":\"CONTACTED\"}")
                .when().post("/api/recruitment/applications/" + appUuid + "/transitions")
                .then().statusCode(200).body("stage", equalTo("CONTACTED"));

        given().contentType("application/json")
                .body("{\"toStage\":\"FINAL_INTERVIEW\"}")
                .when().post("/api/recruitment/applications/" + appUuid + "/transitions")
                .then().statusCode(409)
                .body("allowedTransitions", notNullValue());

        given().contentType("application/json")
                .body("{\"reason\":\"Withdrawing\"}")
                .when().post("/api/recruitment/applications/" + appUuid + "/withdraw")
                .then().statusCode(200).body("stage", equalTo("WITHDRAWN"));
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:write"})
    void duplicateActiveApplicationReturns409() {
        String roleUuid = createSourcingRole();
        String candidateUuid = createCandidate();
        String body = """
                {"candidateUuid":"%s","roleUuid":"%s","applicationType":"JOB_AD"}
                """.formatted(candidateUuid, roleUuid);

        given().contentType("application/json").body(body)
                .when().post("/api/recruitment/applications").then().statusCode(201);

        given().contentType("application/json").body(body)
                .when().post("/api/recruitment/applications").then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:write"})
    void writeScopeAloneCannotCreateOffer() {
        String roleUuid = createSourcingRole();
        String candidateUuid = createCandidate();
        String appUuid = given().contentType("application/json")
                .body("""
                    {"candidateUuid":"%s","roleUuid":"%s","applicationType":"JOB_AD"}
                    """.formatted(candidateUuid, roleUuid))
                .when().post("/api/recruitment/applications")
                .then().statusCode(201)
                .extract().path("uuid");

        marchToFinalInterview(appUuid);

        given().contentType("application/json")
                .body("{\"toStage\":\"OFFER\"}")
                .when().post("/api/recruitment/applications/" + appUuid + "/transitions")
                .then().statusCode(403);
    }

    private void marchToFinalInterview(String appUuid) {
        for (String stage : List.of("CONTACTED", "SCREENING", "FIRST_INTERVIEW",
                "CASE_OR_TECH_INTERVIEW", "FINAL_INTERVIEW")) {
            given().contentType("application/json")
                    .body("{\"toStage\":\"" + stage + "\"}")
                    .when().post("/api/recruitment/applications/" + appUuid + "/transitions")
                    .then().statusCode(200);
        }
    }

    private String createSourcingRole() {
        String uuid = given().contentType("application/json")
                .body("""
                    {"title":"X","hiringCategory":"PRACTICE_CONSULTANT","practice":"DEV",
                     "teamUuid":"00000000-0000-0000-0000-000000000010",
                     "hiringSource":"CAPACITY_GAP"}""")
                .when().post("/api/recruitment/roles").then().statusCode(201).extract().path("uuid");
        given().contentType("application/json")
                .body("""
                    {"userUuid":"00000000-0000-0000-0000-000000000011",
                     "responsibilityKind":"RECRUITMENT_OWNER"}""")
                .when().post("/api/recruitment/roles/" + uuid + "/assignments").then().statusCode(201);
        return uuid;
    }

    private String createCandidate() {
        return given().contentType("application/json")
                .body("{\"firstName\":\"Pat\",\"lastName\":\"Doe\",\"email\":\"pat@example.com\",\"desiredPractice\":\"DEV\"}")
                .when().post("/api/recruitment/candidates").then().statusCode(201).extract().path("uuid");
    }
}
