package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure gate behind the queued-settlement-internal lifecycle (Feature 3): a settlement internal
 * may auto-finalize only when EVERY self-billing voucher backing its group has been paid by the
 * client (8610 remainder == 0). Fail-closed — empty list or any null remainder is NOT paid.
 */
class SelfBilledPaidGateTest {

    private static SelfBilledPaidGate.VoucherRemainder vr(int voucher, String remainder) {
        return new SelfBilledPaidGate.VoucherRemainder(voucher, remainder == null ? null : new BigDecimal(remainder));
    }

    @Test
    void empty_list_is_not_paid() {
        assertFalse(SelfBilledPaidGate.allPaid(List.of()));
    }

    @Test
    void null_remainder_is_not_paid() {
        // A voucher with no finance_details 8610 row yields a null remainder -> fail closed.
        assertFalse(SelfBilledPaidGate.allPaid(List.of(vr(2069, "0.00"), vr(2070, null))));
    }

    @Test
    void mixed_remainders_are_not_paid() {
        assertFalse(SelfBilledPaidGate.allPaid(List.of(vr(2069, "0.00"), vr(2070, "153525.00"))));
    }

    @Test
    void all_zero_remainders_are_paid() {
        assertTrue(SelfBilledPaidGate.allPaid(List.of(vr(2069, "0.00"), vr(2070, "0"))));
    }

    @Test
    void single_zero_remainder_is_paid() {
        assertTrue(SelfBilledPaidGate.allPaid(List.of(vr(2069, "0.00"))));
    }

    @Test
    void non_zero_single_remainder_is_not_paid() {
        assertFalse(SelfBilledPaidGate.allPaid(List.of(vr(2069, "0.01"))));
    }
}
