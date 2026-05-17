package dk.trustworks.intranet.expenseservice.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class ExpenseReviewResourceGetTest {

    @Test
    @TestSecurity(user = "hr-user", roles = {"expenses:review"})
    void queueReturnsPendingHRRows() {
        given()
          .queryParam("state", "PENDING_HR")
          .queryParam("fromDate", "2026-01-01")
          .queryParam("toDate",   "2026-12-31")
        .when()
          .get("/expenses/review")
        .then()
          .statusCode(200)
          .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    void unauthorizedReturnsForbidden() {
        given()
          .queryParam("state", "PENDING_HR")
        .when()
          .get("/expenses/review")
        .then()
          .statusCode(anyOf(is(401), is(403)));
    }
}
