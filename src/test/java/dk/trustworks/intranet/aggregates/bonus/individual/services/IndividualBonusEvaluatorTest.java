package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.dsl.BonusContext;
import dk.trustworks.intranet.aggregates.bonus.individual.dsl.BonusFormulaEngine;
import dk.trustworks.intranet.aggregates.bonus.individual.model.*;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the tier math and spec validation — no Quarkus boot required (the evaluator and
 * validation have no injected state).
 */
class IndividualBonusEvaluatorTest {

    private final IndividualBonusEvaluator evaluator = new IndividualBonusEvaluator();

    /** Michael's contract tier table: marginal 0 / 20 / 30 / 40 / 45%, open-ended top band. */
    private static List<Tier> michaelTiers() {
        return List.of(
                new Tier(bd(0), bd(1_000_000), bd("0.00")),
                new Tier(bd(1_000_000), bd(1_500_000), bd("0.20")),
                new Tier(bd(1_500_000), bd(2_000_000), bd("0.30")),
                new Tier(bd(2_000_000), bd(2_500_000), bd("0.40")),
                new Tier(bd(2_500_000), null, bd("0.45"))
        );
    }

    @Test
    void fullYear_3M_production_yields_675000() {
        BigDecimal earned = evaluator.computeEarned(
                michaelTiers(), bd(3_000_000), new ProRating(true), 12);
        assertEquals(0, earned.compareTo(bd(675_000)), "Expected 675,000 but got " + earned);
    }

    @Test
    void halfYear_1_5M_production_yields_50000() {
        // (0 + 500k·0.20) · 6/12 = 100,000 · 0.5 = 50,000
        BigDecimal earned = evaluator.computeEarned(
                michaelTiers(), bd(1_500_000), new ProRating(true), 6);
        assertEquals(0, earned.compareTo(bd(50_000)), "Expected 50,000 but got " + earned);
    }

    @Test
    void registeredBillableValue_1250HoursAt1000Dkk_yields15000() {
        List<Tier> tiers = List.of(
                new Tier(bd(0), bd(800_000), bd("0.00")),
                new Tier(bd(800_000), bd(1_100_000), bd("0.025")),
                new Tier(bd(1_100_000), bd(1_400_000), bd("0.05")),
                new Tier(bd(1_400_000), bd(2_000_000), bd("0.075")),
                new Tier(bd(2_000_000), null, bd("0.10"))
        );

        assertDoesNotThrow(() -> service.validateSpec(specWith(tiers, Basis.REGISTERED_BILLABLE_VALUE)));
        // 1,250 hours × 1,000 DKK = 1,250,000 DKK basis:
        // 300,000 × 2.5% + 150,000 × 5% = 15,000 DKK.
        BigDecimal earned = evaluator.computeEarned(
                tiers, bd(1_250_000), new ProRating(false), 12);

        assertEquals(0, earned.compareTo(bd(15_000)), "Expected 15,000 but got " + earned);
    }

    @Test
    void boundary_justBelowFirstPayingBand_yieldsZero() {
        BigDecimal earned = evaluator.computeEarned(
                michaelTiers(), bd(999_999), new ProRating(false), 12);
        assertEquals(0, earned.compareTo(BigDecimal.ZERO), "999,999 should earn 0, got " + earned);
    }

    @Test
    void boundary_atFirstBandEdge_yieldsZero() {
        BigDecimal earned = evaluator.computeEarned(
                michaelTiers(), bd(1_000_000), new ProRating(false), 12);
        assertEquals(0, earned.compareTo(BigDecimal.ZERO), "1,000,000 should earn 0, got " + earned);
    }

    @Test
    void boundary_atSecondBandTop_yields100000() {
        // 500k in the 20% band = 100,000; nothing spills into the 30% band.
        BigDecimal earned = evaluator.computeEarned(
                michaelTiers(), bd(1_500_000), new ProRating(false), 12);
        assertEquals(0, earned.compareTo(bd(100_000)), "1,500,000 should earn 100,000, got " + earned);
    }

    @Test
    void nullTierTable_and_nullBasis_yieldZero() {
        assertEquals(0, evaluator.computeEarned(null, bd(1_000_000), null, 12).compareTo(BigDecimal.ZERO));
        assertEquals(0, evaluator.computeEarned(michaelTiers(), null, null, 12).compareTo(BigDecimal.ZERO));
    }

    // --- validation (IndividualBonusService.validateSpec is stateless) ---

    private final IndividualBonusService service = new IndividualBonusService();

    private static Spec specWith(List<Tier> tiers, Basis basis) {
        Schedule schedule = new Schedule(Cadence.YEARLY, new Yearly(1), null, null);
        return new Spec(basis, "FISCAL_YEAR_SUM", tiers, new ProRating(true), null, false, "YPOT", schedule, null);
    }

    @Test
    void validSpec_passesValidation() {
        assertDoesNotThrow(() -> service.validateSpec(specWith(michaelTiers(), Basis.OWN_INVOICED_REVENUE)));
    }

    @Test
    void nonContiguousTierBands_rejected() {
        List<Tier> gapped = List.of(
                new Tier(bd(0), bd(1_000_000), bd("0.00")),
                new Tier(bd(1_200_000), bd(1_500_000), bd("0.20")) // gap: starts at 1.2M not 1.0M
        );
        assertThrows(BadRequestException.class,
                () -> service.validateSpec(specWith(gapped, Basis.OWN_INVOICED_REVENUE)));
    }

