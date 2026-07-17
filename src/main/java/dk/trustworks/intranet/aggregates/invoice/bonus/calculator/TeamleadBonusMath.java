package dk.trustworks.intranet.aggregates.invoice.bonus.calculator;

import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadBonusConfigDTO;

/**
 * Pure, DB-free teamlead-bonus math (unit-tested without CDI). Every method operates on primitives
 * (or the plain {@link TeamleadBonusConfigDTO} value carrier) and performs no rounding — callers
 * round for display/persistence. See the reference example in the spec §0 for the canonical numbers.
 */
public final class TeamleadBonusMath {

    /** Points multiplier applied to utilization above the threshold. */
    public static final double POINTS_MULTIPLIER = 5.0;
    /** Points are expressed in "hundredths" when priced (points × 100). */
    public static final double POINTS_SCALE = 100.0;
    /** Months in a full fiscal year (for proration). */
    public static final int MONTHS_IN_YEAR = 12;

    private TeamleadBonusMath() {}

    /**
     * Team factor bracket: {@code >= tier3From → tier3}, {@code >= tier2From → tier2}, else tier1.
     */
    public static double teamFactor(double avgTeamSize, TeamleadBonusConfigDTO config) {
        if (avgTeamSize >= config.teamFactorTier3From()) return config.teamFactorTier3();
        if (avgTeamSize >= config.teamFactorTier2From()) return config.teamFactorTier2();
        return config.teamFactorTier1();
    }

    /**
     * {@code points = MAX(teamUtil − minUtilThreshold, 0) × 5 × teamFactor}. Utilization at or below
     * the threshold yields 0 points (silently ineligible for the pool).
     */
    public static double rawPoints(double teamUtil, double minUtilThreshold, double teamFactor) {
        return Math.max(teamUtil - minUtilThreshold, 0.0) * POINTS_MULTIPLIER * teamFactor;
    }

    /** {@code poolAmount = MAX(poolBasis, 0) × poolSharePercent}. */
    public static double poolAmount(double poolBasis, double poolSharePercent) {
        return Math.max(poolBasis, 0.0) * poolSharePercent;
    }

    /**
     * {@code pricePerPoint = poolAmount / (Σpoints × 100)}. Returns 0 when there is no pool or no
     * points, so downstream pool shares collapse to 0 rather than dividing by zero.
     */
    public static double pricePerPoint(double poolAmount, double sumRawPoints) {
        if (poolAmount <= 0.0 || sumRawPoints <= 0.0) return 0.0;
        return poolAmount / (sumRawPoints * POINTS_SCALE);
    }

    /** {@code poolShare = points × 100 × pricePerPoint}. */
    public static double poolShare(double rawPoints, double pricePerPoint) {
        return rawPoints * POINTS_SCALE * pricePerPoint;
    }

    /** {@code adjustedPoolBonus = poolShare / 12 × monthsAsLeader}. */
    public static double adjustedPoolBonus(double poolShare, int monthsAsLeader) {
        return (poolShare / MONTHS_IN_YEAR) * monthsAsLeader;
    }

    /** {@code proratedThreshold = annualThreshold × monthsAsLeader / 12}. */
    public static double proratedThreshold(double annualThreshold, int monthsAsLeader) {
        return annualThreshold * ((double) monthsAsLeader / MONTHS_IN_YEAR);
    }

    /**
     * {@code productionBonus = MAX((ownRevenue − proratedThreshold) × commissionPercent, 0)}.
     * Not gated by the pool floor — production is paid even when the pool is 0.
     */
    public static double productionBonus(double ownRevenue, double proratedThreshold, double commissionPercent) {
        return Math.max((ownRevenue - proratedThreshold) * commissionPercent, 0.0);
    }

    /**
     * {@code total = adjustedPoolBonus + productionBonus + splitBonus − prepaidTotal}. May be
     * negative; callers clamp the displayed payout with {@link #clampAtZero(double)}.
     */
    public static double totalBonus(double adjustedPoolBonus, double productionBonus,
                                    double splitBonus, double prepaidTotal) {
        return adjustedPoolBonus + productionBonus + splitBonus - prepaidTotal;
    }

    /** Never let a displayed payout / lump sum go negative. */
    public static double clampAtZero(double value) {
        return Math.max(value, 0.0);
    }

    /**
     * Deduct the prepaid amount from the (clamped) components in order pool → production → split.
     * Any remainder that cannot be covered is reported in {@link PrepaidAllocation#unallocated()}.
     */
    public static PrepaidAllocation allocatePrepaid(double poolAmount, double productionAmount,
                                                    double splitAmount, double prepaid) {
        double remaining = Math.max(prepaid, 0.0);
        double pool = clampAtZero(poolAmount);
        double prod = clampAtZero(productionAmount);
        double split = clampAtZero(splitAmount);

        double fromPool = Math.min(pool, remaining);
        pool -= fromPool;
        remaining -= fromPool;

        double fromProd = Math.min(prod, remaining);
        prod -= fromProd;
        remaining -= fromProd;

        double fromSplit = Math.min(split, remaining);
        split -= fromSplit;
        remaining -= fromSplit;

        return new PrepaidAllocation(pool, prod, split, remaining);
    }

    /** Result of {@link #allocatePrepaid}: the component amounts remaining after prepaid deduction. */
    public record PrepaidAllocation(double poolAmount, double productionAmount,
                                    double splitAmount, double unallocated) {
        /** Sum of the three payable components (already prepaid-reduced, never negative). */
        public double total() {
            return poolAmount + productionAmount + splitAmount;
        }
    }
}
