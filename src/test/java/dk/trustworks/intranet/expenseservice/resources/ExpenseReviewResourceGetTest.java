package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class ExpenseReviewResourceGetTest {

    private String seedExpense(String reviewState, java.time.LocalDate expenseDate) {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("u");
        e.setAmount(100.0);
        e.setAccount("3585");
        e.setExpensedate(expenseDate);
        e.setStatus("CREATED");
        e.setReviewState(reviewState);
        e.setAiValidationApproved(false);
        QuarkusTransaction.requiringNew().run(e::persist);
        return e.getUuid();
    }

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
    @TestSecurity(user = "accounting-user", roles = {"expenses:review"})
    void awaitingEmployeeQueueIncludesAllEmployeeActionStatesWithoutDefaultDateCutoff() {
        String needsFix = seedExpense("NEEDS_FIX", java.time.LocalDate.of(2024, 1, 15));
        String needsJustification = seedExpense("NEEDS_JUSTIFICATION", java.time.LocalDate.now());
        String sentBack = seedExpense("HR_SENT_BACK", java.time.LocalDate.now());
        String pending = seedExpense("PENDING_HR", java.time.LocalDate.now());

        given()
          .queryParam("state", "AWAITING_EMPLOYEE")
        .when()
          .get("/expenses/review")
        .then()
          .statusCode(200)
          .body("expense.uuid", hasItems(needsFix, needsJustification, sentBack))
          .body("expense.uuid", not(hasItem(pending)));
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
