package dk.trustworks.intranet.aggregates.bonus.individual.dsl;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Tier;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;

/**
 * The ONLY object a bonus formula may call methods on — the top-level (unprefixed) JEXL namespace.
 * Exposes {@code tier(x)} (reusing the declarative marginal-tier math over this rule's tier table) plus a
 * small pure math namespace ({@code min}, {@code max}, {@code round}, {@code floor}, {@code ceil},
 * {@code abs}). Constructed fresh per evaluation with the rule's {@code tierTable}.
 * <p>
 * SECURITY: every method here is explicitly allow-listed in {@code BonusFormulaEngine}'s block-mode
 * {@link org.apache.commons.jexl3.introspection.JexlSandbox}; any OTHER method or property access — on
 * this object or on any injected value (a {@link BigDecimal}, a {@link String}, …) — is denied. All
 * arguments are coerced defensively; a non-numeric argument fails safe with {@link BonusFormulaException}.
 */
public final class BonusFunctions {

    /** Upper bound on the decimal places {@code round(x, n)} may request — caps the BigDecimal allocation. */
    private static final int MAX_ROUND_SCALE = 10;

    private final List<Tier> tierTable;

    public BonusFunctions(List<Tier> tierTable) {
        this.tierTable = tierTable;
    }

    /** Marginal-tier bonus for {@code amount} over this rule's tier table (no pro-rating, no cap). */
    public BigDecimal tier(Object amount) {
        return TierMath.marginalSum(tierTable, num(amount, "tier"));
    }

    /** The smaller of two numbers. */
    public BigDecimal min(Object a, Object b) {
        return num(a, "min").min(num(b, "min"));
    }

    /** The larger of two numbers. */
    public BigDecimal max(Object a, Object b) {
        return num(a, "max").max(num(b, "max"));
    }

    /** Round HALF_UP to whole units. */
    public BigDecimal round(Object x) {
        return num(x, "round").setScale(0, RoundingMode.HALF_UP);
    }

    /** Round HALF_UP to {@code scale} decimals (0..{@value #MAX_ROUND_SCALE}). */
    public BigDecimal round(Object x, Object scale) {
        int s = num(scale, "round").intValue();
        if (s < 0 || s > MAX_ROUND_SCALE) {
            throw new BonusFormulaException("round() scale must be within [0, " + MAX_ROUND_SCALE + "]");
        }
        return num(x, "round").setScale(s, RoundingMode.HALF_UP);
    }

    /** Largest whole number ≤ x. */
    public BigDecimal floor(Object x) {
        return num(x, "floor").setScale(0, RoundingMode.FLOOR);
    }

    /** Smallest whole number ≥ x. */
    public BigDecimal ceil(Object x) {
        return num(x, "ceil").setScale(0, RoundingMode.CEILING);
    }

    /** Absolute value. */
    public BigDecimal abs(Object x) {
        return num(x, "abs").abs();
    }

    /** Coerce a JEXL operand to BigDecimal; fail safe (never silently 0) for a non-numeric argument. */
    private static BigDecimal num(Object o, String fn) {
        if (o == null) {
            throw new BonusFormulaException(fn + "() received a null argument");
        }
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof BigInteger bi) return new BigDecimal(bi);
        if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new BonusFormulaException(fn + "() received a non-finite number");
            }
            return BigDecimal.valueOf(d);
        }
        if (o instanceof Number n) return BigDecimal.valueOf(n.longValue());
        throw new BonusFormulaException(fn + "() expects a number but got " + o.getClass().getSimpleName());
    }
}
