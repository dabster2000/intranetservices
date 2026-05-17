package dk.trustworks.intranet.expenseservice.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class RulePreviewImpactResourceTest {

    @Test
    @TestSecurity(user = "test-admin", roles = {"admin:write"})
    void preview_returns_flipped_count() {
        var body = Map.of(
            "ruleId",     "R_MEAL_COST_PER_PERSON",
            "parameter",  "meal_cost_per_person_dkk",
            "oldValue",   125,
            "newValue",   160,
            "windowDays", 7
        );
        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
            .contentType("application/json")
            .body(body)
        .when()
            .post("/admin/rules/preview-impact")
        .then()
            .statusCode(200)
            .body("totalRejected",       greaterThanOrEqualTo(0))
            .body("wouldFlipToApproved", greaterThanOrEqualTo(0))
            .body("wouldRemainRejected", greaterThanOrEqualTo(0));
    }

    @Test
    @TestSecurity(user = "outsider", roles = {"expenses:read"})
    void preview_returns_403_without_admin_write() {
        var body = Map.of(
            "ruleId",     "R_MEAL_COST_PER_PERSON",
            "parameter",  "meal_cost_per_person_dkk",
            "oldValue",   125,
            "newValue",   160,
            "windowDays", 7
        );
        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
            .contentType("application/json")
            .body(body)
        .when()
            .post("/admin/rules/preview-impact")
        .then()
            .statusCode(403);
    }
}
