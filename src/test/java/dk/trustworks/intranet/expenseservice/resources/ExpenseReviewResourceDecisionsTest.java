package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseDecisionLogService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ExpenseReviewResourceDecisionsTest {

    @Inject ExpenseDecisionLogService logs;

    private String seedPendingHRExpense() {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("u");
        e.setAmount(450.0);
        e.setAccount("3585");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now());
        e.setStatus("CREATED");
        e.setReviewState("PENDING_HR");
        e.setAiRuleId("R_MEAL_COST_PER_PERSON");
        e.setAiValidationApproved(false);
        e.setEmployeeJustification("client meeting");
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(e::persist);
        return e.getUuid();
    }

    @Test @TestSecurity(user = "hr", roles = {"expenses:review"})
    void approveAdvancesToValidatedAndLogs() {
        String uuid = seedPendingHRExpense();
        given()
          .header("X-Requested-By", "hr")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"reason\":\"Pre-approved by Lars\"}")
        .when()
          .post("/expenses/" + uuid + "/review/approve")
        .then()
          .statusCode(204);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("VALIDATED", after.getStatus());
        assertNull(after.getReviewState());
        assertEquals("APPROVED", after.getHrDecision());
        assertEquals("hr", after.getHrDecisionBy());
        assertNotNull(after.getHrDecisionAt());
        assertEquals(1, logs.findByExpense(uuid).stream()
            .filter(l -> "HR_APPROVED".equals(l.action)).count());
    }

    @Test @TestSecurity(user = "hr", roles = {"expenses:review"})
    void sendBackTransitionsToHRSentBackWithComment() {
        String uuid = seedPendingHRExpense();
        given()
          .header("X-Requested-By", "hr")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"comment\":\"Need attendee list\"}")
        .when()
          .post("/expenses/" + uuid + "/review/send-back")
        .then()
          .statusCode(204);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("CREATED", after.getStatus());
        assertEquals("HR_SENT_BACK", after.getReviewState());
        assertEquals("Need attendee list", after.getHrComment());
        assertEquals("SENT_BACK", after.getHrDecision());
    }

    @Test @TestSecurity(user = "hr", roles = {"expenses:review"})
    void rejectMarksDeleted() {
        String uuid = seedPendingHRExpense();
        given()
          .header("X-Requested-By", "hr")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"reason\":\"Not a reimbursable expense\"}")
        .when()
          .post("/expenses/" + uuid + "/review/reject")
        .then()
          .statusCode(204);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("DELETED", after.getStatus());
        assertEquals("REJECTED", after.getHrDecision());
        assertEquals("Not a reimbursable expense", after.getHrComment());
    }
}
