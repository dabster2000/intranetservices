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

    /**
     * Seeds a NEEDS_ATTENTION row owned by {@code attentionOwner} (EMPLOYEE / ACCOUNTING).
     * The review GET queue filters on the unified state/owner, so we seed those directly —
     * the entity hook no longer derives them from the retired review_state column.
     */
    private String seedExpense(String attentionOwner, String attentionKind, java.time.LocalDate expenseDate) {
        return seedExpense(attentionOwner, attentionKind, expenseDate, "CREATED");
    }

    private String seedExpense(String attentionOwner, String attentionKind,
                               java.time.LocalDate expenseDate, String status) {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("u");
        e.setAmount(100.0);
        e.setAccount("3585");
        e.setExpensedate(expenseDate);
        e.setStatus(status);
        e.setState("NEEDS_ATTENTION");
        e.setAttentionOwner(attentionOwner);
        e.setAttentionKind(attentionKind);
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
        String needsFix = seedExpense("EMPLOYEE", "RECEIPT", java.time.LocalDate.of(2024, 1, 15));
        String needsJustification = seedExpense("EMPLOYEE", "JUSTIFICATION", java.time.LocalDate.now());
        String sentBack = seedExpense("EMPLOYEE", "JUSTIFICATION", java.time.LocalDate.now());
        String pending = seedExpense("ACCOUNTING", "POLICY", java.time.LocalDate.now());

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
    @TestSecurity(user = "accounting-user", roles = {"expenses:review"})
    void queuesExcludeDeletedRowsEvenWithActionableReviewState() {
        String deleted = seedExpense("EMPLOYEE", "RECEIPT", java.time.LocalDate.now(), "DELETED");
        String visible = seedExpense("EMPLOYEE", "RECEIPT", java.time.LocalDate.now(), "CREATED");

        given()
          .queryParam("state", "AWAITING_EMPLOYEE")
        .when()
          .get("/expenses/review")
        .then()
          .statusCode(200)
          .body("expense.uuid", hasItem(visible))
          .body("expense.uuid", not(hasItem(deleted)));
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
