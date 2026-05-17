package dk.trustworks.intranet.expenseservice.events;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseDecisionLog;
import dk.trustworks.intranet.expenseservice.services.ExpenseAIValidationService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ExpenseCreatedConsumer} routes an AI-rejected expense
 * to the correct review state and appends a row to {@code expense_decision_log}.
 *
 * <p>The OpenAI call itself is mocked via {@code @InjectMock} on
 * {@link ExpenseAIValidationService} so the test exercises only the consumer's
 * routing / persistence wiring.
 */
@QuarkusTest
class ExpenseCreatedConsumerRoutingTest {

    @InjectMock ExpenseAIValidationService aiSvc;
    @Inject     ExpenseCreatedConsumer consumer;

    @Test
    void aiRejectionWithJudgmentRule_setsNeedsJustification_andLogs() {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("u");
        e.setAmount(450.0);
        e.setAccount("3585");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now());
        e.setStatus("CREATED");
        QuarkusTransaction.requiringNew().run(() -> e.persist());

        var response = new ExpenseAIValidationService.AIResult(
                false,
                "above 125 DKK per person",
                java.util.List.of("R_MEAL_COST_PER_PERSON"));
        when(aiSvc.validateWithExtractedText(any(), any(), any(), any(), any(), any())).thenReturn(response);
        when(aiSvc.extractExpenseData(any())).thenReturn("placeholder text");

        consumer.onExpenseCreated(e.getUuid());

        Expense refreshed = QuarkusTransaction.requiringNew()
                .call(() -> Expense.findById(e.getUuid()));
        assertEquals("CREATED", refreshed.getStatus());
        assertEquals("NEEDS_JUSTIFICATION", refreshed.getReviewState());
        assertEquals("R_MEAL_COST_PER_PERSON", refreshed.getAiRuleId());
        assertEquals(Boolean.FALSE, refreshed.getAiValidationApproved());
        assertEquals(1, refreshed.getAiValidationCount());
        assertNotNull(refreshed.getAiRuleIdsJson());

        long logCount = QuarkusTransaction.requiringNew().call(() ->
                ExpenseDecisionLog.count("expenseUuid", e.getUuid()));
        assertEquals(1, logCount);
    }
}
