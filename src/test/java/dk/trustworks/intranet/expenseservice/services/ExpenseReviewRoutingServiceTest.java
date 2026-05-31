package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit test — AIConfigSnapshot is mocked, no Quarkus/DB. */
class ExpenseReviewRoutingServiceTest {

    private ExpenseReviewRoutingService routerWith(AIConfigSnapshot.RuleView... rules) {
        AIConfigSnapshot cfg = Mockito.mock(AIConfigSnapshot.class);
        Mockito.when(cfg.getIntParameter("max_ai_revalidations", 3)).thenReturn(3);
        for (AIConfigSnapshot.RuleView r : rules) {
            Mockito.when(cfg.getRule(r.ruleId())).thenReturn(r);
        }
        ExpenseReviewRoutingService svc = new ExpenseReviewRoutingService();
        svc.config = cfg;
        return svc;
    }

    private AIConfigSnapshot.RuleView rule(String id, String resolutionType, int prio) {
        // RuleView(ruleId, displayName, description, severity, resolutionType, priority,
        //          active, outcomeMode, confidenceThreshold)
        return new AIConfigSnapshot.RuleView(id, id, "", "REJECT", resolutionType, prio,
                true, "BLOCK", 0.0);
    }

    @Test void autoFix_routesToEmployeeReceipt_needsFix() {
        var svc = routerWith(rule("R_DATE_MISMATCH", "AUTO_FIX", 80));
        var r = svc.route(List.of("R_DATE_MISMATCH"), 0);
        assertEquals(ExpenseStateDeriver.OWNER_EMPLOYEE, r.owner());
        assertEquals(ExpenseStateDeriver.KIND_RECEIPT, r.kind());
        assertEquals("R_DATE_MISMATCH", r.primaryRuleId());
    }

    @Test void judgment_routesToEmployeeJustification() {
        var svc = routerWith(rule("R_OFFICE_FOOD_DRINK", "JUDGMENT", 20));
        var r = svc.route(List.of("R_OFFICE_FOOD_DRINK"), 0);
        assertEquals(ExpenseStateDeriver.OWNER_EMPLOYEE, r.owner());
        assertEquals(ExpenseStateDeriver.KIND_JUSTIFICATION, r.kind());
        assertEquals("R_OFFICE_FOOD_DRINK", r.primaryRuleId());
    }

    @Test void autoFixAtCap_escalatesToJustification() {
        var svc = routerWith(rule("R_DATE_MISMATCH", "AUTO_FIX", 80));
        var r = svc.route(List.of("R_DATE_MISMATCH"), 3); // at cap
        assertEquals(ExpenseStateDeriver.KIND_JUSTIFICATION, r.kind());
    }

    @Test void noMatchingRules_defaultsToJustification_nullRule() {
        var svc = routerWith();
        var r = svc.route(List.of("R_UNKNOWN"), 0);
        assertEquals(ExpenseStateDeriver.OWNER_EMPLOYEE, r.owner());
        assertEquals(ExpenseStateDeriver.KIND_JUSTIFICATION, r.kind());
        assertNull(r.primaryRuleId());
    }

    // Ported from the prior @QuarkusTest version: when both an AUTO_FIX and a JUDGMENT rule fire,
    // judgment takes precedence. Preserves coverage the plan's 4 tests would otherwise drop.
    @Test void mixedRules_judgmentTakesPrecedence() {
        var svc = routerWith(
                rule("R_DATE_MISMATCH", "AUTO_FIX", 80),
                rule("R_OFFICE_FOOD_DRINK", "JUDGMENT", 20));
        var r = svc.route(List.of("R_DATE_MISMATCH", "R_OFFICE_FOOD_DRINK"), 0);
        assertEquals(ExpenseStateDeriver.OWNER_EMPLOYEE, r.owner());
        assertEquals(ExpenseStateDeriver.KIND_JUSTIFICATION, r.kind());
        assertEquals("R_OFFICE_FOOD_DRINK", r.primaryRuleId());
    }
}
