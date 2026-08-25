package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Resolves which Slack channel a recruitment notification posts to
 * (plan §P12): a per-practice override when configured, otherwise the
 * default channel — so an unconfigured or freshly created practice needs
 * no setup (registry idiom). Settings are read from {@code app_settings}
 * on every call ({@code RecruitmentFeatureFlag} idiom, no cache), so a
 * routing change takes effect without a redeploy.
 * <ul>
 *   <li>{@code recruitment.slack.channel.default} — the shared channel ID
 *       (seeded blank by V443; blank = notifications are OFF even with
 *       the pipeline flag on).</li>
 *   <li>{@code recruitment.slack.channel.<practice_uuid>} — optional
 *       per-practice override, keyed by practice uuid exactly like the
 *       specialization catalogs (never by practice code).</li>
 *   <li>{@code recruitment.slack.channel.offer} — the HR-facing offer-phase
 *       channel (seeded blank by V532). When set, offer-phase moments (OFFER
 *       entry, contract sent/signed, conversion, onboarding uploads) post
 *       here INSTEAD of the practice/default channels; blank keeps every
 *       routing exactly as before the channel existed.</li>
 * </ul>
 */
@ApplicationScoped
public class RecruitmentSlackChannelRouter {

    public static final String DEFAULT_CHANNEL_KEY = "recruitment.slack.channel.default";
    public static final String OFFER_CHANNEL_KEY = "recruitment.slack.channel.offer";
    public static final String PRACTICE_CHANNEL_KEY_PREFIX = "recruitment.slack.channel.";

    @Inject
    AppSettingService appSettingService;

    /**
     * The channel for a notification about the given practice (nullable);
     * empty when nothing is configured — the caller skips posting.
     */
    public Optional<String> channelFor(String practiceUuid) {
        return practiceChannel(practiceUuid).or(this::defaultChannel);
    }

    /**
     * The practice's OWN channel — the override only, with no fall back to
     * the default. Callers that need to tell "this practice has a channel"
     * apart from "everything lands in the shared channel" use this:
     * the per-practice AI digest only runs for practices that have one, and
     * the discussion notifier lets a practice channel win over its own
     * {@code recruitment.slack.channel.discussion} setting.
     */
    public Optional<String> practiceChannel(String practiceUuid) {
        if (practiceUuid == null || practiceUuid.isBlank()
                // Practice keys are uuids; refuse the reserved suffixes so
                // a malformed caller can never read the shared channels here.
                || "default".equals(practiceUuid) || "discussion".equals(practiceUuid)
                || "offer".equals(practiceUuid)) {
            return Optional.empty();
        }
        return read(PRACTICE_CHANNEL_KEY_PREFIX + practiceUuid);
    }

    /** The shared fallback channel; empty ⇒ notifications are off. */
    public Optional<String> defaultChannel() {
        return read(DEFAULT_CHANNEL_KEY);
    }

    /**
     * The HR-facing offer-phase channel. Empty ⇒ the offer split is OFF:
     * offer-phase notifications keep flowing wherever they flowed before
     * this channel existed (practice/default channels, the hard-coded HR
     * fallback), and none of the offer-phase suppressions apply. Callers
     * therefore treat "present" as the master switch for the whole
     * offer-routing behavior — never just as an address.
     */
    public Optional<String> offerChannel() {
        return read(OFFER_CHANNEL_KEY);
    }

    private Optional<String> read(String key) {
        return appSettingService.findByKey(key)
                .map(AppSetting::getSettingValue)
                .map(String::trim)
                .filter(v -> !v.isEmpty());
    }
}
