package dk.trustworks.intranet.aggregates.bonus.individual.dsl;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Tier;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The curated, allow-listed set of facts a {@code formula} may reference, for one rule × fiscal-year
 * window. Direct facts ({@code basisAmount}, {@code monthsEmployed}, {@code fiscalYear}) are supplied
 * eagerly; the five per-employee fact bases ({@code production}, {@code utilization}, {@code billableHours},
 * {@code budgetAttainment}, {@code salary}) are resolved LAZILY through {@code variableResolver} so a
 * formula that references only {@code production} triggers only one DB query.
 * <p>
 * This is a plain value carrier — it holds NO CDI beans and does NO I/O itself; the caller (which owns the
 * request-scoped {@code EntityManager}) supplies {@code variableResolver}. That keeps DB access on the
 * caller thread while the formula itself evaluates on a bounded worker thread. Trivially unit-testable: a
 * test supplies an in-memory {@code variableResolver} (no Quarkus, no DB).
 */
public final class BonusContext {

    /** Lazily-resolved per-employee fact bases (each maps to one basis-resolver query). */
    public static final Set<String> FACT_VARIABLES =
            Set.of("production", "utilization", "billableHours", "budgetAttainment", "salary");

    /** Every identifier a formula may reference (fact bases + the eager direct facts). */
    public static final Set<String> ALLOWED_VARIABLES = Set.of(
            "production", "utilization", "billableHours", "budgetAttainment", "salary",
            "basisAmount", "monthsEmployed", "fiscalYear");

    private final List<Tier> tierTable;
    private final int monthsEmployed;
    private final int fiscalYear;
    private final BigDecimal basisAmount;
    private final Function<String, BigDecimal> variableResolver;
    private final Map<String, BigDecimal> factCache = new HashMap<>();

    public BonusContext(List<Tier> tierTable, int monthsEmployed, int fiscalYear,
                        BigDecimal basisAmount, Function<String, BigDecimal> variableResolver) {
        this.tierTable = tierTable;
        this.monthsEmployed = monthsEmployed;
        this.fiscalYear = fiscalYear;
        this.basisAmount = basisAmount == null ? BigDecimal.ZERO : basisAmount;
        this.variableResolver = variableResolver;
    }

    public List<Tier> tierTable() {
        return tierTable;
    }

    public int monthsEmployed() {
        return monthsEmployed;
    }

    public int fiscalYear() {
        return fiscalYear;
    }

    public BigDecimal basisAmount() {
        return basisAmount;
    }

    /**
     * Resolve one allow-listed variable to a value (never null). Direct facts are returned immediately;
     * the five per-employee fact bases are resolved once and cached. A name outside
     * {@link #ALLOWED_VARIABLES} fails safe — a formula can never reach an un-curated fact.
     */
    public BigDecimal resolveVariable(String name) {
        return switch (name) {
            case "basisAmount" -> basisAmount;
            case "monthsEmployed" -> BigDecimal.valueOf(monthsEmployed);
            case "fiscalYear" -> BigDecimal.valueOf(fiscalYear);
            default -> {
                if (!FACT_VARIABLES.contains(name)) {
                    throw new BonusFormulaException("Formula references unknown variable: " + name);
                }
                yield factCache.computeIfAbsent(name, n -> {
                    BigDecimal v = variableResolver == null ? null : variableResolver.apply(n);
                    return v == null ? BigDecimal.ZERO : v;
                });
            }
        };
    }
}
