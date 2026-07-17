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

    // =====================================================================
    // Hybrid per-leader split (spec §4)
    // =====================================================================

    /**
     * Splits a team's payable pool between its leaders proportionally to
     * {@code weight_L = ownWindowPoints_L × monthsAsLeader_L}. Returns one slice per leader in the
     * input order, each in {@code [0, 1]} and summing to 1 (barring the all-zero edge case).
     *
     * <ul>
     *   <li>A single leader always gets the whole slice ({@code 1.0}).</li>
     *   <li>When {@code ΣW = 0} (e.g. every leader sat at/below the utilization threshold) but the
     *       team still has a payable pool, the split falls back to being proportional to the
     *       months-as-leader counts, so leaderless-driven proration is still honoured.</li>
     *   <li>When both the weights and the month counts are all zero, every slice is {@code 0}
     *       (the covered fraction is zero anyway, so nothing is payable).</li>
     * </ul>
     *
     * Negative weights/months are clamped to zero before the ratio. Pure logic — unit-tested
     * without a DB.
     *
     * @param weights per-leader {@code ownWindowPoints × monthsAsLeader} (same order as {@code months})
     * @param months  per-leader considered-months-as-leader counts
     * @return per-leader slices in input order
     * @throws IllegalArgumentException when the two arrays differ in length
     */
    public static double[] hybridSlices(double[] weights, int[] months) {
        if (weights.length != months.length) {
            throw new IllegalArgumentException("weights and months must have the same length");
        }
        int n = weights.length;
        double[] slices = new double[n];
        if (n == 0) return slices;
        if (n == 1) {
            slices[0] = 1.0;
            return slices;
        }

        double sumWeights = 0.0;
        for (double w : weights) sumWeights += Math.max(w, 0.0);
        if (sumWeights > 0.0) {
            for (int i = 0; i < n; i++) slices[i] = Math.max(weights[i], 0.0) / sumWeights;
            return slices;
        }

        // ΣW = 0 fallback: proportional to months-as-leader.
        long sumMonths = 0;
        for (int m : months) sumMonths += Math.max(m, 0);
        if (sumMonths > 0) {
            for (int i = 0; i < n; i++) slices[i] = Math.max(months[i], 0) / (double) sumMonths;
        }
        return slices;
    }

    /**
     * Recomposes a team's fiscal-year utilization as the month-count-weighted average over each
     * leader window's <em>effective</em> utilization (admin override applied) plus the measured
     * monthly utilizations of any leaderless (unassigned) months.
     *
     * <pre>teamUtil = (Σ effectiveUtil_L × windowMonths_L + Σ unassignedUtil_m) / (Σ windowMonths_L + #unassigned)</pre>
     *
     * With no overrides each window's effective utilization is the equal-weight mean of its months,
     * so this collapses to the plain equal-weight monthly average (backward compatible). With a sole
     * full-coverage leader whose utilization is overridden it equals the override exactly. Only
     * months that actually carry member data should be passed in (empty months have no utilization).
     * Returns {@code 0} when there are no contributing months. Pure logic — unit-tested without a DB.
     *
     * @param windowEffectiveUtil per-leader-window effective utilization (override applied)
     * @param windowMonthCounts   per-leader-window count of data-carrying months (same order)
     * @param unassignedMonthlyUtil measured utilizations of leaderless data-carrying months
     * @return the recomposed team utilization
     * @throws IllegalArgumentException when the two window arrays differ in length
     */
    public static double recomposedUtilization(double[] windowEffectiveUtil, int[] windowMonthCounts,
                                               double[] unassignedMonthlyUtil) {
        if (windowEffectiveUtil.length != windowMonthCounts.length) {
            throw new IllegalArgumentException("window arrays must have the same length");
        }
        double weightedSum = 0.0;
        long totalMonths = 0;
        for (int i = 0; i < windowEffectiveUtil.length; i++) {
            int count = Math.max(windowMonthCounts[i], 0);
            weightedSum += windowEffectiveUtil[i] * count;
            totalMonths += count;
        }
        for (double util : unassignedMonthlyUtil) {
            weightedSum += util;
            totalMonths += 1;
        }
        if (totalMonths == 0) return 0.0;
        return weightedSum / totalMonths;
    }
}
