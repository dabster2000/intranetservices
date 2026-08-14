package dk.trustworks.intranet.competenceservice.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fraction → whole percent, for display only.
 *
 * <p>Scores and thresholds are stored as fractions ({@code 0.8000}, {@code 0.800}) because
 * that is what the comparison uses, but every surface in the module shows whole percents. The
 * conversion lives here once so the score badge and the threshold line can never round by
 * different rules — "83% of 80%" next to a red FAILED is the kind of thing that gets a whole
 * evidence trail disbelieved.
 *
 * <p><strong>Rounding is directional, not nearest.</strong> Scores round down and thresholds
 * round up, so a displayed score can never reach a displayed threshold that the actual
 * comparison did not reach. Nearest-rounding both would render a 79.6% attempt against an
 * 80% threshold as "80% of 80% — failed", which reads as a bug in the scorer.
 *
 * <p>The rounded numbers are never the authority: {@code passed} is computed on the fractions
 * by {@code CompetenceTestScorer} and travels on the attempt row.
 */
final class CompetencePercent {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private CompetencePercent() {
    }

    /** A score as a whole percent, rounded <em>down</em>. {@code null} → 0. */
    static int score(BigDecimal fraction) {
        return fraction == null
                ? 0
                : fraction.multiply(HUNDRED).setScale(0, RoundingMode.DOWN).intValue();
    }

    /** A threshold as a whole percent, rounded <em>up</em>. {@code null} → 0. */
    static int threshold(BigDecimal fraction) {
        return fraction == null
                ? 0
                : fraction.multiply(HUNDRED).setScale(0, RoundingMode.UP).intValue();
    }
}
