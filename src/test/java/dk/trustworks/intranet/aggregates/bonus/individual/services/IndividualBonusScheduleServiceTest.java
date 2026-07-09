package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Advance;
import dk.trustworks.intranet.aggregates.bonus.individual.model.AdvanceType;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Basis;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Cadence;
import dk.trustworks.intranet.aggregates.bonus.individual.model.NegativeHandling;
import dk.trustworks.intranet.aggregates.bonus.individual.model.ProRating;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Schedule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.aggregates.bonus.individual.model.TrueUp;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Vehicle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit tests for the advance/true-up reconciliation math — no Quarkus boot required (the injected
 * collaborators are not touched by {@code reconcileTrueUp}). This is the core of both the FY-close TRUEUP
 * and the termination FINAL_SETTLEMENT: {@code earned − Σ advances-paid}, after negative handling.
 */
class IndividualBonusScheduleServiceTest {

    private final IndividualBonusScheduleService service = new IndividualBonusScheduleService();

    /** A MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP spec with a 50,000/month advance and the given negative policy. */
    private static Spec specWith(NegativeHandling handling) {
        Advance advance = new Advance(Vehicle.MONTHLY_LUMP_SUM, AdvanceType.FIXED, bd(50_000), null, "EMPLOYED_IN_FY");
        TrueUp trueUp = new TrueUp(true, "FY_EARNED_MINUS_ADVANCES", handling);
        Schedule schedule = new Schedule(Cadence.MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP, null, advance, trueUp);
        return new Spec(Basis.OWN_INVOICED_REVENUE, "FISCAL_YEAR_SUM", null,
                new ProRating(true), null, false, null, schedule);
    }

    @Test
    void trueUp_advancesUnderEarned_netsToEarned() {
        // 12 × 50,000 = 600,000 advances; earned 675,000 → true-up 75,000; advances + true-up == earned.
        BigDecimal advancesPaid = bd(50_000).multiply(bd(12));
        BigDecimal trueUp = service.reconcileTrueUp(specWith(NegativeHandling.WRITE_OFF), bd(675_000), advancesPaid);
        assertEquals(0, trueUp.compareTo(bd(75_000)), "true-up should be 75,000 but was " + trueUp);
        assertEquals(0, advancesPaid.add(trueUp).compareTo(bd(675_000)), "advances + true-up must net to earned");
    }

    @Test
    void trueUp_clawback_keepsNegative() {
        // advances 700,000 > earned 675,000 → CLAWBACK keeps −25,000 (a negative Danløn '41' line).
        BigDecimal trueUp = service.reconcileTrueUp(specWith(NegativeHandling.CLAWBACK), bd(675_000), bd(700_000));
        assertEquals(0, trueUp.compareTo(bd(-25_000)), "CLAWBACK true-up should be -25,000 but was " + trueUp);
    }

    @Test
    void trueUp_writeOff_clampsNegativeToZero() {
        // advances 700,000 > earned 675,000 → WRITE_OFF clamps to 0 (already-paid advances stay).
        BigDecimal trueUp = service.reconcileTrueUp(specWith(NegativeHandling.WRITE_OFF), bd(675_000), bd(700_000));
        assertEquals(0, trueUp.compareTo(BigDecimal.ZERO), "WRITE_OFF true-up should be 0 but was " + trueUp);
    }

    @Test
    void trueUp_nullHandling_defaultsToWriteOff() {
        Spec noHandling = new Spec(Basis.OWN_INVOICED_REVENUE, "FISCAL_YEAR_SUM", null,
                new ProRating(true), null, false, null,
                new Schedule(Cadence.MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP, null, null, null));
        BigDecimal trueUp = service.reconcileTrueUp(noHandling, bd(675_000), bd(700_000));
        assertEquals(0, trueUp.compareTo(BigDecimal.ZERO), "default (WRITE_OFF) should clamp to 0 but was " + trueUp);
    }

    @Test
    void finalSettlement_atTermination_earnedToTermMinusAdvancesPaid() {
        // Terminated after 5 employed months: earned-to-term 250,000; advances-paid 5×50,000=250,000 → 0.
        BigDecimal advancesPaid = bd(50_000).multiply(bd(5));
        BigDecimal settlement = service.reconcileTrueUp(specWith(NegativeHandling.WRITE_OFF), bd(250_000), advancesPaid);
        assertEquals(0, settlement.compareTo(BigDecimal.ZERO), "even settlement should be 0 but was " + settlement);
    }

    @Test
    void finalSettlement_advancesExceedEarnedToTerm_clawback() {
        // Production slumped before the leave: earned-to-term 200,000; advances-paid 250,000; CLAWBACK → −50,000.
        BigDecimal settlement = service.reconcileTrueUp(specWith(NegativeHandling.CLAWBACK), bd(200_000), bd(250_000));
        assertEquals(0, settlement.compareTo(bd(-50_000)), "clawback settlement should be -50,000 but was " + settlement);
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }
}
