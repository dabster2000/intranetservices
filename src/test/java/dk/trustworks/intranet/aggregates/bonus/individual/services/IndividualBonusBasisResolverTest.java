package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Basis;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the run-rate forecast helpers — no Quarkus boot / DB required (the extracted math is
 * pure). Covers the P2#8 fix: a mid-FY projection of a production basis is annualised over the elapsed
 * employed months so it reflects a year-end estimate rather than production-to-date.
 */
class IndividualBonusBasisResolverTest {

    // --- runRate: annualise actuals over elapsed employed months ---

    @Test
    void runRate_midWindow_annualisesToFullWindow() {
        // 1,200,000 booked over 4 elapsed employed months of a 10-month window → 3,000,000 estimate.
        BigDecimal forecast = IndividualBonusBasisResolver.runRate(bd(1_200_000), 4, 10);
        assertEquals(0, forecast.compareTo(bd(3_000_000)), "run-rate of 1.2M/4×10 should be 3.0M but was " + forecast);
    }

    @Test
    void runRate_fullyElapsedWindow_returnsActuals() {
        // total <= elapsed → nothing to extrapolate; return the booked actuals unchanged.
        assertEquals(0, IndividualBonusBasisResolver.runRate(bd(675_000), 12, 12).compareTo(bd(675_000)));
        assertEquals(0, IndividualBonusBasisResolver.runRate(bd(675_000), 12, 10).compareTo(bd(675_000)));
    }

    @Test
    void runRate_nothingElapsed_returnsActuals() {
        // elapsed <= 0 → cannot annualise (would divide by zero); degrade to actuals (typically ~0).
        assertEquals(0, IndividualBonusBasisResolver.runRate(BigDecimal.ZERO, 0, 12).compareTo(BigDecimal.ZERO));
        assertEquals(0, IndividualBonusBasisResolver.runRate(bd(50_000), 0, 12).compareTo(bd(50_000)));
    }

    @Test
    void runRate_nullActuals_isZero() {
        assertEquals(0, IndividualBonusBasisResolver.runRate(null, 4, 10).compareTo(BigDecimal.ZERO));
    }

    @Test
    void runRate_roundsToOere() {
        // 100 over 3 elapsed × 10 total = 333.333... → 333.33 (2dp, HALF_UP).
        BigDecimal forecast = IndividualBonusBasisResolver.runRate(bd(100), 3, 10);
        assertEquals(0, forecast.compareTo(new BigDecimal("333.33")), "expected 333.33 but was " + forecast);
    }

    // --- isForecastable: only ADDITIVE production/hours sums are scaled ---

    @Test
    void isForecastable_additiveBases_true() {
        assertTrue(IndividualBonusBasisResolver.isForecastable(Basis.OWN_INVOICED_REVENUE));
        assertTrue(IndividualBonusBasisResolver.isForecastable(Basis.BILLABLE_HOURS));
    }

    @Test
    void isForecastable_ratioAndLevelBases_false() {
        // Ratios/levels are already period-normalised — scaling by month count would inflate them.
        assertFalse(IndividualBonusBasisResolver.isForecastable(Basis.UTILIZATION));
        assertFalse(IndividualBonusBasisResolver.isForecastable(Basis.BUDGET_ATTAINMENT));
        assertFalse(IndividualBonusBasisResolver.isForecastable(Basis.SALARY));
        assertFalse(IndividualBonusBasisResolver.isForecastable(Basis.FIXED_AMOUNT));
    }

    // --- monthsBetweenInclusive: distinct calendar months spanned ---

    @Test
    void monthsBetweenInclusive_countsBothEndpoints() {
        assertEquals(1, IndividualBonusBasisResolver.monthsBetweenInclusive(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));
        assertEquals(10, IndividualBonusBasisResolver.monthsBetweenInclusive(
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 6, 30)));
        assertEquals(12, IndividualBonusBasisResolver.monthsBetweenInclusive(
                LocalDate.of(2026, 7, 1), LocalDate.of(2027, 6, 30)));
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }
}
