package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AIRuleCatalogTest {
    @Test @TestTransaction
    void thirteenSeededRulesLoadAsPanacheEntities() {
        long count = AIRuleCatalog.count("active", true);
        assertEquals(13L, count);
        AIRuleCatalog meal = AIRuleCatalog.find("ruleId", "R_MEAL_COST_PER_PERSON").firstResult();
        assertNotNull(meal);
        assertEquals("JUDGMENT", meal.resolutionType);
    }
}
