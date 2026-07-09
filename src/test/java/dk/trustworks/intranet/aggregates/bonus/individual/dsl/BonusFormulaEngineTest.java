package dk.trustworks.intranet.aggregates.bonus.individual.dsl;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Basis;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Cadence;
import dk.trustworks.intranet.aggregates.bonus.individual.model.ProRating;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Schedule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Tier;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Yearly;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Security- and money-critical unit tests for the sandboxed JEXL {@code formula} engine — no Quarkus boot
 * (the engine is {@code new}-able with an explicit timeout, and {@link BonusContext} takes an in-memory
 * variable resolver, so no DB is touched).
 * <p>
 * Proves: (1) {@code tier(production)} reproduces the declarative amount to the øre; (2) conditional/blended
 * formulas compute; (3) EVERY sandbox-escape attempt (reflection, construction, {@code System}) is rejected
 * or blocked and never executes; (4) syntax / disallowed-variable / over-length formulas are rejected at
 * validation; (5) a runaway loop is bounded by the timeout; (6) a null / non-numeric result fails safe.
 */
class BonusFormulaEngineTest {

    /** A short timeout keeps the runaway test fast. */
    private final BonusFormulaEngine engine = new BonusFormulaEngine(500L);

    /** Michael's contract tier table: marginal 0 / 20 / 30 / 40 / 45%, open-ended top band (== 675,000 @ 3M). */
    private static List<Tier> michaelTiers() {
        return List.of(
                new Tier(bd(0), bd(1_000_000), bd("0.00")),
                new Tier(bd(1_000_000), bd(1_500_000), bd("0.20")),
                new Tier(bd(1_500_000), bd(2_000_000), bd("0.30")),
                new Tier(bd(2_000_000), bd(2_500_000), bd("0.40")),
                new Tier(bd(2_500_000), null, bd("0.45")));
    }

    private static Spec formulaSpec(String formula, List<Tier> tiers) {
        Schedule schedule = new Schedule(Cadence.YEARLY, new Yearly(1), null, null);
        return new Spec(Basis.OWN_INVOICED_REVENUE, "FISCAL_YEAR_SUM", tiers,
                new ProRating(false), null, false, "YPOT", schedule, formula);
    }

    /** A context whose fact variables come from an in-memory map — no basis resolver, no DB. */
    private static BonusContext ctx(Map<String, BigDecimal> facts, int months, List<Tier> tiers,
                                    BigDecimal basisAmount) {
        return new BonusContext(tiers, months, 2026, basisAmount, facts::get);
    }

    private static Map<String, BigDecimal> facts(BigDecimal production, BigDecimal utilization) {
        Map<String, BigDecimal> m = new HashMap<>();
        m.put("production", production);
        m.put("utilization", utilization);
        return m;
    }

    // --- (1) tier(production) reproduces the declarative result to the øre ---

    @Test
    void tierOfProduction_reproducesDeclarativeAmount() {
        Spec spec = formulaSpec("tier(production)", michaelTiers());
        BigDecimal earned = engine.evaluate(spec, ctx(facts(bd(3_000_000), bd("0.80")), 12, michaelTiers(), bd(3_000_000)));
        assertEquals(0, earned.compareTo(bd(675_000)), "tier(production) must equal 675,000 but was " + earned);
    }

    // --- (2) conditional / blended formulas compute (both branches) ---

    @Test
    void conditional_highUtilization_takesTierBranch() {
        // utilization 0.80 > 0.75 → tier(production) = 675,000
        Spec spec = formulaSpec("utilization > 0.75 ? tier(production) : tier(production) * 0.5", michaelTiers());
        BigDecimal earned = engine.evaluate(spec, ctx(facts(bd(3_000_000), bd("0.80")), 12, michaelTiers(), bd(3_000_000)));
        assertEquals(0, earned.compareTo(bd(675_000)), "high-util branch should be 675,000 but was " + earned);
    }

