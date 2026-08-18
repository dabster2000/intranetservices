package dk.trustworks.intranet.expenseservice.events;

import dk.trustworks.intranet.expenseservice.events.ExpenseJustificationAiConsumer.Verdict;
import dk.trustworks.intranet.expenseservice.services.AIConfigSnapshot.RuleView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit tests (no Quarkus, no DB, no OpenAI) for the W2 pure helpers. */
class ExpenseJustificationAiConsumerTest {

    private static RuleView rule(String id, boolean alwaysHuman) {
        return new RuleView(id, id, "desc", "REJECT", "JUDGMENT", 10, true, "BLOCK", 0.8, alwaysHuman);
    }

    // ---- guardrailReason -------------------------------------------------

    @Test
    void amount_at_or_above_threshold_forces_human() {
        String why = ExpenseJustificationAiConsumer.guardrailReason(
                1000.0, 1000.0, 0, 3, 90, rule("R_OFFICE_FOOD_DRINK", false));
        assertNotNull(why);
        assertTrue(why.contains("1000"));
    }

    @Test
    void repeat_fires_at_threshold_forces_human() {
        String why = ExpenseJustificationAiConsumer.guardrailReason(
                200.0, 1000.0, 3, 3, 90, rule("R_OFFICE_FOOD_DRINK", false));
        assertNotNull(why);
        assertTrue(why.contains("3×"));
    }

    @Test
    void always_human_rule_forces_human() {
        String why = ExpenseJustificationAiConsumer.guardrailReason(
                200.0, 1000.0, 0, 3, 90, rule("R_HOME_PROXIMITY", true));
        assertNotNull(why);
        assertTrue(why.contains("always-human"));
    }

    @Test
    void unknown_rule_forces_human() {
        assertNotNull(ExpenseJustificationAiConsumer.guardrailReason(
                200.0, 1000.0, 0, 3, 90, null));
    }

    @Test
    void small_amount_first_fire_known_rule_lets_ai_judge() {
        assertNull(ExpenseJustificationAiConsumer.guardrailReason(
                200.0, 1000.0, 1, 3, 90, rule("R_OFFICE_FOOD_DRINK", false)));
    }

    // ---- parseVerdict ----------------------------------------------------

    @Test
    void parses_a_valid_verdict() {
        Verdict v = ExpenseJustificationAiConsumer.parseVerdict(
                "{\"accept\":true,\"confidence\":0.91,\"reservation\":\"\"}");
        assertNotNull(v);
        assertTrue(v.accept());
        assertEquals(0.91, v.confidence(), 1e-9);
    }

    @Test
    void out_of_range_confidence_collapses_to_zero() {
        Verdict v = ExpenseJustificationAiConsumer.parseVerdict(
                "{\"accept\":true,\"confidence\":7.5,\"reservation\":\"x\"}");
        assertNotNull(v);
        assertEquals(0.0, v.confidence(), 1e-9);
    }

    @Test
    void garbage_and_missing_fields_yield_null() {
        assertNull(ExpenseJustificationAiConsumer.parseVerdict("not json"));
        assertNull(ExpenseJustificationAiConsumer.parseVerdict(null));
        assertNull(ExpenseJustificationAiConsumer.parseVerdict("{\"accept\":true}"));
    }

    @Test
    void refusal_fallback_shape_parses_as_refer() {
        Verdict v = ExpenseJustificationAiConsumer.parseVerdict(
                "{\"accept\":false,\"confidence\":0.0,\"reservation\":\"AI refused or failed\"}");
        assertNotNull(v);
        assertFalse(v.accept());
    }

    // ---- appendSoftFlag --------------------------------------------------

    @Test
    void appends_to_null_empty_and_existing_arrays() {
        assertEquals("[\"AI_ACCEPTED_JUSTIFICATION\"]",
                ExpenseJustificationAiConsumer.appendSoftFlag(null, "AI_ACCEPTED_JUSTIFICATION"));
        assertEquals("[\"AI_ACCEPTED_JUSTIFICATION\"]",
                ExpenseJustificationAiConsumer.appendSoftFlag("[]", "AI_ACCEPTED_JUSTIFICATION"));
        assertEquals("[\"R_WEEKEND_FOOD_DRINK\",\"AI_ACCEPTED_JUSTIFICATION\"]",
                ExpenseJustificationAiConsumer.appendSoftFlag(
                        "[\"R_WEEKEND_FOOD_DRINK\"]", "AI_ACCEPTED_JUSTIFICATION"));
    }

    @Test
    void does_not_duplicate_an_existing_flag() {
        assertEquals("[\"AI_ACCEPTED_JUSTIFICATION\"]",
                ExpenseJustificationAiConsumer.appendSoftFlag(
                        "[\"AI_ACCEPTED_JUSTIFICATION\"]", "AI_ACCEPTED_JUSTIFICATION"));
    }

    @Test
    void broken_json_falls_back_to_a_fresh_array() {
        assertEquals("[\"AI_ACCEPTED_JUSTIFICATION\"]",
                ExpenseJustificationAiConsumer.appendSoftFlag("{broken", "AI_ACCEPTED_JUSTIFICATION"));
    }
}
