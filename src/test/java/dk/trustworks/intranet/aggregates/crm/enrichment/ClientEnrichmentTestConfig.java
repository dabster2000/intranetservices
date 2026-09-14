package dk.trustworks.intranet.aggregates.crm.enrichment;

import java.util.Optional;

/**
 * Builds a {@link ClientEnrichmentConfig} for the fast tier, which cannot inject one. Lives
 * in the config's own package so the tests in the {@code ai} and {@code services} packages
 * need no widening of the config's field visibility.
 */
public final class ClientEnrichmentTestConfig {

    private ClientEnrichmentTestConfig() {
    }

    public static ClientEnrichmentConfig defaults() {
        return with("production", 20, 10, 20, "low");
    }

    public static ClientEnrichmentConfig with(String environmentId, int cvrCap, int logoCap, int sectorCap, String effort) {
        ClientEnrichmentConfig config = new ClientEnrichmentConfig();
        config.enabled = true;
        config.environmentId = environmentId;
        config.cvrNightlyCap = cvrCap;
        config.cvrRetryAfterDays = 7;
        config.cvrFinderModel = "test-model";
        config.logoNightlyCap = logoCap;
        config.logoRetryAfterDays = 14;
        config.logoFinderModel = "test-model";
        config.logoImageModel = "test-image-model";
        config.logoMaxDownloadBytes = 5_242_880L;
        config.sectorNightlyCap = sectorCap;
        config.sectorRetryAfterDays = 7;
        config.sectorModel = "test-model";
        config.reasoningEffort = Optional.ofNullable(effort);
        return config;
    }
}
