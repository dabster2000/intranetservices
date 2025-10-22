package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.config.ContractOverrideFeatureConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Service for evaluating contract override feature flags.
 *
 * Implements percentage-based rollout and whitelist logic.
 */
@ApplicationScoped
@JBossLog
public class ContractOverrideFeatureService {

    @Inject
    ContractOverrideFeatureConfig config;

    /**
     * Check if override system is globally enabled
     */
    public boolean isOverrideSystemEnabled() {
        return config.enabled();
    }

    /**
     * Check if override API is available
     */
    public boolean isApiEnabled() {
        return config.enabled() && config.api().enabled();
    }

    /**
     * Check if override API is in read-only mode
     */
    public boolean isApiReadOnly() {
        return config.api().readOnly();
    }

    /**
     * Check if override UI should be visible
     */
    public boolean isUiEnabled() {
        return config.enabled() && config.ui().enabled();
    }

    /**
     * Check if overrides are enabled for a specific contract
     *
     * @param contractUuid Contract UUID to check
     * @return true if overrides enabled for this contract
     */
    public boolean isEnabledForContract(String contractUuid) {
        if (!config.enabled()) {
            return false;
        }

        // Check whitelist first (for pilot testing)
        if (config.rollout().whitelist() != null &&
            config.rollout().whitelist().contains(contractUuid)) {
            log.debugf("Contract %s is whitelisted for overrides", contractUuid);
            return true;
        }

        // Check percentage rollout
        int percentage = config.rollout().percentage();
        if (percentage == 0) {
            return false;
        }
        if (percentage == 100) {
            return true;
        }

        // Use hash-based deterministic rollout
        // Same contract UUID always gets same result (stable rollout)
        int hash = Math.abs(contractUuid.hashCode() % 100);
        boolean enabled = hash < percentage;

        if (enabled) {
            log.debugf("Contract %s included in %d%% rollout (hash=%d)",
                contractUuid, percentage, hash);
        }

        return enabled;
    }

    /**
     * Get cache TTL in seconds
     */
    public int getCacheTtlSeconds() {
        return config.cache().ttlSeconds();
    }

    /**
     * Get max cache size
     */
    public int getCacheMaxSize() {
        return config.cache().maxSize();
    }
}
