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
 * Pure unit tests for the materialisation gate {@code IndividualBonusPayoutService.shouldMaterialize}:
 * a zero amount is always skipped; a NEGATIVE amount is written only for a TRUEUP / FINAL_SETTLEMENT
 * (a CLAWBACK deduction), never for an advance / monthly / yearly payout. No Quarkus boot required.
 */
class IndividualBonusPayoutServiceTest {

    private static ProjectedPayout payout(BigDecimal amount, PayoutKind kind) {
        return new ProjectedPayout(LocalDate.of(2027, 7, 1), amount, kind, PayoutStatus.PROJECTED,
                "individual:ref", false, false);
    }

    @Test
    void negativeTrueUp_isMaterialised() {
        assertTrue(IndividualBonusPayoutService.shouldMaterialize(payout(bd(-25_000), PayoutKind.TRUEUP)));
    }

    @Test
    void negativeFinalSettlement_isMaterialised() {
        assertTrue(IndividualBonusPayoutService.shouldMaterialize(payout(bd(-50_000), PayoutKind.FINAL_SETTLEMENT)));
    }

    @Test
    void negativeNonTrueUp_isSkipped() {
        assertFalse(IndividualBonusPayoutService.shouldMaterialize(payout(bd(-5_000), PayoutKind.ADVANCE)));
        assertFalse(IndividualBonusPayoutService.shouldMaterialize(payout(bd(-5_000), PayoutKind.MONTHLY)));
        assertFalse(IndividualBonusPayoutService.shouldMaterialize(payout(bd(-5_000), PayoutKind.YEARLY)));
    }

    @Test
    void zeroAmount_isAlwaysSkipped() {
        assertFalse(IndividualBonusPayoutService.shouldMaterialize(payout(BigDecimal.ZERO, PayoutKind.TRUEUP)));
        assertFalse(IndividualBonusPayoutService.shouldMaterialize(payout(BigDecimal.ZERO, PayoutKind.ADVANCE)));
    }

    @Test
    void positiveAmounts_areMaterialised() {
        assertTrue(IndividualBonusPayoutService.shouldMaterialize(payout(bd(50_000), PayoutKind.ADVANCE)));
        assertTrue(IndividualBonusPayoutService.shouldMaterialize(payout(bd(75_000), PayoutKind.TRUEUP)));
    }

    @Test
    void nullAmount_isSkipped() {
        assertFalse(IndividualBonusPayoutService.shouldMaterialize(payout(null, PayoutKind.TRUEUP)));
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }
}
