package dk.trustworks.intranet.expenseservice.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dk.trustworks.intranet.expenseservice.services.ExpenseAIOutcomeCombiner.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure unit test — no Quarkus, no DB. */
class ExpenseAIOutcomeCombinerTest {

    private FiredRule rule(String id, double conf, String mode, double threshold) {
        return new FiredRule(id, conf, mode, threshold);
    }

    @Test void noRules_noMismatch_isApprove() {
        Outcome o = combine(List.of(), AmountSignal.NONE);
        assertEquals(OUTCOME_APPROVE, o.outcome());
        assertTrue(o.blockingRuleIds().isEmpty());
        assertTrue(o.softFlags().isEmpty());
        assertNull(o.attentionOwner());
    }

    @Test void blockRuleAboveThreshold_blocks() {
        Outcome o = combine(List.of(rule("R_OFFICE_FOOD_DRINK", 0.90, "BLOCK", 0.70)), AmountSignal.NONE);
        assertEquals(OUTCOME_BLOCK, o.outcome());
        assertEquals(List.of("R_OFFICE_FOOD_DRINK"), o.blockingRuleIds());
        assertEquals(0.90, o.confidence());
    }

    @Test void blockRuleBelowThreshold_demotesToSoftFlag() {
        Outcome o = combine(List.of(rule("R_OFFICE_FOOD_DRINK", 0.50, "BLOCK", 0.70)), AmountSignal.NONE);
        assertEquals(OUTCOME_SOFT_FLAG, o.outcome());
        assertTrue(o.blockingRuleIds().isEmpty());
        assertEquals(List.of("R_OFFICE_FOOD_DRINK"), o.softFlags());
    }

    @Test void softFlagModeRule_neverBlocks() {
        Outcome o = combine(List.of(rule("R_RECEIPT_READABLE", 0.99, "SOFT_FLAG", 0.0)), AmountSignal.NONE);
        assertEquals(OUTCOME_SOFT_FLAG, o.outcome());
        assertEquals(List.of("R_RECEIPT_READABLE"), o.softFlags());
    }

    @Test void offModeRule_isIgnored() {
        Outcome o = combine(List.of(rule("R_DATE_MISMATCH", 0.99, "OFF", 0.0)), AmountSignal.NONE);
        assertEquals(OUTCOME_APPROVE, o.outcome());
        assertTrue(o.softFlags().isEmpty());
    }

    @Test void thresholdZero_alwaysBlocks() {
        Outcome o = combine(List.of(rule("R_HOME_PROXIMITY", 0.01, "BLOCK", 0.0)), AmountSignal.NONE);
        assertEquals(OUTCOME_BLOCK, o.outcome());
    }

    @Test void largeAmountMismatch_blocksAsEmployeeAmountMismatch() {
        Outcome o = combine(List.of(), AmountSignal.BLOCK);
        assertEquals(OUTCOME_BLOCK, o.outcome());
        assertEquals("EMPLOYEE", o.attentionOwner());
        assertEquals("AMOUNT_MISMATCH", o.attentionKind());
    }

    @Test void smallAmountMismatch_softFlags() {
        Outcome o = combine(List.of(), AmountSignal.SOFT);
        assertEquals(OUTCOME_SOFT_FLAG, o.outcome());
        assertTrue(o.softFlags().contains("AMOUNT_MISMATCH"));
        assertNull(o.attentionOwner());
    }

    @Test void policyBlockWins_overAmountSoft() {
        Outcome o = combine(List.of(rule("R_OFFICE_FOOD_DRINK", 0.9, "BLOCK", 0.0)), AmountSignal.SOFT);
        assertEquals(OUTCOME_BLOCK, o.outcome());
        assertNull(o.attentionOwner(), "policy block routes via the rule router, not amount-mismatch");
        assertTrue(o.softFlags().contains("AMOUNT_MISMATCH"));
    }
}
