package dk.trustworks.intranet.aggregates.crm.enrichment;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The runtime switch for every client-enrichment job — the {@code AccountSlackFeatureFlag}
 * shape: read from {@code app_settings} on every call (tiny table, no caching), missing or
 * unparseable ⇒ {@code false}.
 *
 * <p>{@code crm.enrichment.enabled} is seeded {@code 'true'} by V610, because the three
 * jobs were asked for as nightly jobs and spend little. It exists so the spend can be
 * stopped without a deploy: {@code PUT /app-settings} with the key and {@code false}.
 * Off ⇒ the nightly job, the manual trigger and the on-edit sector check are all
 * no-ops; the marks already on the clients stay.
 */
@ApplicationScoped
public class ClientEnrichmentFeatureFlag {

    static final String ENABLED_KEY = "crm.enrichment.enabled";

    @Inject
    AppSettingService appSettingService;

    public boolean isEnabled() {
        return appSettingService.findByKey(ENABLED_KEY)
                .map(AppSetting::getSettingValue)
                .map(String::trim)
                .map(Boolean::parseBoolean)
                .orElse(false);
    }
}
