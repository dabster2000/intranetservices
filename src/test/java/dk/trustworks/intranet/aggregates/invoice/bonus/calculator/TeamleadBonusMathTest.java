package dk.trustworks.intranet.aggregates.invoice.bonus.calculator;

import dk.trustworks.intranet.aggregates.invoice.bonus.calculator.TeamleadBonusMath.PrepaidAllocation;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadBonusConfigDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-math unit tests for {@link TeamleadBonusMath} (no DB, no CDI).
 * Reference numbers come from the FY23/24 spreadsheet in the spec §0:
 * pool = 5% of 7,700,431 → 385,021.55; threshold 1.1M; commission 20%; minUtil 0.65;
 * factors 1 / 1.5 / 2 at breakpoints 7 / 11.
 */
class TeamleadBonusMathTest {

    /** Currency-level tolerance (reference numbers are given at 2 decimals). */
    private static final double DELTA = 0.01;
    /** Tight tolerance for unrounded intermediate values. */
    private static final double EXACT = 1e-9;

    /** Code defaults match the FY23/24 reference parameters exactly. */
    private static final TeamleadBonusConfigDTO CONFIG = TeamleadBonusConfigDTO.codeDefaults(2023);

    private static final double OVERSKUD = 7_700_431.0;
    private static final double SUM_RAW_POINTS = 5.72075; // Σpoints×100 = 572.075 across all teams

    // --- Team factor brackets ---

    @Test
    void teamFactor_stephanTeamSize_isTier2() {
        assertEquals(1.5, TeamleadBonusMath.teamFactor(9.62, CONFIG), EXACT);
    }

    @Test
    void teamFactor_buchholdtTeamSize_isTier1() {
        assertEquals(1.0, TeamleadBonusMath.teamFactor(6.24, CONFIG), EXACT);
    }

    @Test
    void teamFactor_exactlyAtTier2Breakpoint_isTier2() {
        assertEquals(1.5, TeamleadBonusMath.teamFactor(7.0, CONFIG), EXACT);
    }

    @Test
    void teamFactor_justBelowTier2Breakpoint_isTier1() {
        assertEquals(1.0, TeamleadBonusMath.teamFactor(6.99, CONFIG), EXACT);
    }

    @Test
    void teamFactor_exactlyAtTier3Breakpoint_isTier3() {
        assertEquals(2.0, TeamleadBonusMath.teamFactor(11.0, CONFIG), EXACT);
    }

    @Test
    void teamFactor_justBelowTier3Breakpoint_isTier2() {
        assertEquals(1.5, TeamleadBonusMath.teamFactor(10.99, CONFIG), EXACT);
    }

    // --- Raw points ---

    @Test
    void rawPoints_stephan_matchesReference() {
        // (0.8547 − 0.65) × 5 × 1.5 = 1.53525
        assertEquals(1.53525, TeamleadBonusMath.rawPoints(0.8547, 0.65, 1.5), EXACT);
    }

    @Test
    void rawPoints_buchholdt_matchesReference() {
        // (0.7025 − 0.65) × 5 × 1.0 = 0.2625
        assertEquals(0.2625, TeamleadBonusMath.rawPoints(0.7025, 0.65, 1.0), EXACT);
    }

    @Test
    void rawPoints_utilizationAtThreshold_isZero() {
        assertEquals(0.0, TeamleadBonusMath.rawPoints(0.65, 0.65, 1.5), EXACT);
    }

    @Test
    void rawPoints_utilizationBelowThreshold_isZero() {
        assertEquals(0.0, TeamleadBonusMath.rawPoints(0.60, 0.65, 2.0), EXACT);
    }

    // --- Pool amount ---

    @Test
    void poolAmount_referenceOverskud_isFivePercent() {
        // 5% of 7,700,431 = 385,021.55
        assertEquals(385_021.55, TeamleadBonusMath.poolAmount(OVERSKUD, CONFIG.poolSharePercent()), DELTA);
    }

    @Test
    void poolAmount_negativeBasis_clampsToZero() {
        assertEquals(0.0, TeamleadBonusMath.poolAmount(-1_000_000.0, CONFIG.poolSharePercent()), EXACT);
    }

    // --- Price per point ---

    @Test
    void pricePerPoint_matchesReference() {
        double poolAmount = TeamleadBonusMath.poolAmount(OVERSKUD, CONFIG.poolSharePercent());
        // 385,021.55 / 572.075 = 673.0263514399337
        assertEquals(673.0263514399337, TeamleadBonusMath.pricePerPoint(poolAmount, SUM_RAW_POINTS), EXACT);
    }

    @Test
    void pricePerPoint_zeroPool_isZero() {
        assertEquals(0.0, TeamleadBonusMath.pricePerPoint(0.0, SUM_RAW_POINTS), EXACT);
    }

    @Test
    void pricePerPoint_zeroPoints_isZeroInsteadOfDivideByZero() {
        assertEquals(0.0, TeamleadBonusMath.pricePerPoint(385_021.55, 0.0), EXACT);
    }

    // --- Pool share & proration ---

