package dk.trustworks.intranet.recruitmentservice.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class OpenRoleResourceTest {

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:write"})
    void createListAndTransitionWalksTheGoldenPath() {
        String body = """
            {
              "title": "Senior DEV consultant",
              "hiringCategory": "PRACTICE_CONSULTANT",
              "practice": "DEV",
              "teamUuid": "00000000-0000-0000-0000-000000000001",
              "hiringSource": "CAPACITY_GAP",
              "hiringReason": "Capacity gap"
            }""";
        String uuid = given().contentType("application/json").body(body)
                .when().post("/api/recruitment/roles")
                .then().statusCode(201)
                .body("uuid", notNullValue())
                .body("status", equalTo("DRAFT"))
                .extract().path("uuid");

        given().contentType("application/json")
                .body("{\"userUuid\":\"00000000-0000-0000-0000-000000000002\",\"responsibilityKind\":\"RECRUITMENT_OWNER\"}")
                .when().post("/api/recruitment/roles/" + uuid + "/assignments")
                .then().statusCode(201);

        given().when().get("/api/recruitment/roles/" + uuid)
                .then().statusCode(200)
                .body("status", equalTo("SOURCING"));

        given().contentType("application/json")
                .body("{\"toStatus\":\"PAUSED\"}")
                .when().post("/api/recruitment/roles/" + uuid + "/transitions")
                .then().statusCode(400);

        given().contentType("application/json")
                .body("{\"toStatus\":\"PAUSED\",\"reason\":\"On hold\"}")
                .when().post("/api/recruitment/roles/" + uuid + "/transitions")
                .then().statusCode(200)
                .body("status", equalTo("PAUSED"));

        given().contentType("application/json")
                .body("{\"toStatus\":\"FILLED\",\"reason\":\"x\"}")
                .when().post("/api/recruitment/roles/" + uuid + "/transitions")
                .then().statusCode(409)
                .body("allowedTransitions", notNullValue())
                .body("allowedTransitions.size()", equalTo(2));
    }

    @Test
    @TestSecurity(user = "stranger", roles = {"users:read"})
    void readWithoutScopeReturns403() {
        given().when().get("/api/recruitment/roles").then().statusCode(403);
    }
}