    @Test
    void conditional_lowUtilization_takesHalfBranch() {
        // utilization 0.50 <= 0.75 → tier(production) * 0.5 = 337,500
        Spec spec = formulaSpec("utilization > 0.75 ? tier(production) : tier(production) * 0.5", michaelTiers());
        BigDecimal earned = engine.evaluate(spec, ctx(facts(bd(3_000_000), bd("0.50")), 12, michaelTiers(), bd(3_000_000)));
        assertEquals(0, earned.compareTo(bd(337_500)), "low-util branch should be 337,500 but was " + earned);
    }

    @Test
    void blendedMultiplier_producesCleanMoney() {
        // tier(production) * 1.1 = 675,000 * 1.1 = 742,500 — JEXL double-literal arithmetic must not leak
        // float noise into a payroll amount; the øre-scale coercion yields exactly 742,500.00.
        Spec spec = formulaSpec("tier(production) * 1.1", michaelTiers());
        BigDecimal earned = engine.evaluate(spec, ctx(facts(bd(3_000_000), bd("0.80")), 12, michaelTiers(), bd(3_000_000)));
        assertEquals(0, earned.compareTo(new BigDecimal("742500")), "blended amount should be 742,500 but was " + earned);
        assertEquals(2, earned.scale(), "money must be at øre (2-decimal) scale but was scale " + earned.scale());
    }

    @Test
    void percentOfProduction_producesCleanMoney() {
        // production * 0.15 = 450,000.00 — a common "flat % of production" formula, no float noise.
        Spec spec = formulaSpec("production * 0.15", michaelTiers());
        BigDecimal earned = engine.evaluate(spec, ctx(facts(bd(3_000_000), bd("0.80")), 12, michaelTiers(), bd(3_000_000)));
        assertEquals(0, earned.compareTo(new BigDecimal("450000")), "0.15·production should be 450,000 but was " + earned);
    }

    @Test
    void mathHelpers_minClampsBlendedResult() {
        // min(tier(production), 500000) → clamp 675,000 down to 500,000
        Spec spec = formulaSpec("min(tier(production), 500000)", michaelTiers());
        BigDecimal earned = engine.evaluate(spec, ctx(facts(bd(3_000_000), bd("0.80")), 12, michaelTiers(), bd(3_000_000)));
        assertEquals(0, earned.compareTo(bd(500_000)), "min() should clamp to 500,000 but was " + earned);
    }

    @Test
    void monthsEmployed_prorationExpressibleInFormula() {
        // The formula owns pro-rating: tier(production) * monthsEmployed / 12 at 6 months = 337,500
        Spec spec = formulaSpec("tier(production) * monthsEmployed / 12", michaelTiers());
        BigDecimal earned = engine.evaluate(spec, ctx(facts(bd(3_000_000), bd("0.80")), 6, michaelTiers(), bd(3_000_000)));
        assertEquals(0, earned.compareTo(bd(337_500)), "6/12 proration should be 337,500 but was " + earned);
    }

    // --- (3) sandbox: no formula can reach java.*, System, Runtime, reflection ---

    @Test
    void sandbox_reflectionViaClassForName_isBlocked() {
        assertFailsSafe("''.class.forName('java.lang.System')");
    }

    @Test
    void sandbox_newRuntime_isBlocked() {
        assertFailsSafe("new('java.lang.Runtime')");
    }

    @Test
    void sandbox_systemExit_isBlockedAndNeverExecutes() {
        // If this executed, the JVM would exit and the whole test run would die — reaching the assertion
        // proves System.exit never ran.
        assertFailsSafe("System.exit(0)");
    }

    @Test
    void sandbox_getClassEscape_isBlocked() {
        assertFailsSafe("production.getClass().getName()");
    }

    @Test
    void sandbox_disallowedVariable_isRejectedAtValidation() {
        // `password` is not in the curated allow-list → rejected at write time, never resolved.
        assertThrows(BonusFormulaException.class, () -> engine.validate("password * 2"));
    }

    // --- (4) invalid / over-length formulas rejected at validation ---

