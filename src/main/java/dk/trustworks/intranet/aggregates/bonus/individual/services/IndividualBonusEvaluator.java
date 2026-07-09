package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.model.ProRating;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Tier;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure tier math shared by both the projection and materialisation layers.
 * <p>
 * Marginal tier sum (each rate applies only to its own slice of the basis) plus optional
 * pro-rating by months employed in the fiscal year. Has no injected state, so it is trivially
 * unit-testable via {@code new IndividualBonusEvaluator()} without booting Quarkus.
 */
@ApplicationScoped
public class IndividualBonusEvaluator {

    private static final int MONTHS_IN_FY = 12;

    /**
     * Compute the earned bonus for a basis amount against a marginal tier table.
     *
     * @param tiers             marginal bands ({@code to == null} = open-ended top band); null/empty → 0
     * @param basisAmount       the resolved basis (e.g. own FY production); null → 0
     * @param proRating         optional pro-rating (null → no pro-rating)
     * @param monthsEmployedInFy months the employee was ACTIVE in the FY (used only if pro-rating)
     * @return gross earned amount, rounded HALF_UP to whole DKK when pro-rated
     */
    public BigDecimal computeEarned(List<Tier> tiers, BigDecimal basisAmount,
                                    ProRating proRating, int monthsEmployedInFy) {
        BigDecimal basis = basisAmount == null ? BigDecimal.ZERO : basisAmount;
        BigDecimal bonus = BigDecimal.ZERO;

        if (tiers != null) {
            for (Tier t : tiers) {
                if (t == null || t.rate() == null || t.from() == null) continue;
                BigDecimal lo = t.from();
                BigDecimal hi = (t.to() == null) ? basis : basis.min(t.to());
                if (hi.compareTo(lo) > 0) {
                    bonus = bonus.add(hi.subtract(lo).multiply(t.rate()));
                }
            }
        }

        if (proRating != null && proRating.byMonthsEmployedInFy()) {
            bonus = bonus
                    .multiply(BigDecimal.valueOf(monthsEmployedInFy))
                    .divide(BigDecimal.valueOf(MONTHS_IN_FY), 0, RoundingMode.HALF_UP);
        }
        return bonus;
    }
}
