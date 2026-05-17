package dk.trustworks.intranet.expenseservice.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ExpenseDecisionsResourceTest {

    @Test
    @TestSecurity(user = "test-admin", roles = {"admin:write"})
    void list_returns_decisions_and_summary() {
        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
            .queryParam("from", "2026-05-10")
            .queryParam("to",   "2026-05-17")
            .queryParam("limit", 50)
        .when()
            .get("/admin/expense-decisions")
        .then()
            .statusCode(200)
            .body("decisions",              notNullValue())
            .body("totalCount",             greaterThanOrEqualTo(0))
            .body("summary.autoApproved",   notNullValue())
            .body("summary.awaitingEmployee", notNullValue())
            .body("summary.sentToHr",       notNullValue());
    }

    @Test
    @TestSecurity(user = "outsider", roles = {"expenses:read"})
    void list_returns_403_without_admin_write() {
        given()
            .header("X-Requested-By", "00000000-0000-0000-0000-000000000001")
        .when()
            .get("/admin/expense-decisions")
        .then()
            .statusCode(403);
    }
}
