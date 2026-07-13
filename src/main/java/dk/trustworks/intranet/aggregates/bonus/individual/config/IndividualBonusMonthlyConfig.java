package dk.trustworks.intranet.aggregates.bonus.individual.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.enterprise.context.ApplicationScoped;

/** Runtime rollout controls for the monthly-utilization bonus workflow. */
@ApplicationScoped
@ConfigMapping(prefix = "individual-bonus.monthly")
public interface IndividualBonusMonthlyConfig {

    @WithDefault("false")
    boolean authoringEnabled();

    @WithDefault("false")
    boolean materializationEnabled();

    @WithDefault("false")
    boolean reconciliationEnabled();

    @WithDefault("24")
    int dueLookbackMonths();

    default int boundedDueLookbackMonths() {
        return Math.max(1, dueLookbackMonths());
    }
}
