package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.dto.AddAllowListEntryRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the allow-list bypass via {@link ExpenseAIValidationService#isAllowListed}.
 *
 * The full validation pipeline integration is not tested here because it requires
 * mocking OpenAI. The bypass logic is verified at the helper-method level — call
 * sites that consult this method are tested separately.
 */
@QuarkusTest
class MerchantAllowListBypassTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject ExpenseAIValidationService validation;
    @Inject MerchantAllowListService allowList;

    @Test
    @Transactional
    void allow_listed_merchant_is_recognized() {
        allowList.add(new AddAllowListEntryRequest(
            "R_MERCHANT_ALLOW_OFFICE_FOOD_DRINK",
            "Café Det Sker",
            "CONTAINS",
            null
        ));
        boolean bypassed = validation.isAllowListed("R_MERCHANT_ALLOW_OFFICE_FOOD_DRINK", "Café Det Sker, Kbh");
        assertTrue(bypassed, "Allow-listed merchant should be recognized");
    }

    @Test
    @Transactional
    void non_allow_listed_merchant_is_not_recognized() {
        boolean bypassed = validation.isAllowListed("R_MERCHANT_ALLOW_OFFICE_FOOD_DRINK", "Random Restaurant XYZ");
        assertTrue(!bypassed, "Non-allow-listed merchant should not be recognized");
    }

    @Test
    @Transactional
    void null_merchant_is_not_allow_listed() {
        boolean bypassed = validation.isAllowListed("R_MERCHANT_ALLOW_OFFICE_FOOD_DRINK", null);
        assertTrue(!bypassed, "Null merchant should not be allow-listed");
    }

    @Test
    @Transactional
    void suppressed_rule_from_rules_and_final_rule_approves() throws Exception {
        allowList.add(new AddAllowListEntryRequest(
                "R_MERCHANT_ALLOW_RULES_FINAL",
                "Allowed Merchant",
                "CONTAINS",
                null
        ));

        ExpenseAIValidationService.AIResult result = validation.normalizePolicyVerdict(
                json("""
                    {
                      "final_rule_id": "R_MERCHANT_ALLOW_RULES_FINAL",
                      "rules": [
                        {
                          "id": "R_MERCHANT_ALLOW_RULES_FINAL",
                          "severity": "REJECT",
                          "decision": "FAILED",
                          "user_message": "Merchant would normally be rejected."
                        }
                      ]
                    }
                    """),
                false,
                "Merchant would normally be rejected.",
                "Allowed Merchant Copenhagen",
                null, null
        );

        assertTrue(result.approved());
        assertEquals(List.of(), result.ruleIds());
        assertTrue(result.reason().contains("allow-list"));
    }

    @Test
    @Transactional
    void suppressed_top_level_rule_ids_approve() throws Exception {
        allowList.add(new AddAllowListEntryRequest(
                "R_MERCHANT_ALLOW_TOP_LEVEL",
                "Allowed Top",
                "CONTAINS",
                null
        ));

        ExpenseAIValidationService.AIResult result = validation.normalizePolicyVerdict(
                json("""
                    {
                      "ruleIds": ["R_MERCHANT_ALLOW_TOP_LEVEL"],
                      "final_rule_id": "R_MERCHANT_ALLOW_TOP_LEVEL",
                      "rules": [
                        {
                          "id": "R_MERCHANT_ALLOW_TOP_LEVEL",
                          "severity": "REJECT",
                          "decision": "FAILED",
                          "confidence": 0.95,
                          "user_message": "Merchant would normally be rejected."
                        }
                      ]
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
    @Transactional
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
    @Transactional
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

    @Test
    @Transactional
    void mixed_suppressed_and_non_suppressed_failures_stay_rejected() throws Exception {
        allowList.add(new AddAllowListEntryRequest(
                "R_MERCHANT_ALLOW_MIXED",
                "Allowed Mixed",
                "CONTAINS",
                null
        ));

        ExpenseAIValidationService.AIResult result = validation.normalizePolicyVerdict(
                json("""
                    {
                      "final_rule_id": "R_MEAL_COST_PER_PERSON",
                      "rules": [
                        {
                          "id": "R_MERCHANT_ALLOW_MIXED",
                          "severity": "REJECT",
                          "decision": "FAILED",
                          "user_message": "Merchant would normally be rejected."
                        },
                        {
                          "id": "R_MEAL_COST_PER_PERSON",
                          "severity": "REJECT",
                          "decision": "FAILED",
                          "user_message": "Meal cap exceeded."
                        }
                      ]
                    }
                    """),
                false,
                "Meal cap exceeded.",
                "Allowed Mixed Merchant",
                null, null
        );

        assertFalse(result.approved());
        assertEquals(List.of("R_MEAL_COST_PER_PERSON"), result.ruleIds());
        assertEquals("Meal cap exceeded.", result.reason());
    }

    @Test
    @Transactional
    void non_allow_listed_merchant_rule_stays_rejected() throws Exception {
        ExpenseAIValidationService.AIResult result = validation.normalizePolicyVerdict(
                json("""
                    {
                      "final_rule_id": "R_MERCHANT_ALLOW_NOT_LISTED",
                      "rules": [
                        {
                          "id": "R_MERCHANT_ALLOW_NOT_LISTED",
                          "severity": "REJECT",
                          "decision": "FAILED",
                          "user_message": "Merchant rejected."
                        }
                      ]
                    }
                    """),
                false,
                "Merchant rejected.",
                "Unknown Merchant",
                null, null
        );

        assertFalse(result.approved());
        assertEquals(List.of("R_MERCHANT_ALLOW_NOT_LISTED"), result.ruleIds());
    }

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }
}
