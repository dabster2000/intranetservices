package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.model.PayoutKind;
import dk.trustworks.intranet.aggregates.bonus.individual.model.PayoutStatus;
import dk.trustworks.intranet.aggregates.bonus.individual.model.ProjectedPayout;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the materialisation gates ({@code shouldMaterialize} / {@code isNegativeClawback})
 * and the recurring-kind classifier — no Quarkus boot required.
 * <p>
 * Policy (spec §7 / D1): only strictly-positive amounts are AUTO-written. A zero is a no-op; a NEGATIVE
 * settlement is a CLAWBACK that is NOT auto-paid (Danløn cannot export a negative løntype-41 line, and the
 * export drops a net-negative "41 Bonus" row entirely) — it is surfaced for manual handling and still shown
 * in the projection.
 */
class IndividualBonusPayoutServiceTest {

    private static ProjectedPayout payout(BigDecimal amount, PayoutKind kind) {
        return new ProjectedPayout(LocalDate.of(2027, 7, 1), amount, kind, PayoutStatus.PROJECTED,
                "individual:ref", false, false);
    }

    // --- shouldMaterialize: only positive amounts are auto-written ---

    @Test
    void positiveAmounts_areMaterialised() {
        assertTrue(IndividualBonusPayoutService.shouldMaterialize(payout(bd(50_000), PayoutKind.ADVANCE)));
        assertTrue(IndividualBonusPayoutService.shouldMaterialize(payout(bd(75_000), PayoutKind.TRUEUP)));
        assertTrue(IndividualBonusPayoutService.shouldMaterialize(payout(bd(675_000), PayoutKind.YEARLY)));
    }

    @Test
    void negativeSettlement_isNotAutoWritten() {
        // A CLAWBACK is no longer auto-written — it would be silently dropped by the Danløn export.
        assertFalse(IndividualBonusPayoutService.shouldMaterialize(payout(bd(-25_000), PayoutKind.TRUEUP)));
        assertFalse(IndividualBonusPayoutService.shouldMaterialize(payout(bd(-50_000), PayoutKind.FINAL_SETTLEMENT)));
    }

    @Test
    void zeroAmount_isAlwaysSkipped() {
        assertFalse(IndividualBonusPayoutService.shouldMaterialize(payout(BigDecimal.ZERO, PayoutKind.TRUEUP)));
        assertFalse(IndividualBonusPayoutService.shouldMaterialize(payout(BigDecimal.ZERO, PayoutKind.ADVANCE)));
    }

    @Test
    void nullAmount_isSkipped() {
        assertFalse(IndividualBonusPayoutService.shouldMaterialize(payout(null, PayoutKind.TRUEUP)));
    }

    // --- isNegativeClawback: net-negative TRUEUP / FINAL_SETTLEMENT → manual handling ---

    @Test
    void negativeSettlement_isClawback() {
        assertTrue(IndividualBonusPayoutService.isNegativeClawback(payout(bd(-25_000), PayoutKind.TRUEUP)));
        assertTrue(IndividualBonusPayoutService.isNegativeClawback(payout(bd(-50_000), PayoutKind.FINAL_SETTLEMENT)));
    }

    @Test
    void negativeNonSettlement_isNotClawback() {
        // Advances/monthly never go negative in practice; even if they did they are not a settlement clawback.
        assertFalse(IndividualBonusPayoutService.isNegativeClawback(payout(bd(-5_000), PayoutKind.ADVANCE)));
        assertFalse(IndividualBonusPayoutService.isNegativeClawback(payout(bd(-5_000), PayoutKind.MONTHLY)));
    }

    @Test
    void positiveOrZeroSettlement_isNotClawback() {
        assertFalse(IndividualBonusPayoutService.isNegativeClawback(payout(bd(25_000), PayoutKind.TRUEUP)));
        assertFalse(IndividualBonusPayoutService.isNegativeClawback(payout(BigDecimal.ZERO, PayoutKind.FINAL_SETTLEMENT)));
    }

    // --- kind classification (job scheduledOnly filter + ACTIVE gate) ---

    @Test
    void monthlyAdvanceKinds_areRecurring() {
        assertTrue(IndividualBonusScheduleService.isMonthlyAdvanceKind(PayoutKind.ADVANCE));
        assertTrue(IndividualBonusScheduleService.isMonthlyAdvanceKind(PayoutKind.MONTHLY));
        assertFalse(IndividualBonusScheduleService.isMonthlyAdvanceKind(PayoutKind.YEARLY));
        assertFalse(IndividualBonusScheduleService.isMonthlyAdvanceKind(PayoutKind.TRUEUP));
        assertFalse(IndividualBonusScheduleService.isMonthlyAdvanceKind(PayoutKind.FINAL_SETTLEMENT));
    }

    @Test
    void settlementKinds_areAdminConfirmed() {
        assertTrue(IndividualBonusScheduleService.isSettlementKind(PayoutKind.YEARLY));
        assertTrue(IndividualBonusScheduleService.isSettlementKind(PayoutKind.TRUEUP));
        assertTrue(IndividualBonusScheduleService.isSettlementKind(PayoutKind.FINAL_SETTLEMENT));
        assertFalse(IndividualBonusScheduleService.isSettlementKind(PayoutKind.ADVANCE));
        assertFalse(IndividualBonusScheduleService.isSettlementKind(PayoutKind.MONTHLY));
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }
}
