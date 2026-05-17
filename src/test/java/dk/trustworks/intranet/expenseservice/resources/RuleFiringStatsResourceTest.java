package dk.trustworks.intranet.expenseservice.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class RuleFiringStatsResourceTest {

    @Test
    @TestSecurity(user = "test-admin", roles = {"admin:write"})
    void firing_stats_returns_per_rule_counts() {
        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
            .queryParam("days", 30)
        .when()
            .get("/admin/rules/firing-stats")
        .then()
            .statusCode(200)
            .body("stats", notNullValue());
    }
}
