package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseDecisionLogService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class ExpenseResourceDecisionLogTest {

    @Inject ExpenseDecisionLogService logs;

    @Test @TestSecurity(user = "user-1", roles = {"expenses:read"})
    void ownerCanReadOwnLog() {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("user-1");
        e.setAmount(1.0);
        e.setAccount("1");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now());
        e.setStatus("CREATED");
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            e.persist();
            logs.recordAIRejection(e, "NEEDS_JUSTIFICATION", "R_MEAL_COST_PER_PERSON", "test");
        });

        given()
          .header("X-Requested-By", "user-1")
        .when()
          .get("/expenses/" + e.getUuid() + "/decision-log")
        .then()
          .statusCode(200)
          .body("size()", is(1))
          .body("[0].action", is("AI_VALIDATED_REJECTED"));
    }
}
