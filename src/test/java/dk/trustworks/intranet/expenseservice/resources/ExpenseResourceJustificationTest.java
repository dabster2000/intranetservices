package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ExpenseResourceJustificationTest {

    String seedNeedsJustification(String userUuid) {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid(userUuid);
        e.setAmount(200.0);
        e.setAccount("3585");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now());
        e.setStatus("CREATED");
        e.setReviewState("NEEDS_JUSTIFICATION");
        e.setAiRuleId("R_MEAL_COST_PER_PERSON");
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(e::persist);
        return e.getUuid();
    }

    @Test @TestSecurity(user = "user-1", roles = {"expenses:write"})
    void ownerSubmitsJustification_movesToPendingHR() {
        String uuid = seedNeedsJustification("user-1");
        given()
          .header("X-Requested-By", "user-1")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"justification\":\"Quarterly review with Customer X\"}")
        .when()
          .post("/expenses/" + uuid + "/justification")
        .then()
          .statusCode(204);

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(uuid));
        assertEquals("PENDING_HR", after.getReviewState());
        assertEquals("Quarterly review with Customer X", after.getEmployeeJustification());
    }

    @Test @TestSecurity(user = "stranger", roles = {"expenses:write"})
    void nonOwnerForbidden() {
        String uuid = seedNeedsJustification("user-1");
        given()
          .header("X-Requested-By", "stranger")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"justification\":\"...\"}")
        .when()
          .post("/expenses/" + uuid + "/justification")
        .then()
          .statusCode(403);
    }
}
