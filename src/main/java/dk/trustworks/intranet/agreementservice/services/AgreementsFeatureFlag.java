package dk.trustworks.intranet.agreementservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Runtime toggles for the agreement registry (Phase 3) — the
 * {@code EmployeeDocumentsFeatureFlag} shape: read from
 * {@code app_settings} on every call (tiny table, no caching), missing
 * or unparseable ⇒ dark.
 *
 * <p>{@code documents.agreements.enabled} gates the UI surfaces and the
 * expiry Slack alerts; the completion recorder writes registry rows
 * regardless so no data is lost while the feature is dark.
 * {@code agreements.slack.channel} names the HR channel for the 60/14-day
 * expiry alerts; blank means alerts stay off. Both seeded by V547.</p>
 */
@ApplicationScoped
public class AgreementsFeatureFlag {

    static final String ENABLED_KEY = "documents.agreements.enabled";
    static final String SLACK_CHANNEL_KEY = "agreements.slack.channel";
    static final String BACKFILL_ENABLED_KEY = "documents.agreements.backfill.enabled";

    @Inject
    AppSettingService appSettingService;

    /** UI surfaces + expiry alerts armed. */
    public boolean isEnabled() {
        return appSettingService.findByKey(ENABLED_KEY)
                .map(AppSetting::getSettingValue)
                .map(String::trim)
                .map(Boolean::parseBoolean)
                .orElse(false);
    }

    /**
     * Phase-4 AI backfill (spec §10). Seeded OFF by V548; gates the
     * console UI and, unlike the registry recorder, also the run-start
     * endpoint — a corpus walk downloads employee documents and spends
     * AI tokens, so it must never fire while the feature is dark.
     */
    public boolean isBackfillEnabled() {
        return appSettingService.findByKey(BACKFILL_ENABLED_KEY)
                .map(AppSetting::getSettingValue)
                .map(String::trim)
                .map(Boolean::parseBoolean)
                .orElse(false);
    }

    /** The HR Slack channel for expiry alerts; null while unconfigured. */
    public String slackChannel() {
        return appSettingService.findByKey(SLACK_CHANNEL_KEY)
                .map(AppSetting::getSettingValue)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(null);
    }
}
