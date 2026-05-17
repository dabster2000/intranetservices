package dk.trustworks.intranet.expenseservice.services;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ExpenseReviewRoutingServiceTest {

    @Inject ExpenseReviewRoutingService router;

    @Test
    void singleAutoFixRule_routesToNeedsFix() {
        var d = router.route(List.of("R_RECEIPT_READABLE"), /*aiValidationCount*/ 0);
        assertEquals("NEEDS_FIX", d.reviewState());
        assertEquals("R_RECEIPT_READABLE", d.primaryRuleId());
    }

    @Test
    void singleJudgmentRule_routesToNeedsJustification() {
        var d = router.route(List.of("R_MEAL_COST_PER_PERSON"), 0);
        assertEquals("NEEDS_JUSTIFICATION", d.reviewState());
        assertEquals("R_MEAL_COST_PER_PERSON", d.primaryRuleId());
    }

    @Test
    void mixedRules_judgmentTakesPrecedence() {
        var d = router.route(List.of("R_RECEIPT_READABLE", "R_MEAL_COST_PER_PERSON"), 0);
        assertEquals("NEEDS_JUSTIFICATION", d.reviewState());
        assertEquals("R_MEAL_COST_PER_PERSON", d.primaryRuleId());
    }

    @Test
    void atCap_forcesJustificationEvenForAutoFix() {
        var d = router.route(List.of("R_RECEIPT_READABLE"), /*aiValidationCount*/ 3);
        assertEquals("NEEDS_JUSTIFICATION", d.reviewState());
    }
}
