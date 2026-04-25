package dk.trustworks.intranet.recruitmentservice.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class RecruitmentStatusResourceTest {

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:admin"})
    void putThenGetRoundTrip() {
        given().contentType("application/json")
                .body("{\"status\":\"ACTIVE\",\"reason\":\"Q1 ramp-up\"}")
                .when().put("/api/recruitment/status/PRACTICE/DEV")
                .then().statusCode(200);

        given().when().get("/api/recruitment/status")
                .then().statusCode(200)
                .body("find { it.scopeKind == 'PRACTICE' && it.scopeId == 'DEV' }.status",
                        equalTo("ACTIVE"));
    }

    @Test
    @TestSecurity(user = "stranger", roles = {"users:read"})
    void unauthorizedScopeReturns403() {
        given().when().get("/api/recruitment/status").then().statusCode(403);
    }
}