    @Test
    void poolShare_stephan_matchesReference() {
        double pricePerPoint = TeamleadBonusMath.pricePerPoint(
                TeamleadBonusMath.poolAmount(OVERSKUD, CONFIG.poolSharePercent()), SUM_RAW_POINTS);
        assertEquals(103_326.37, TeamleadBonusMath.poolShare(1.53525, pricePerPoint), DELTA);
    }

    @Test
    void poolShare_buchholdt_matchesReference() {
        double pricePerPoint = TeamleadBonusMath.pricePerPoint(
                TeamleadBonusMath.poolAmount(OVERSKUD, CONFIG.poolSharePercent()), SUM_RAW_POINTS);
        assertEquals(17_666.94, TeamleadBonusMath.poolShare(0.2625, pricePerPoint), DELTA);
    }

    @Test
    void adjustedPoolBonus_fullYear_equalsPoolShare() {
        assertEquals(103_326.37, TeamleadBonusMath.adjustedPoolBonus(103_326.37, 12), DELTA);
    }

    @Test
    void adjustedPoolBonus_buchholdtFourMonths_matchesReference() {
        double pricePerPoint = TeamleadBonusMath.pricePerPoint(
                TeamleadBonusMath.poolAmount(OVERSKUD, CONFIG.poolSharePercent()), SUM_RAW_POINTS);
        double poolShare = TeamleadBonusMath.poolShare(0.2625, pricePerPoint);
        // 17,666.94 / 12 × 4 = 5,888.98
        assertEquals(5_888.98, TeamleadBonusMath.adjustedPoolBonus(poolShare, 4), DELTA);
    }

    // --- Production bonus ---

    @Test
    void proratedThreshold_fullYear_isAnnualThreshold() {
        assertEquals(1_100_000.0,
                TeamleadBonusMath.proratedThreshold(CONFIG.productionThresholdAnnual(), 12), EXACT);
    }

    @Test
    void proratedThreshold_fourMonths_isOneThird() {
        assertEquals(1_100_000.0 * 4 / 12,
                TeamleadBonusMath.proratedThreshold(CONFIG.productionThresholdAnnual(), 4), EXACT);
    }

    @Test
    void productionBonus_stephan_matchesReference() {
        // (1,121,769 − 1,100,000) × 0.2 = 4,353.80
        double threshold = TeamleadBonusMath.proratedThreshold(CONFIG.productionThresholdAnnual(), 12);
        assertEquals(4_353.80, TeamleadBonusMath.productionBonus(
                1_121_769.0, threshold, CONFIG.productionCommissionPercent()), DELTA);
    }

    @Test
    void productionBonus_buchholdtProrated_matchesReference() {
        // (647,691 − 1,100,000×4/12) × 0.2 = 56,204.87
        double threshold = TeamleadBonusMath.proratedThreshold(CONFIG.productionThresholdAnnual(), 4);
        assertEquals(56_204.87, TeamleadBonusMath.productionBonus(
                647_691.0, threshold, CONFIG.productionCommissionPercent()), DELTA);
    }

    @Test
    void productionBonus_belowThreshold_isZero() {
        double threshold = TeamleadBonusMath.proratedThreshold(CONFIG.productionThresholdAnnual(), 12);
        assertEquals(0.0, TeamleadBonusMath.productionBonus(
                900_000.0, threshold, CONFIG.productionCommissionPercent()), EXACT);
    }

    // --- Total bonus ---

    @Test
    void totalBonus_stephan_matchesReference() {
        // 103,326.37 + 4,353.80 + 25,000 − 25,000 = 107,680.17
        assertEquals(107_680.17,
                TeamleadBonusMath.totalBonus(103_326.37, 4_353.80, 25_000.0, 25_000.0), DELTA);
    }

    @Test
    void totalBonus_buchholdt_matchesReference() {
        // 5,888.98 + 56,204.87 + 0 − 20,000 = 42,093.85
        assertEquals(42_093.85,
                TeamleadBonusMath.totalBonus(5_888.98, 56_204.87, 0.0, 20_000.0), DELTA);
    }

    @Test
    void totalBonus_mayGoNegative_andClampsAtZeroForPayout() {
        double total = TeamleadBonusMath.totalBonus(0.0, 1_000.0, 0.0, 5_000.0);
        assertEquals(-4_000.0, total, EXACT);
        assertEquals(0.0, TeamleadBonusMath.clampAtZero(total), EXACT);
    }

    // --- Prepaid allocation (pool → production → split ordering) ---

    @Test
    void allocatePrepaid_coveredByPoolAlone() {
        PrepaidAllocation a = TeamleadBonusMath.allocatePrepaid(10_000.0, 5_000.0, 25_000.0, 8_000.0);
        assertEquals(2_000.0, a.poolAmount(), EXACT);
        assertEquals(5_000.0, a.productionAmount(), EXACT);
        assertEquals(25_000.0, a.splitAmount(), EXACT);
        assertEquals(0.0, a.unallocated(), EXACT);
        assertEquals(32_000.0, a.total(), EXACT);
    }

