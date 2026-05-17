package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.events.ExpenseCreatedConsumer;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseAIValidationService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class AIConfigDryRunTest {

    @InjectMock ExpenseCreatedConsumer consumer;

    @Test
    @TestSecurity(user = "admin", roles = {"admin:write"})
    void dryRunReturnsRuleVerdictsWithoutModifyingExpense() {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("u");
        e.setAmount(450.0);
        e.setAccount("3585");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now());
        e.setStatus("CREATED");
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(e::persist);

        // Mock the consumer's validateExpense to return a rejection
        when(consumer.validateExpense(any(Expense.class))).thenReturn(
            new ExpenseAIValidationService.AIResult(false, "above 125 DKK/person",
                java.util.List.of("R_MEAL_COST_PER_PERSON")));

        given()
          .header("X-Requested-By", "admin")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"expenseUuid\":\"" + e.getUuid() + "\"}")
        .when()
          .post("/admin/ai-config/dry-run")
        .then()
          .statusCode(200)
          .body("ruleVerdicts.size()", greaterThan(0))
          .body("routingOutcome", anyOf(is("NEEDS_FIX"), is("NEEDS_JUSTIFICATION"), is("APPROVED")));

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(e.getUuid()));
        assertNull(after.getAiValidationApproved(), "dry-run must not modify the expense");
    }
}