    @Test
    void validation_syntaxError_isRejected() {
        assertThrows(BonusFormulaException.class, () -> engine.validate("tier(production"));
    }

    @Test
    void validation_overLength_isRejected() {
        String tooLong = "1 +".repeat(700) + " 1"; // > 2000 chars
        assertThrows(BonusFormulaException.class, () -> engine.validate(tooLong));
    }

    @Test
    void validation_tierOfProduction_isAccepted() {
        // The canonical formula compiles and references only allow-listed variables.
        engine.validate("utilization > 0.75 ? tier(production) * 1.1 : tier(production)");
    }

    // --- (5) language lockdown: a bonus formula is a pure expression — loops/new/assignment do not parse ---

    @Test
    void runawayLoop_rejectedAtParse() {
        // Loops are disabled, so a runaway `while` is rejected at WRITE time and can never reach evaluation
        // (structurally stronger than relying on the 2 s cooperative timeout, which cannot preempt CPU/heap).
        assertThrows(BonusFormulaException.class, () -> engine.validate("while(true){}"));
    }

    @Test
    void featureLockdown_rejectsConstruction() {
        assertThrows(BonusFormulaException.class, () -> engine.validate("new('java.lang.String')"));
    }

    @Test
    void featureLockdown_rejectsAssignment() {
        assertThrows(BonusFormulaException.class, () -> engine.validate("production = 1"));
    }

    @Test
    void roundScale_isBounded() {
        // A giant scale would allocate a huge BigDecimal; round() rejects it before setScale.
        Spec spec = formulaSpec("round(production, 1000000000)", michaelTiers());
        assertThrows(BonusFormulaException.class,
                () -> engine.evaluate(spec, ctx(facts(bd(3_000_000), bd("0.80")), 12, michaelTiers(), bd(3_000_000))));
    }

    @Test
    void implausiblyLargeResult_failsSafe() {
        // production^5 ≈ 2.4e32 (33 significant digits) exceeds the magnitude guard → refused, no guessed pay.
        Spec spec = formulaSpec("production * production * production * production * production", michaelTiers());
        assertThrows(BonusFormulaException.class,
                () -> engine.evaluate(spec, ctx(facts(bd(3_000_000), bd("0.80")), 12, michaelTiers(), bd(3_000_000))));
    }

    // --- (6) null / non-numeric result fails safe (never pays a guessed amount) ---

    @Test
    void nullResult_failsSafe() {
        // utilization 0.50 is not > 999 → the formula yields null → refused, no payout.
        Spec spec = formulaSpec("utilization > 999 ? tier(production) : null", michaelTiers());
        assertThrows(BonusFormulaException.class,
                () -> engine.evaluate(spec, ctx(facts(bd(3_000_000), bd("0.50")), 12, michaelTiers(), bd(3_000_000))));
    }

    @Test
    void nonNumericResult_failsSafe() {
        Spec spec = formulaSpec("'not a number'", michaelTiers());
        assertThrows(BonusFormulaException.class,
                () -> engine.evaluate(spec, ctx(facts(bd(3_000_000), bd("0.80")), 12, michaelTiers(), bd(3_000_000))));
    }

    @Test
    void divisionByZero_failsSafe() {
        Spec spec = formulaSpec("production / (monthsEmployed - monthsEmployed)", michaelTiers());
        assertThrows(BonusFormulaException.class,
                () -> engine.evaluate(spec, ctx(facts(bd(3_000_000), bd("0.80")), 12, michaelTiers(), bd(3_000_000))));
    }

    /** A formula that must be rejected at validation OR blocked at evaluation — either way it never runs. */
    private void assertFailsSafe(String formula) {
        Spec spec = formulaSpec(formula, michaelTiers());
        assertThrows(BonusFormulaException.class, () -> {
            engine.validate(formula); // most escapes fail here (compile/permission) ...
            engine.evaluate(spec, ctx(facts(bd(3_000_000), bd("0.80")), 12, michaelTiers(), bd(3_000_000))); // ... the rest here
        });
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
