package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests for verdict normalization fallbacks; no Quarkus or database. */
class ExpenseAIValidationServiceNormalizeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExpenseAIValidationService validation = service();

    @Test
    void top_level_final_rule_id_blocks_when_rules_array_is_empty() throws Exception {
        ExpenseAIValidationService.AIResult result = validation.normalizePolicyVerdict(
                json("""
                    {
                      "final_rule_id": "R_MEAL_COST_PER_PERSON",
                      "rules": []
                    }
                    """),
                false,
                "Meal cap exceeded.",
                "Unknown Merchant",
                null, null
        );

        assertFalse(result.approved());
        assertEquals(ExpenseAIValidationService.AIResult.OUTCOME_BLOCK, result.outcome());
        assertEquals(List.of("R_MEAL_COST_PER_PERSON"), result.ruleIds());
    }

    @Test
    void top_level_rule_ids_still_honor_allow_list_suppression() throws Exception {
        ExpenseAIValidationService.AIResult result = validation.normalizePolicyVerdict(
                json("""
                    {
                      "ruleIds": ["R_MERCHANT_ALLOW_TOP_LEVEL"],
                      "final_rule_id": null,
                      "rules": []
                    }
                    """),
                false,
                "Merchant would normally be rejected.",
                "Allowed Top Merchant",
                null, null
        );

        assertTrue(result.approved());
        assertEquals(List.of(), result.ruleIds());
        assertTrue(result.reason().contains("allow-list"));
    }

    @Test
    void approved_true_does_not_let_legacy_rule_ids_create_a_block() throws Exception {
        ExpenseAIValidationService.AIResult result = validation.normalizePolicyVerdict(
                json("""
                    {
                      "ruleIds": ["R_MEAL_COST_PER_PERSON"],
                      "final_rule_id": "R_MEAL_COST_PER_PERSON",
                      "rules": []
                    }
                    """),
                true,
                "Approved.",
                "Unknown Merchant",
                null, null
        );

        assertTrue(result.approved());
        assertEquals(ExpenseAIValidationService.AIResult.OUTCOME_APPROVE, result.outcome());
        assertEquals(List.of(), result.ruleIds());
    }

    @Test
    void approved_false_with_no_parseable_rule_blocks_defensively() throws Exception {
        ExpenseAIValidationService.AIResult result = validation.normalizePolicyVerdict(
                json("""
                    {
                      "final_rule_id": null,
                      "rules": []
                    }
                    """),
                false,
                "Manual review needed.",
                "Unknown Merchant",
                null, null
        );

        assertFalse(result.approved());
        assertEquals(ExpenseAIValidationService.AIResult.OUTCOME_BLOCK, result.outcome());
        assertEquals(List.of(), result.ruleIds());
    }

    private static ExpenseAIValidationService service() {
        ExpenseAIValidationService service = new ExpenseAIValidationService();
        service.config = new TestConfig();
        service.merchantAllowList = new TestAllowList();
        return service;
    }

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    private static final class TestConfig extends AIConfigSnapshot {
        @Override
        public RuleView getRule(String ruleId) {
            if ("R_MEAL_COST_PER_PERSON".equals(ruleId)) {
                return new RuleView(ruleId, "Meal cost", "Meal cap", "REJECT",
                        "JUDGMENT", 30, true, "BLOCK", 0.0);
            }
            if ("R_MERCHANT_ALLOW_TOP_LEVEL".equals(ruleId)) {
                return new RuleView(ruleId, "Allow top", "Allowed merchant", "REJECT",
                        "JUDGMENT", 20, true, "BLOCK", 0.0);
            }
            return null;
        }

        @Override
        public BigDecimal getDecimalParameter(String key, BigDecimal fallback) {
            return fallback;
        }
    }

    private static final class TestAllowList extends MerchantAllowListService {
        @Override
        public boolean matches(String ruleId, String merchant) {
            return "R_MERCHANT_ALLOW_TOP_LEVEL".equals(ruleId)
                    && merchant != null
                    && merchant.contains("Allowed Top");
        }
    }
}
