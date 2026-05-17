package dk.trustworks.intranet.expenseservice.services;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ExpenseAIValidationServiceUsesSnapshotTest {

    @Inject ExpenseAIValidationService svc;
    @Inject AIConfigSnapshot snapshot;

    @Test
    void buildsSystemPromptFromSnapshotRules() {
        String built = svc.buildPolicyValidationPrompt();
        for (var r : snapshot.getRulesByPriority()) {
            assertTrue(built.contains(r.ruleId()),
                "policy prompt must mention rule " + r.ruleId());
        }
    }

    @Test
    void rendersThresholdParametersInPolicyPrompt() {
        String built = svc.buildPolicyValidationPrompt();

        assertTrue(built.contains("Food or drink above 125 DKK per person requires a documented business reason."));
        assertTrue(built.contains("IT equipment purchases above 500 DKK require pre-approval."));
        assertTrue(built.contains("Receipt date and expense date must be within 30 calendar days of each other."));

        assertFalse(built.contains("{{meal_cost_per_person_dkk}}"));
        assertFalse(built.contains("{{it_equipment_pre_approval_dkk}}"));
        assertFalse(built.contains("{{date_mismatch_tolerance_days}}"));
    }
}
