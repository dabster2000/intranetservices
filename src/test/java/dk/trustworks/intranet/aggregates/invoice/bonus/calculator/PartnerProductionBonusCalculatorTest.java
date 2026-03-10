package dk.trustworks.intranet.aggregates.invoice.bonus.calculator;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PartnerProductionBonusCalculatorTest {

    @Inject
    PartnerProductionBonusCalculator calculator;

    @Test
    void zeroBonusWhenBelowThreshold() {
        // Revenue 1.4M, full year → below 1.5M threshold
        BigDecimal result = calculator.calculateProductionBonus(BigDecimal.valueOf(1_400_000), 12);
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
    }

    @Test
    void zeroBonusExactlyAtThreshold() {
        // Revenue 1.5M, full year → at threshold (not above)
        BigDecimal result = calculator.calculateProductionBonus(BigDecimal.valueOf(1_500_000), 12);
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
    }

    @Test
    void positiveBonusAboveThreshold() {
        // Revenue 2.0M, full year → 500k eligible, 20% = 100k
        BigDecimal result = calculator.calculateProductionBonus(BigDecimal.valueOf(2_000_000), 12);
        assertEquals(0, BigDecimal.valueOf(100_000).compareTo(result),
                "Expected 100,000 but got: " + result);
    }

    @Test
    void proratedThresholdForPartialYear() {
        // Revenue 1.0M, 6 months → prorated threshold = 1.5M * 6/12 = 750k
        // eligible = 1M - 750k = 250k, bonus = 250k * 0.20 = 50k
        BigDecimal result = calculator.calculateProductionBonus(BigDecimal.valueOf(1_000_000), 6);
        assertEquals(0, BigDecimal.valueOf(50_000).compareTo(result),
                "Expected 50,000 but got: " + result);
    }

    @Test
    void zeroBonusBelowProratedThreshold() {
        // Revenue 500k, 6 months → prorated threshold = 750k → below
        BigDecimal result = calculator.calculateProductionBonus(BigDecimal.valueOf(500_000), 6);
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
    }

    @Test
    void zeroBonusForNullRevenue() {
        BigDecimal result = calculator.calculateProductionBonus(null, 12);
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
    }

    @Test
    void zeroBonusForZeroRevenue() {
        BigDecimal result = calculator.calculateProductionBonus(BigDecimal.ZERO, 12);
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
    }

    @Test
    void zeroBonusForInvalidMonths() {
        BigDecimal result = calculator.calculateProductionBonus(BigDecimal.valueOf(2_000_000), 0);
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
    }

    @Test
    void zeroBonusForNegativeMonths() {
        BigDecimal result = calculator.calculateProductionBonus(BigDecimal.valueOf(2_000_000), -1);
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
    }

    @Test
    void zeroBonusForOver12Months() {
        BigDecimal result = calculator.calculateProductionBonus(BigDecimal.valueOf(2_000_000), 13);
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
    }

    @Test
    void fullYearConvenienceMethod() {
        BigDecimal full = calculator.calculateProductionBonus(BigDecimal.valueOf(2_000_000));
        BigDecimal explicit = calculator.calculateProductionBonus(BigDecimal.valueOf(2_000_000), 12);
        assertEquals(0, full.compareTo(explicit));
    }

    @Test
    void thresholdMetChecks() {
        assertTrue(calculator.isThresholdMet(BigDecimal.valueOf(2_000_000), 12));
        assertFalse(calculator.isThresholdMet(BigDecimal.valueOf(1_000_000), 12));
        assertFalse(calculator.isThresholdMet(null, 12));
    }

    @Test
    void proratedThresholdValues() {
        BigDecimal full = calculator.getProratedThreshold(12);
        assertEquals(0, BigDecimal.valueOf(1_500_000).compareTo(full),
                "Full year threshold should be 1.5M, got: " + full);

        BigDecimal half = calculator.getProratedThreshold(6);
        assertEquals(0, BigDecimal.valueOf(750_000).compareTo(half),
                "Half year threshold should be 750k, got: " + half);
    }
}
