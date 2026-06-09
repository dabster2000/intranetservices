package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.RestampDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelfBilledRestampPlannerTest {

    private static final String VAT = "vat", ENE = "ene", AS = "as";

    private static SelfBilledRestampPlanner.Internal internal(String uuid, String client, String cons, String amt, Integer sy, Integer sm) {
        return new SelfBilledRestampPlanner.Internal(uuid, client, AS, cons, new BigDecimal(amt), sy, sm);
    }
    private static SelfBilledRestampPlanner.Target target(String client, String cons, int y, int m, String amt) {
        return new SelfBilledRestampPlanner.Target(client, AS, cons, y, m, new BigDecimal(amt));
    }

    @Test void unstamped_internal_with_unique_match_is_restamped() {
        // Michelle Sep 78,200 exists (settlement_* NULL); self-billed Sep 78,200 -> re-stamp to VAT 2025-09.
        List<RestampDecision> d = SelfBilledRestampPlanner.plan(
                List.of(internal("i-sep", VAT, "michelle", "78200.00", null, null)),
                List.of(target(VAT, "michelle", 2025, 9, "78200.00")));
        assertEquals(RestampDecision.Outcome.RESTAMP, d.get(0).outcome());
        assertEquals(VAT, d.get(0).clientUuid());
        assertEquals(2025, d.get(0).workYear());
        assertEquals(9, d.get(0).workMonth());
    }

    @Test void arrears_internal_with_no_target_is_unmatched_not_reversed() {
        // Julie Apr 132,651.70 exists but April is not self-billed yet -> UNMATCHED (leave alone, no clawback).
        List<RestampDecision> d = SelfBilledRestampPlanner.plan(
                List.of(internal("i-apr", ENE, "julie", "132651.70", 2026, 4)),
                List.of());
        assertEquals(RestampDecision.Outcome.UNMATCHED, d.get(0).outcome());
    }

    @Test void already_correctly_stamped_is_no_change() {
        List<RestampDecision> d = SelfBilledRestampPlanner.plan(
                List.of(internal("i-aug", VAT, "michelle", "153525.00", 2025, 8)),
                List.of(target(VAT, "michelle", 2025, 8, "153525.00")));
        assertEquals(RestampDecision.Outcome.NO_CHANGE, d.get(0).outcome());
    }

    @Test void different_client_does_not_match() {
        // A self-billed target on Energinet must NOT re-stamp a Vattenfall internal even at equal amount.
        List<RestampDecision> d = SelfBilledRestampPlanner.plan(
                List.of(internal("i-x", VAT, "michelle", "100000.00", null, null)),
                List.of(target(ENE, "michelle", 2025, 7, "100000.00")));
        assertEquals(RestampDecision.Outcome.UNMATCHED, d.get(0).outcome());
    }

    @Test void ambiguous_when_two_same_client_targets_match_amount() {
        List<RestampDecision> d = SelfBilledRestampPlanner.plan(
                List.of(internal("i-x", VAT, "michelle", "100000.00", null, null)),
                List.of(target(VAT, "michelle", 2025, 7, "100000.00"), target(VAT, "michelle", 2025, 9, "100000.00")));
        assertEquals(RestampDecision.Outcome.AMBIGUOUS, d.get(0).outcome());
    }

    @Test void wrong_period_stamp_with_unique_match_is_restamped_to_correct_period() {
        // THE primary migration scenario: internal stamped to the wrong period, unique self-billed target -> RESTAMP.
        List<RestampDecision> d = SelfBilledRestampPlanner.plan(
                List.of(internal("i-x", VAT, "michelle", "78200.00", 2025, 7)), // stamped wrong (Jul)
                List.of(target(VAT, "michelle", 2025, 9, "78200.00")));         // correct period (Sep)
        assertEquals(RestampDecision.Outcome.RESTAMP, d.get(0).outcome());
        assertEquals(2025, d.get(0).workYear());
        assertEquals(9, d.get(0).workMonth());
    }

    @Test void amount_just_outside_tolerance_is_unmatched() {
        List<RestampDecision> d = SelfBilledRestampPlanner.plan(
                List.of(internal("i-x", VAT, "michelle", "100000.00", null, null)),
                List.of(target(VAT, "michelle", 2025, 7, "99998.99"))); // diff = 1001.01 kr > 1 kr
        assertEquals(RestampDecision.Outcome.UNMATCHED, d.get(0).outcome());
    }
}
