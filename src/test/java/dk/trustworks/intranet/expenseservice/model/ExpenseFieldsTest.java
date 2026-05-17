package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ExpenseFieldsTest {
    @Test @TestTransaction
    void newFieldsRoundTrip() {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("test-user");
        e.setAmount(100.0);
        e.setAccount("3585");
        e.setAccountname("Frokost");
        e.setDescription("test");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now());
        e.setStatus("CREATED");

        e.setReviewState("NEEDS_JUSTIFICATION");
        e.setAiRuleId("R_MEAL_COST_PER_PERSON");
        e.setAiRuleIdsJson("[\"R_MEAL_COST_PER_PERSON\"]");
        e.setEmployeeJustification("for the client");
        e.setHrDecision(null);
        e.setAiValidationCount(1);

        e.persist();
        Expense round = Expense.findById(e.getUuid());
        assertEquals("NEEDS_JUSTIFICATION", round.getReviewState());
        assertEquals("R_MEAL_COST_PER_PERSON", round.getAiRuleId());
        assertEquals(1, round.getAiValidationCount());
        assertNotNull(round.getVersion());
    }
}
