package dk.trustworks.intranet.aggregates.invoice.bonus.calculator;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "bonus.partner")
public interface PartnerBonusConfig {

    @WithDefault("4000000.0")
    double salesThresholdPerPartner();

    @WithDefault("0.035")
    double baseShareValue();

    @WithDefault("30.0")
    double calibrationValue();

    @WithDefault("1500000.0")
    double productionAnnualThreshold();

    @WithDefault("0.20")
    double productionCommissionRate();
}
