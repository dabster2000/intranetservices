package dk.trustworks.intranet.aggregates.bonus.individual.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * The declarative bonus spec — the immutable value object persisted (as JSON text) in
 * {@code individual_bonus_rule.spec} and evaluated at runtime.
 * <p>
 * Nullable fields are legitimate: {@code tierTable} is null for {@code FIXED_AMOUNT}; {@code cap}
 * null means "no cap"; {@code proRating} null means "no pro-rating". Consumers must null-check.
 *
 * @param basis       the fact feed to evaluate (resolved by a hard-coded switch — never reflectively)
 * @param aggregation aggregation window, e.g. {@code FISCAL_YEAR_SUM}
 * @param tierTable   marginal tier bands (each rate applies to its own slice)
 * @param proRating   optional pro-rating by months employed in FY
 * @param cap         optional hard cap on the earned amount
 * @param pension     false → Danløn "41 Bonus"; true → with-pension bucket
 * @param replaces    e.g. {@code YPOT} (suppresses the TW_BONUS for that FY)
 * @param schedule    the payout schedule
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Spec(
        Basis basis,
        String aggregation,
        List<Tier> tierTable,
        ProRating proRating,
        BigDecimal cap,
        boolean pension,
        String replaces,
        Schedule schedule
) {
}
