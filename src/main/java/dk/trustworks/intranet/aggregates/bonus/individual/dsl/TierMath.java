package dk.trustworks.intranet.aggregates.bonus.individual.dsl;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Tier;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pure marginal-tier-sum math — the single source of truth shared by the declarative evaluator
 * ({@code IndividualBonusEvaluator.computeEarned}) and the {@code tier(x)} formula helper
 * ({@link BonusFunctions}). Each band's rate applies only to its own slice of the amount.
 * <p>
 * No pro-rating, no cap — those are applied by the caller. Stateless and side-effect free.
 */
public final class TierMath {

    private TierMath() {
    }

    /**
     * Marginal sum of {@code tiers} over {@code amount}: for each band {@code [from, to)} add
     * {@code (min(amount, to) - from) * rate} for the slice above {@code from}. A {@code null} {@code to}
     * is an open-ended top band. {@code null}/empty {@code tiers} or {@code null} {@code amount} → 0.
     */
    public static BigDecimal marginalSum(List<Tier> tiers, BigDecimal amount) {
        BigDecimal basis = amount == null ? BigDecimal.ZERO : amount;
        BigDecimal sum = BigDecimal.ZERO;
        if (tiers == null) return sum;
        for (Tier t : tiers) {
            if (t == null || t.rate() == null || t.from() == null) continue;
            BigDecimal lo = t.from();
            BigDecimal hi = (t.to() == null) ? basis : basis.min(t.to());
            if (hi.compareTo(lo) > 0) {
                sum = sum.add(hi.subtract(lo).multiply(t.rate()));
            }
        }
        return sum;
    }
}