    @Test
    void allocatePrepaid_spillsFromPoolIntoProduction() {
        PrepaidAllocation a = TeamleadBonusMath.allocatePrepaid(10_000.0, 5_000.0, 25_000.0, 12_000.0);
        assertEquals(0.0, a.poolAmount(), EXACT);
        assertEquals(3_000.0, a.productionAmount(), EXACT);
        assertEquals(25_000.0, a.splitAmount(), EXACT);
        assertEquals(0.0, a.unallocated(), EXACT);
    }

    @Test
    void allocatePrepaid_spillsThroughToSplit() {
        PrepaidAllocation a = TeamleadBonusMath.allocatePrepaid(10_000.0, 5_000.0, 25_000.0, 20_000.0);
        assertEquals(0.0, a.poolAmount(), EXACT);
        assertEquals(0.0, a.productionAmount(), EXACT);
        assertEquals(20_000.0, a.splitAmount(), EXACT);
        assertEquals(0.0, a.unallocated(), EXACT);
    }

    @Test
    void allocatePrepaid_exceedingAllComponents_reportsUnallocatedRemainder() {
        PrepaidAllocation a = TeamleadBonusMath.allocatePrepaid(10_000.0, 5_000.0, 25_000.0, 45_000.0);
        assertEquals(0.0, a.poolAmount(), EXACT);
        assertEquals(0.0, a.productionAmount(), EXACT);
        assertEquals(0.0, a.splitAmount(), EXACT);
        assertEquals(5_000.0, a.unallocated(), EXACT);
        assertEquals(0.0, a.total(), EXACT);
    }

    @Test
    void allocatePrepaid_negativeComponentsAreClampedBeforeAllocation() {
        PrepaidAllocation a = TeamleadBonusMath.allocatePrepaid(-1_000.0, 5_000.0, 0.0, 3_000.0);
        assertEquals(0.0, a.poolAmount(), EXACT);
        assertEquals(2_000.0, a.productionAmount(), EXACT);
        assertEquals(0.0, a.splitAmount(), EXACT);
        assertEquals(0.0, a.unallocated(), EXACT);
    }

    @Test
    void allocatePrepaid_zeroPrepaid_leavesComponentsUntouched() {
        PrepaidAllocation a = TeamleadBonusMath.allocatePrepaid(10_000.0, 5_000.0, 25_000.0, 0.0);
        assertEquals(10_000.0, a.poolAmount(), EXACT);
        assertEquals(5_000.0, a.productionAmount(), EXACT);
        assertEquals(25_000.0, a.splitAmount(), EXACT);
        assertEquals(0.0, a.unallocated(), EXACT);
    }

    // --- End-to-end reference rows (pure math chain) ---

    @Test
    void referenceExample_stephan_endToEnd() {
        double poolAmount = TeamleadBonusMath.poolAmount(OVERSKUD, CONFIG.poolSharePercent());
        double pricePerPoint = TeamleadBonusMath.pricePerPoint(poolAmount, SUM_RAW_POINTS);
        double factor = TeamleadBonusMath.teamFactor(9.62, CONFIG);
        double points = TeamleadBonusMath.rawPoints(0.8547, CONFIG.minUtilThreshold(), factor);
        double poolShare = TeamleadBonusMath.poolShare(points, pricePerPoint);
        double adjustedPool = TeamleadBonusMath.adjustedPoolBonus(poolShare, 12);
        double production = TeamleadBonusMath.productionBonus(1_121_769.0,
                TeamleadBonusMath.proratedThreshold(CONFIG.productionThresholdAnnual(), 12),
                CONFIG.productionCommissionPercent());
        double total = TeamleadBonusMath.totalBonus(adjustedPool, production, 25_000.0, 25_000.0);

        assertEquals(1.53525, points, EXACT);
        assertEquals(103_326.37, adjustedPool, DELTA);
        assertEquals(4_353.80, production, DELTA);
        assertEquals(107_680.17, total, DELTA);
    }

    @Test
    void referenceExample_buchholdt_endToEnd() {
        double poolAmount = TeamleadBonusMath.poolAmount(OVERSKUD, CONFIG.poolSharePercent());
        double pricePerPoint = TeamleadBonusMath.pricePerPoint(poolAmount, SUM_RAW_POINTS);
        double factor = TeamleadBonusMath.teamFactor(6.24, CONFIG);
        double points = TeamleadBonusMath.rawPoints(0.7025, CONFIG.minUtilThreshold(), factor);
        double poolShare = TeamleadBonusMath.poolShare(points, pricePerPoint);
        double adjustedPool = TeamleadBonusMath.adjustedPoolBonus(poolShare, 4);
        double production = TeamleadBonusMath.productionBonus(647_691.0,
                TeamleadBonusMath.proratedThreshold(CONFIG.productionThresholdAnnual(), 4),
                CONFIG.productionCommissionPercent());
        double total = TeamleadBonusMath.totalBonus(adjustedPool, production, 0.0, 20_000.0);

        assertEquals(0.2625, points, EXACT);
        assertEquals(17_666.94, poolShare, DELTA);
        assertEquals(5_888.98, adjustedPool, DELTA);
        assertEquals(56_204.87, production, DELTA);
        assertEquals(42_093.85, total, DELTA);
    }
}
