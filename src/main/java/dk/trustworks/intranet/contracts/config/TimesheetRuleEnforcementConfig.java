package dk.trustworks.intranet.contracts.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Rollout mode for server-side timesheet agreement-rule validation.
 *
 * <p>{@link Mode#OFF} is deliberately the default and performs no contract or rule lookup.
 * {@link Mode#LOG_ONLY} evaluates and audits failures while preserving the current save behavior,
 * and {@link Mode#ENFORCE} rejects invalid work entries with a structured HTTP 422 response.
 */
@ApplicationScoped
@ConfigMapping(prefix = "feature.timesheet.rule-enforcement")
public interface TimesheetRuleEnforcementConfig {

    @WithDefault("OFF")
    Mode mode();

    enum Mode {
        OFF,
        LOG_ONLY,
        ENFORCE
    }
}
