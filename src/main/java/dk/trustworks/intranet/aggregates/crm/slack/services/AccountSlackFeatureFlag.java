package dk.trustworks.intranet.aggregates.crm.slack.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The master switches for the two CRM Slack lanes (CRM spec §4.9; source channels §4.1) —
 * the {@link dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag}
 * shape: read from {@code app_settings} on every call (tiny table, no caching), missing
 * or unparseable ⇒ {@code false}. Every Slack feature in this repo is opt-in, and these
 * read client channels and spend model tokens nightly, so there has to be a way to stop
 * them without a deploy.
 *
 * <p>{@code crm.slack.account-spaces.enabled} is seeded {@code 'false'} by V594. Off ⇒
 * {@code AccountSlackSyncJob} is a no-op and the account page is exactly as it was: the
 * timeline shows whatever digests already exist and produces no new ones.
 *
 * <p>{@code crm.slack.source-channels.enabled} is the second lane's own switch, deliberately
 * not shared with the first: a general channel is read for every account at once and is the
 * noisier and costlier of the two, so the spend that needs stopping in a hurry is usually
 * this one alone. Off ⇒ {@code SlackSourceSyncJob} is a no-op and the mentions already
 * filed stay on their timelines.
 *
 * <p>The model knobs are NOT here — they are {@code @ConfigProperty} values on
 * {@code AccountSlackDigestService} and its source-channel sibling, matching
 * {@code SignalExtractionService}, because a model id or a reasoning effort is a deployment
 * decision, not a runtime toggle.
 */
@ApplicationScoped
public class AccountSlackFeatureFlag {

    static final String ENABLED_KEY = "crm.slack.account-spaces.enabled";

    static final String SOURCE_CHANNELS_KEY = "crm.slack.source-channels.enabled";

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

    /** The nightly read of the general source channels and the mention extraction armed. */
    public boolean isSourceChannelsEnabled() {
        return appSettingService.findByKey(SOURCE_CHANNELS_KEY)
                .map(AppSetting::getSettingValue)
                .map(String::trim)
                .map(Boolean::parseBoolean)
                .orElse(false);
    }
}
