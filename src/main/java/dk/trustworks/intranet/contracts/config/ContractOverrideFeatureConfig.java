package dk.trustworks.intranet.contracts.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Feature flag configuration for Contract Rule Override System.
 *
 * Enables zero-downtime deployment with gradual rollout control.
 * All flags default to disabled (false) for safety.
 */
@ApplicationScoped
@ConfigMapping(prefix = "feature.contract.overrides")
public interface ContractOverrideFeatureConfig {

    /**
     * Master switch for contract override system.
     * When false, all override functionality is disabled and system falls back to contract type rules.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * API-specific feature flags
     */
    ApiConfig api();

    /**
     * UI-specific feature flags (read by frontend)
     */
    UiConfig ui();

    /**
     * Rollout configuration
     */
    RolloutConfig rollout();

    /**
     * Cache configuration
     */
    CacheConfig cache();

    // Nested configurations

    interface ApiConfig {
        @WithDefault("false")
        boolean enabled();

        @WithDefault("false")
        boolean readOnly();
    }

    interface UiConfig {
        @WithDefault("false")
        boolean enabled();
    }

    interface RolloutConfig {
        /**
         * Percentage of contracts to enable overrides for (0-100)
         */
        @WithDefault("0")
        int percentage();

        /**
         * Whitelist of contract UUIDs to always enable overrides for (pilot testing)
         */
        List<String> whitelist();
    }

    interface CacheConfig {
        @WithDefault("3600")  // 1 hour
        int ttlSeconds();

        @WithDefault("10000")
        int maxSize();
    }
}
