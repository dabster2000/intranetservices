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
}
