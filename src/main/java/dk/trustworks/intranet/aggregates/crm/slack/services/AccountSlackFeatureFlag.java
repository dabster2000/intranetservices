package dk.trustworks.intranet.aggregates.crm.slack.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The master switch for account Slack spaces (CRM spec §4.9) — the
 * {@link dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag}
 * shape: read from {@code app_settings} on every call (tiny table, no caching), missing
 * or unparseable ⇒ {@code false}. Every Slack feature in this repo is opt-in, and this
 * one reads client channels and spends model tokens on every linked account nightly, so
 * there has to be a way to stop it without a deploy.
 *
 * <p>{@code crm.slack.account-spaces.enabled} is seeded {@code 'false'} by V594. Off ⇒
 * {@code AccountSlackSyncJob} is a no-op and the account page is exactly as it was: the
 * timeline shows whatever digests already exist and produces no new ones.
 *
 * <p>The model knobs are NOT here — they are {@code @ConfigProperty} values on
 * {@code AccountSlackDigestService}, matching {@code SignalExtractionService}, because a
 * model id or a reasoning effort is a deployment decision, not a runtime toggle.
 */
@ApplicationScoped
public class AccountSlackFeatureFlag {

    static final String ENABLED_KEY = "crm.slack.account-spaces.enabled";

    @Inject
    AppSettingService appSettingService;

    /** The nightly channel read and digest armed. */
    public boolean isEnabled() {
        return appSettingService.findByKey(ENABLED_KEY)
                .map(AppSetting::getSettingValue)
                .map(String::trim)
                .map(Boolean::parseBoolean)
                .orElse(false);
    }
}
