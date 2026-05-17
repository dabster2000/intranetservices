package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.AIConfigHistory;
import dk.trustworks.intranet.expenseservice.model.AIRuleCatalog;
import dk.trustworks.intranet.expenseservice.model.AIValidationParameter;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AIConfigServiceTest {

    @Inject AIConfigService svc;
    @Inject AIConfigSnapshot snapshot;

    @Test @TestTransaction
    void updatingRuleAppendsHistory() {
        AIRuleCatalog meal = AIRuleCatalog.<AIRuleCatalog>find("ruleId", "R_MEAL_COST_PER_PERSON").firstResult();
        String oldDescription = meal.description;
        meal.description = "Updated description for testing";
        svc.updateRule(meal, "admin-user-uuid");

        long historyCount = AIConfigHistory.count(
            "entityKind = ?1 and entityKey = ?2", "RULE", "R_MEAL_COST_PER_PERSON");
        assertEquals(1L, historyCount);

        AIConfigHistory historyRow = AIConfigHistory.<AIConfigHistory>find(
            "entityKey", "R_MEAL_COST_PER_PERSON").firstResult();
        assertTrue(historyRow.snapshotJson.contains(oldDescription),
            "snapshot must include the pre-update description");
    }

    @Test @TestTransaction
    void updatingParameterPublishesRefreshEvent() {
        int oldCap = snapshot.getIntParameter("max_ai_revalidations", -1);
        AIValidationParameter p = AIValidationParameter.<AIValidationParameter>find(
            "parameterKey", "max_ai_revalidations").firstResult();
        p.parameterValue = String.valueOf(oldCap + 1);
        svc.updateParameter(p, "admin-user-uuid");
        assertEquals(oldCap + 1, snapshot.getIntParameter("max_ai_revalidations", -1));
    }
}