    @Test
    void rateAboveOne_rejected() {
        List<Tier> badRate = List.of(
                new Tier(bd(0), bd(1_000_000), bd("0.00")),
                new Tier(bd(1_000_000), null, bd("1.50")) // rate > 1
        );
        assertThrows(BadRequestException.class,
                () -> service.validateSpec(specWith(badRate, Basis.OWN_INVOICED_REVENUE)));
    }

    @Test
    void unsupportedCompanyBasis_rejected() {
        assertThrows(BadRequestException.class,
                () -> service.validateSpec(specWith(michaelTiers(), Basis.COMPANY_EBITDA)));
    }

    @Test
    void nonPositiveCap_rejected() {
        // A zero/negative cap would silently clamp every earned amount to ≤ 0 and suppress pay — reject it.
        Spec zeroCap = new Spec(Basis.OWN_INVOICED_REVENUE, "FISCAL_YEAR_SUM", michaelTiers(),
                new ProRating(true), BigDecimal.ZERO, false, "YPOT",
                new Schedule(Cadence.YEARLY, new Yearly(1), null, null), null);
        assertThrows(BadRequestException.class, () -> service.validateSpec(zeroCap));
    }

    // --- formula escape hatch: write-time validation (IndividualBonusService.validateSpec) ---

    @Test
    void formulaSpec_validFormula_passesValidation() {
        assertDoesNotThrow(() -> serviceWithEngine().validateSpec(
                formulaSpec(Basis.OWN_INVOICED_REVENUE, michaelTiers(), "tier(production)", null)));
    }

    @Test
    void formulaSpec_emptyTierTableAllowed_whenFormulaPresent() {
        // A formula may compute earned without a tier table (it need not call tier()).
        assertDoesNotThrow(() -> serviceWithEngine().validateSpec(
                formulaSpec(Basis.OWN_INVOICED_REVENUE, null, "min(production, 100000)", null)));
    }

    @Test
    void formulaSpec_syntaxError_rejected() {
        assertThrows(BadRequestException.class, () -> serviceWithEngine().validateSpec(
                formulaSpec(Basis.OWN_INVOICED_REVENUE, michaelTiers(), "tier(production", null)));
    }

    @Test
    void formulaSpec_disallowedVariable_rejected() {
        assertThrows(BadRequestException.class, () -> serviceWithEngine().validateSpec(
                formulaSpec(Basis.OWN_INVOICED_REVENUE, michaelTiers(), "password * 2", null)));
    }

    @Test
    void formulaSpec_fixedAmountBasis_rejected() {
        assertThrows(BadRequestException.class, () -> serviceWithEngine().validateSpec(
                formulaSpec(Basis.FIXED_AMOUNT, null, "tier(production)", null)));
    }

    @Test
    void formulaSpec_overLength_rejected() {
        String tooLong = "1 +".repeat(700) + " production"; // > 2000 chars
        assertThrows(BadRequestException.class, () -> serviceWithEngine().validateSpec(
                formulaSpec(Basis.OWN_INVOICED_REVENUE, michaelTiers(), tooLong, null)));
    }

    // --- formula escape hatch: earned() routing (IndividualBonusEvaluator.earned) ---

    @Test
    void earned_withFormula_reproducesTierAmount() {
        Spec spec = formulaSpec(Basis.OWN_INVOICED_REVENUE, michaelTiers(), "tier(production)", null);
        BigDecimal earned = evaluatorWithEngine().earned(spec, ctx(bd(3_000_000), 12, michaelTiers()));
        assertEquals(0, earned.compareTo(bd(675_000)), "formula earned should be 675,000 but was " + earned);
    }

    @Test
    void earned_withFormula_capClamps() {
        Spec spec = formulaSpec(Basis.OWN_INVOICED_REVENUE, michaelTiers(), "tier(production)", bd(500_000));
        BigDecimal earned = evaluatorWithEngine().earned(spec, ctx(bd(3_000_000), 12, michaelTiers()));
        assertEquals(0, earned.compareTo(bd(500_000)), "cap should clamp to 500,000 but was " + earned);
    }

    @Test
    void earned_noFormula_usesDeclarativeTierMath() {
        // formula null → the marginal tier table with pro-rating is used (unchanged behaviour).
        Spec spec = specWith(michaelTiers(), Basis.OWN_INVOICED_REVENUE); // proRating(true), formula null
        BigDecimal earned = new IndividualBonusEvaluator().earned(spec, ctx(bd(3_000_000), 12, michaelTiers()));
        assertEquals(0, earned.compareTo(bd(675_000)), "declarative earned should be 675,000 but was " + earned);
    }

    private static IndividualBonusService serviceWithEngine() {
        IndividualBonusService svc = new IndividualBonusService();
        svc.formulaEngine = new BonusFormulaEngine();
        return svc;
    }

    private static IndividualBonusEvaluator evaluatorWithEngine() {
        IndividualBonusEvaluator ev = new IndividualBonusEvaluator();
        ev.formulaEngine = new BonusFormulaEngine();
        return ev;
    }

    private static Spec formulaSpec(Basis basis, List<Tier> tiers, String formula, BigDecimal cap) {
        Schedule schedule = new Schedule(Cadence.YEARLY, new Yearly(1), null, null);
        return new Spec(basis, "FISCAL_YEAR_SUM", tiers, new ProRating(false), cap, false, "YPOT", schedule, formula);
    }

    private static BonusContext ctx(BigDecimal production, int months, List<Tier> tiers) {
        Map<String, BigDecimal> facts = new HashMap<>();
        facts.put("production", production);
        return new BonusContext(tiers, months, 2026, production, facts::get);
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
