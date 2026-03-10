package dk.trustworks.intranet.aggregates.invoice.bonus.calculator;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates production bonus for individual partners.
 * <p>
 * Formula:
 * 1. proratedThreshold = annualThreshold * (monthsActive / 12)
 * 2. If revenue <= proratedThreshold: bonus = 0
 * 3. eligibleRevenue = revenue - proratedThreshold
 * 4. productionBonus = eligibleRevenue * commissionRate (20%)
 */
@ApplicationScoped
public class PartnerProductionBonusCalculator {

    @Inject
    PartnerBonusConfig config;

    public BigDecimal calculateProductionBonus(BigDecimal annualRevenue, int monthsActive) {
        if (monthsActive <= 0 || monthsActive > 12) {
            Log.warn("Invalid months active: " + monthsActive);
            return BigDecimal.ZERO;
        }
        if (annualRevenue == null || annualRevenue.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal proratedThreshold = BigDecimal.valueOf(config.productionAnnualThreshold())
                .multiply(BigDecimal.valueOf(monthsActive))
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);

        if (annualRevenue.compareTo(proratedThreshold) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal eligibleRevenue = annualRevenue.subtract(proratedThreshold);
        BigDecimal productionBonus = eligibleRevenue
                .multiply(BigDecimal.valueOf(config.productionCommissionRate()))
                .setScale(2, RoundingMode.HALF_UP);

        Log.infof("Production bonus: revenue=%.2f, threshold=%.2f, eligible=%.2f, bonus=%.2f",
                annualRevenue.doubleValue(), proratedThreshold.doubleValue(),
                eligibleRevenue.doubleValue(), productionBonus.doubleValue());

        return productionBonus;
    }

    public BigDecimal calculateProductionBonus(BigDecimal annualRevenue) {
        return calculateProductionBonus(annualRevenue, 12);
    }

    public boolean isThresholdMet(BigDecimal annualRevenue, int monthsActive) {
        if (monthsActive <= 0 || monthsActive > 12) return false;
        if (annualRevenue == null || annualRevenue.compareTo(BigDecimal.ZERO) <= 0) return false;
        BigDecimal proratedThreshold = BigDecimal.valueOf(config.productionAnnualThreshold())
                .multiply(BigDecimal.valueOf(monthsActive))
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        return annualRevenue.compareTo(proratedThreshold) > 0;
    }

    public BigDecimal getProratedThreshold(int monthsActive) {
        if (monthsActive <= 0 || monthsActive > 12) {
            return BigDecimal.valueOf(config.productionAnnualThreshold());
        }
        return BigDecimal.valueOf(config.productionAnnualThreshold())
                .multiply(BigDecimal.valueOf(monthsActive))
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }
}
