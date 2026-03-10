package dk.trustworks.intranet.aggregates.invoice.bonus.calculator;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PartnerSalesBonusCalculatorTest {

    @Inject
    PartnerSalesBonusCalculator calculator;

    @Test
    void zeroBonusWhenBelowThreshold() {
        // 2 partners, 7M total → 3.5M avg, below 4M threshold
        BigDecimal result = calculator.calculateBonusPerPartner(BigDecimal.valueOf(7_000_000), 2);
        assertEquals(0, result.compareTo(BigDecimal.ZERO), "Should be zero below threshold");
    }

    @Test
    void zeroBonusExactlyAtThreshold() {
        // 2 partners, 8M total → 4M avg, at threshold (not above)
        BigDecimal result = calculator.calculateBonusPerPartner(BigDecimal.valueOf(8_000_000), 2);
        assertEquals(0, result.compareTo(BigDecimal.ZERO), "Should be zero at exact threshold");
    }

    @Test
    void positiveBonusAboveThreshold() {
        // 2 partners, 10M total → 5M avg, 1M eligible
        BigDecimal result = calculator.calculateBonusPerPartner(BigDecimal.valueOf(10_000_000), 2);
        assertTrue(result.compareTo(BigDecimal.ZERO) > 0, "Should have positive bonus above threshold");

        // eligible = 1M, eligibleMillions = 1.0
        // uplift = exp(1.0/30) / 100 = exp(0.0333) / 100 ≈ 1.0339 / 100 ≈ 0.010339
        // totalRate = 0.035 + 0.010339 ≈ 0.045339
        // bonus = 1,000,000 * 0.045339 ≈ 45,339
        assertTrue(result.doubleValue() > 40_000 && result.doubleValue() < 50_000,
                "Bonus should be approximately 45k for 1M eligible, got: " + result);
    }

    @Test
    void zeroBonusForZeroPartners() {
        BigDecimal result = calculator.calculateBonusPerPartner(BigDecimal.valueOf(10_000_000), 0);
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
    }

    @Test
    void zeroBonusForNegativePartners() {
        BigDecimal result = calculator.calculateBonusPerPartner(BigDecimal.valueOf(10_000_000), -1);
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
    }

    @Test
    void singlePartnerHighSales() {
        // 1 partner, 6M total → 2M eligible
        BigDecimal result = calculator.calculateBonusPerPartner(BigDecimal.valueOf(6_000_000), 1);
        assertTrue(result.compareTo(BigDecimal.ZERO) > 0);
        // Higher eligible sales → higher uplift → higher rate
        assertTrue(result.doubleValue() > 80_000, "Should be over 80k for 2M eligible, got: " + result);
    }

    @Test
    void thresholdMetReturnsTrueAboveThreshold() {
        assertTrue(calculator.isThresholdMet(BigDecimal.valueOf(10_000_000), 2));
    }

    @Test
    void thresholdMetReturnsFalseBelowThreshold() {
        assertFalse(calculator.isThresholdMet(BigDecimal.valueOf(7_000_000), 2));
    }

    @Test
    void thresholdMetReturnsFalseForZeroPartners() {
        assertFalse(calculator.isThresholdMet(BigDecimal.valueOf(10_000_000), 0));
    }
}
