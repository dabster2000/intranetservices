package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDateTime;

@QuarkusTest
class ExpenseDecisionLogTest {
    @Test @TestTransaction
    void persistAndQuery() {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("u");
        e.setAmount(1.0);
        e.setAccount("1");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now());
        e.setStatus("CREATED");
        e.persist();

        ExpenseDecisionLog log = new ExpenseDecisionLog();
        log.uuid = java.util.UUID.randomUUID().toString();
        log.expenseUuid = e.getUuid();
        log.occurredAt = LocalDateTime.now();
        log.actorRole = "AI";
        log.action = "AI_VALIDATED_REJECTED";
        log.toReviewState = "NEEDS_JUSTIFICATION";
        log.aiRuleId = "R_MEAL_COST_PER_PERSON";
        log.reasonText = "above 125 DKK per person";
        log.persist();

        long count = ExpenseDecisionLog.count("expenseUuid", e.getUuid());
        assertEquals(1L, count);
    }
}
