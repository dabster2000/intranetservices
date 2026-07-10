package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Advance;
import dk.trustworks.intranet.aggregates.bonus.individual.model.AdvanceType;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Basis;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Cadence;
import dk.trustworks.intranet.aggregates.bonus.individual.model.NegativeHandling;
import dk.trustworks.intranet.aggregates.bonus.individual.model.PayoutKind;
import dk.trustworks.intranet.aggregates.bonus.individual.model.ProRating;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Schedule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.aggregates.bonus.individual.model.TrueUp;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Vehicle;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                new ProRating(true), null, false, null, schedule, null);
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
                new Schedule(Cadence.MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP, null, null, null), null);
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

    // --- (#2) bounded-yearly window filter: a settlement pay-month may legitimately fall past effectiveTo ---

    @Test
    void boundedYearly_settlementAfterEffectiveTo_survivesWithinHorizon() {
        // Rule effectiveTo = FY-end (2027-06-30) → windowEnd caps the EARNING period there. The YEARLY
        // settlement pays 2027-07-01 (FY+1 July), AFTER windowEnd — it must NOT be dropped as long as it is
        // within the horizon (the bounded-yearly-loses-July bug).
        LocalDate windowEnd = LocalDate.of(2027, 6, 30);
        LocalDate horizon = LocalDate.of(2027, 12, 31);
        LocalDate settlement = LocalDate.of(2027, 7, 1);
        assertTrue(IndividualBonusScheduleService.survivesWindow(PayoutKind.YEARLY, settlement, windowEnd, horizon),
                "an earned FY's July settlement must survive even when it is after effectiveTo");
        assertTrue(IndividualBonusScheduleService.survivesWindow(PayoutKind.TRUEUP, settlement, windowEnd, horizon));
        assertTrue(IndividualBonusScheduleService.survivesWindow(PayoutKind.FINAL_SETTLEMENT, settlement, windowEnd, horizon));
    }

    @Test
    void boundedYearly_settlementBeyondHorizon_isDropped() {
        // A settlement past the projection horizon is still cut (we do not show pay beyond the horizon).
        LocalDate windowEnd = LocalDate.of(2027, 6, 30);
        LocalDate horizon = LocalDate.of(2027, 6, 20); // horizon before the July pay-month
        assertFalse(IndividualBonusScheduleService.survivesWindow(
                PayoutKind.YEARLY, LocalDate.of(2027, 7, 1), windowEnd, horizon));
    }

    @Test
    void recurringAdvance_isBoundedByEarningWindow_notHorizon() {
        // A recurring advance past the (termination/effectiveTo) window is dropped even though the horizon
        // extends further — only settlements are allowed to trail past the window.
        LocalDate windowEnd = LocalDate.of(2027, 3, 1); // e.g. termination month
        LocalDate horizon = LocalDate.of(2027, 12, 31);
        assertFalse(IndividualBonusScheduleService.survivesWindow(
                PayoutKind.ADVANCE, LocalDate.of(2027, 4, 1), windowEnd, horizon));
        assertTrue(IndividualBonusScheduleService.survivesWindow(
                PayoutKind.ADVANCE, LocalDate.of(2027, 3, 1), windowEnd, horizon));
    }

    // --- (#4) at materialisation, subtract PAID advances only (projected-but-unpaid counts as 0) ---

    @Test
    void advanceContribution_committedOnly_ignoresProjectedUnpaid() {
        // Materialisation path: an un-materialised advance contributes 0 (settlement nets only real advances).
        assertEquals(0, IndividualBonusScheduleService
                .advanceContribution(null, bd(50_000), true).compareTo(BigDecimal.ZERO));
        // Display path: an un-materialised advance contributes its projection (smooth forecast).
        assertEquals(0, IndividualBonusScheduleService
                .advanceContribution(null, bd(50_000), false).compareTo(bd(50_000)));
        // A committed (paid) advance is authoritative in BOTH paths.
        assertEquals(0, IndividualBonusScheduleService
                .advanceContribution(bd(48_000), bd(50_000), true).compareTo(bd(48_000)));
        assertEquals(0, IndividualBonusScheduleService
                .advanceContribution(bd(48_000), bd(50_000), false).compareTo(bd(48_000)));
    }

    // --- (#6) termination tie-break: TERMINATED wins a same-date tie (money-safe) ---

    @Test
    void sameDateTie_terminatedWins() {
        LocalDate d = LocalDate.of(2027, 3, 15);
        UserStatus active = new UserStatus(ConsultantType.CONSULTANT, StatusType.ACTIVE, d, 100, "u1");
        UserStatus terminated = new UserStatus(ConsultantType.CONSULTANT, StatusType.TERMINATED, d, 0, "u1");
        Optional<UserStatus> effective =
                IndividualBonusScheduleService.effectiveStatusAsOf(List.of(active, terminated), d);
        assertTrue(effective.isPresent());
        assertEquals(StatusType.TERMINATED, effective.get().getStatus(),
                "on a same-date ACTIVE+TERMINATED tie the payout path must resolve to TERMINATED");
    }

    @Test
    void laterActiveAfterTermination_wins() {
        // A genuine re-hire (ACTIVE dated AFTER a TERMINATED) still resolves to ACTIVE.
        UserStatus terminated = new UserStatus(ConsultantType.CONSULTANT, StatusType.TERMINATED,
                LocalDate.of(2027, 3, 15), 0, "u1");
        UserStatus rehired = new UserStatus(ConsultantType.CONSULTANT, StatusType.ACTIVE,
                LocalDate.of(2027, 6, 1), 100, "u1");
        Optional<UserStatus> effective = IndividualBonusScheduleService
                .effectiveStatusAsOf(List.of(terminated, rehired), LocalDate.of(2027, 7, 1));
        assertTrue(effective.isPresent());
        assertEquals(StatusType.ACTIVE, effective.get().getStatus());
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }
}
