package dk.trustworks.intranet.expenseservice.services;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AIConfigSnapshotTest {

    @Inject AIConfigSnapshot snapshot;

    @Test
    void snapshotExposesSeededRulesAndParameters() {
        var rules = snapshot.getRulesByPriority();
        assertEquals(13, rules.size());
        assertEquals("R_OVERRIDE_WHITELIST_ADDRESS", rules.get(0).ruleId());

        assertEquals(125,  snapshot.getIntParameter("meal_cost_per_person_dkk", 0));
        assertEquals(3,    snapshot.getIntParameter("max_ai_revalidations",     0));
        assertEquals("125", snapshot.getParameters().get("meal_cost_per_person_dkk"));
        assertEquals("500", snapshot.getParameters().get("it_equipment_pre_approval_dkk"));
        assertEquals("30",  snapshot.getParameters().get("date_mismatch_tolerance_days"));
        assertNotNull(snapshot.getPromptBody("VISION_EXTRACTION"));
    }

    @Test
    void resolutionTypeAccessor() {
        assertEquals("AUTO_FIX", snapshot.getResolutionType("R_RECEIPT_READABLE"));
        assertEquals("JUDGMENT", snapshot.getResolutionType("R_MEAL_COST_PER_PERSON"));
    }
}
