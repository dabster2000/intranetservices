package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.model.*;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

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
        return new Spec(basis, "FISCAL_YEAR_SUM", tiers, new ProRating(true), null, false, "YPOT", schedule);
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

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
