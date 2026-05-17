package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseDecisionLog;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ExpenseDecisionLogServiceTest {

    @Inject ExpenseDecisionLogService logs;

    @Test @TestTransaction
    void appendsAITransition() {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("u");
        e.setAmount(1.0);
        e.setAccount("1");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now());
        e.setStatus("CREATED");
        e.persist();

        logs.recordAIRejection(e, "NEEDS_JUSTIFICATION", "R_MEAL_COST_PER_PERSON", "above 125 DKK/person");

        var found = ExpenseDecisionLog.<ExpenseDecisionLog>list("expenseUuid", e.getUuid());
        assertEquals(1, found.size());
        var row = found.get(0);
        assertEquals("AI", row.actorRole);
        assertEquals("AI_VALIDATED_REJECTED", row.action);
        assertEquals("NEEDS_JUSTIFICATION", row.toReviewState);
        assertEquals("R_MEAL_COST_PER_PERSON", row.aiRuleId);
    }
}
