package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.dsl.BonusContext;
import dk.trustworks.intranet.aggregates.bonus.individual.dsl.BonusFormulaEngine;
import dk.trustworks.intranet.aggregates.bonus.individual.dsl.TierMath;
import dk.trustworks.intranet.aggregates.bonus.individual.model.ProRating;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Tier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
     * The sandboxed JEXL engine for the optional {@code formula} escape hatch. Only consulted by
     * {@link #earned(Spec, BonusContext)} when a formula is present; the pure {@link #computeEarned} tier
     * math never touches it, so {@code new IndividualBonusEvaluator()} stays usable in boot-free unit tests.
     */
    @Inject
    BonusFormulaEngine formulaEngine;

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
        BigDecimal bonus = TierMath.marginalSum(tiers, basisAmount);
        if (proRating != null && proRating.byMonthsEmployedInFy()) {
            bonus = bonus
                    .multiply(BigDecimal.valueOf(monthsEmployedInFy))
                    .divide(BigDecimal.valueOf(MONTHS_IN_FY), 0, RoundingMode.HALF_UP);
        }
        return bonus;
    }

    /**
     * The SINGLE earned-computation entry point shared by the projection ({@code IndividualBonusScheduleService})
     * and the standalone FY computation ({@code IndividualBonusService#computeAmountForFy}) — so the formula
     * escape hatch and the declarative tier math live behind one door.
     * <p>
     * When {@code spec.formula()} is present, the sandboxed JEXL engine fully computes the FY earned scalar
     * against the curated facts in {@code ctx}; the formula owns pro-rating ({@code monthsEmployed} is injected),
     * so {@code proRating} is deliberately NOT applied — that avoids double pro-rating. When absent, the marginal
     * tier table is used with pro-rating as before. Any {@code cap} is clamped last in both paths. A formula
     * failure throws {@link dk.trustworks.intranet.aggregates.bonus.individual.dsl.BonusFormulaException}.
     * <p>
     * {@code FIXED_AMOUNT} is schedule-driven and never reaches here — callers short-circuit it to 0.
     */
    public BigDecimal earned(Spec spec, BonusContext ctx) {
        BigDecimal earned;
        String formula = spec.formula();
        if (formula != null && !formula.isBlank()) {
            earned = formulaEngine.evaluate(spec, ctx);
        } else {
            earned = computeEarned(spec.tierTable(), ctx.basisAmount(), spec.proRating(), ctx.monthsEmployed());
        }
        if (spec.cap() != null && earned.compareTo(spec.cap()) > 0) {
            earned = spec.cap();
        }
        return earned;
    }
}
