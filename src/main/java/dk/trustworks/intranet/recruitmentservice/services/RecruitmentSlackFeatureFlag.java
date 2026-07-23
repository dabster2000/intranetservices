package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Single source of truth for the recruitment module's Slack companion
 * toggles (Slack spec §3.1, plan §1.6) — sibling of
 * {@link RecruitmentAiFeatureFlag}, same shape by design: read from
 * {@code app_settings} on every call (tiny table, no caching), missing
 * or unparseable value ⇒ {@code false} (every Slack feature is opt-in).
 * <p>
 * These toggles gate <em>side effects</em> (inbound dispatch, reactor
 * posts, scheduled DMs) — there is deliberately NO admin bypass at this
 * level, matching the AI companion.
 * <ul>
 *   <li>{@code recruitment.slack.interactivity.enabled} — <b>master
 *       kill switch for ALL inbound handling</b> (P13). Off ⇒ the
 *       callback endpoints answer 200 + a "currently disabled"
 *       ephemeral for interactive payloads and silently ignore events
 *       (Slack requires 200 to stop retries — a non-200 causes retry
 *       storms). Outbound reactors (P12) are unaffected.</li>
 *   <li>{@code recruitment.slack.cards.enabled} — living threaded
 *       candidate cards; seeded, inert until P22.</li>
 *   <li>{@code recruitment.slack.partner-channels.enabled} —
 *       partner-track private channels; seeded, inert until P22.</li>
 *   <li>{@code recruitment.slack.refer.enabled} — {@code /refer} slash
 *       command + modal; seeded, inert until P14.</li>
 *   <li>{@code recruitment.slack.triage-actions.enabled} — referral
 *       triage buttons; seeded, inert until P14.</li>
 *   <li>{@code recruitment.slack.capture.enabled} — "Log to candidate
 *       timeline" message shortcut; seeded, inert until P14.</li>
 *   <li>{@code recruitment.slack.lookup.enabled} — {@code /candidates}
 *       ephemeral lookup; seeded, inert until P14.</li>
 *   <li>{@code recruitment.slack.scorecard.enabled} — scorecard modal +
 *       the scorecard button on the nudge/kit DMs (P18). Off ⇒ those DMs
 *       are deep-link-only (the explicit degradation chain).</li>
 *   <li>{@code recruitment.slack.app-home.enabled} — App Home
 *       dashboard; seeded, inert until P23.</li>
 *   <li>{@code recruitment.slack.morning-brief.enabled} — morning
 *       interviewer briefs; seeded, inert until P23.</li>
 *   <li>{@code recruitment.slack.dpo-digest.enabled} — DPO exception
 *       digest; seeded, inert until P24.</li>
 *   <li>{@code recruitment.slack.assistant.enabled} — @Recruiting
 *       assistant; seeded, inert until P25.</li>
 * </ul>
 * All twelve are seeded {@code 'false'}, category {@code 'recruitment'},
 * by V444. The P12 channel-routing settings
 * ({@code recruitment.slack.channel.*}) are deliberately NOT flags and
 * not read here — routing lives in {@code RecruitmentSlackChannelRouter}.
 */
@ApplicationScoped
public class RecruitmentSlackFeatureFlag {

    static final String INTERACTIVITY_KEY = "recruitment.slack.interactivity.enabled";
    static final String CARDS_KEY = "recruitment.slack.cards.enabled";
    static final String PARTNER_CHANNELS_KEY = "recruitment.slack.partner-channels.enabled";
    static final String REFER_KEY = "recruitment.slack.refer.enabled";
    static final String TRIAGE_ACTIONS_KEY = "recruitment.slack.triage-actions.enabled";
    static final String CAPTURE_KEY = "recruitment.slack.capture.enabled";
    static final String LOOKUP_KEY = "recruitment.slack.lookup.enabled";
    static final String SCORECARD_KEY = "recruitment.slack.scorecard.enabled";
    static final String APP_HOME_KEY = "recruitment.slack.app-home.enabled";
    static final String MORNING_BRIEF_KEY = "recruitment.slack.morning-brief.enabled";
    static final String DPO_DIGEST_KEY = "recruitment.slack.dpo-digest.enabled";
    static final String ASSISTANT_KEY = "recruitment.slack.assistant.enabled";

    @Inject
    AppSettingService appSettingService;

    /** Master kill switch for all inbound Slack handling (P13). */
    public boolean isInteractivityEnabled() {
        return readFlag(INTERACTIVITY_KEY);
    }

    /** Living threaded candidate cards — toggle exists, feature ships in P22. */
    public boolean isCardsEnabled() {
        return readFlag(CARDS_KEY);
    }

    /** Partner-track private channels — toggle exists, feature ships in P22. */
    public boolean isPartnerChannelsEnabled() {
        return readFlag(PARTNER_CHANNELS_KEY);
    }

    /** {@code /refer} slash command + modal — toggle exists, feature ships in P14. */
    public boolean isReferEnabled() {
        return readFlag(REFER_KEY);
    }

    /** Referral triage buttons — toggle exists, feature ships in P14. */
    public boolean isTriageActionsEnabled() {
        return readFlag(TRIAGE_ACTIONS_KEY);
    }

    /** "Log to candidate timeline" message shortcut — toggle exists, feature ships in P14. */
    public boolean isCaptureEnabled() {
        return readFlag(CAPTURE_KEY);
    }

    /** {@code /candidates} ephemeral lookup — toggle exists, feature ships in P14. */
    public boolean isLookupEnabled() {
        return readFlag(LOOKUP_KEY);
    }

    /** Scorecard modal + the scorecard button on the nudge/kit DMs (P18). */
    public boolean isScorecardEnabled() {
        return readFlag(SCORECARD_KEY);
    }

    /** App Home dashboard — toggle exists, feature ships in P23. */
    public boolean isAppHomeEnabled() {
        return readFlag(APP_HOME_KEY);
    }

    /** Morning interviewer briefs — toggle exists, feature ships in P23. */
    public boolean isMorningBriefEnabled() {
        return readFlag(MORNING_BRIEF_KEY);
    }

    /** DPO exception digest — toggle exists, feature ships in P24. */
    public boolean isDpoDigestEnabled() {
        return readFlag(DPO_DIGEST_KEY);
    }

    /** @Recruiting assistant — toggle exists, feature ships in P25. */
    public boolean isAssistantEnabled() {
        return readFlag(ASSISTANT_KEY);
    }

    private boolean readFlag(String key) {
        Optional<AppSetting> setting = appSettingService.findByKey(key);
        return setting
                .map(AppSetting::getSettingValue)
                .map(Boolean::parseBoolean)
                .orElse(Boolean.FALSE);
    }
}
