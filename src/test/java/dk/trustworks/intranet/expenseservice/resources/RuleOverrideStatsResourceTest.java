package dk.trustworks.intranet.expenseservice.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class RuleOverrideStatsResourceTest {

    @Test
    @TestSecurity(user = "test-admin", roles = {"admin:write"})
    void override_stats_returns_per_rule_entries() {
        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
            .queryParam("days", 180)
        .when()
            .get("/admin/rules/override-stats")
        .then()
            .statusCode(200)
            .body("windowDays", equalTo(180))
            .body("stats", notNullValue());
    }
}
