package dk.trustworks.intranet.aggregates.invoice.bonus.calculator;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates partner sales bonus based on group sales performance.
 * <p>
 * Formula:
 * 1. averageSalesPerPartner = totalGroupSales / numberOfPartners
 * 2. If average <= threshold (4M): bonus = 0
 * 3. eligibleSales = average - threshold
 * 4. uplift = exp(eligibleSales_in_millions / calibrationValue) / 100
 * 5. totalRate = baseShareValue + uplift
 * 6. bonusPerPartner = eligibleSales * totalRate
 */
@ApplicationScoped
public class PartnerSalesBonusCalculator {

    @Inject
    PartnerBonusConfig config;

    public BigDecimal calculateBonusPerPartner(BigDecimal totalGroupSales, int numberOfPartners) {
        if (numberOfPartners <= 0) {
            Log.warn("Invalid number of partners: " + numberOfPartners);
            return BigDecimal.ZERO;
        }

        BigDecimal averageSalesPerPartner = totalGroupSales.divide(
                BigDecimal.valueOf(numberOfPartners), 2, RoundingMode.HALF_UP
        );

        BigDecimal threshold = BigDecimal.valueOf(config.salesThresholdPerPartner());
        if (averageSalesPerPartner.compareTo(threshold) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal eligibleSales = averageSalesPerPartner.subtract(threshold);
        double eligibleSalesInMillions = eligibleSales.doubleValue() / 1_000_000.0;
        double uplift = Math.exp(eligibleSalesInMillions / config.calibrationValue()) / 100.0;
        double totalRate = config.baseShareValue() + uplift;

        BigDecimal bonusPerPartner = eligibleSales.multiply(BigDecimal.valueOf(totalRate))
                .setScale(2, RoundingMode.HALF_UP);

        Log.infof("Partner sales bonus: eligible=%.2f, rate=%.4f%%, bonus=%.2f",
                eligibleSales.doubleValue(), totalRate * 100, bonusPerPartner.doubleValue());

        return bonusPerPartner;
    }

    public boolean isThresholdMet(BigDecimal totalGroupSales, int numberOfPartners) {
        if (numberOfPartners <= 0) return false;
        BigDecimal avg = totalGroupSales.divide(
                BigDecimal.valueOf(numberOfPartners), 2, RoundingMode.HALF_UP
        );
        return avg.compareTo(BigDecimal.valueOf(config.salesThresholdPerPartner())) > 0;
    }
}
