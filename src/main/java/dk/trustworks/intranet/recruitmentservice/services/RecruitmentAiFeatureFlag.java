package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Single source of truth for the recruitment module's AI companion
 * toggles (AI spec §11, plan §1.6) — sibling of
 * {@link RecruitmentFeatureFlag}, same shape by design: read from
 * {@code app_settings} on every call (tiny table, no caching), missing
 * or unparseable value ⇒ {@code false} (every AI feature is opt-in).
 * <p>
 * These toggles gate <em>side effects</em> (reactor OpenAI calls) —
 * there is deliberately NO admin bypass at this level. Resource-level
 * feature guards keep the standard 404 + {@code admin:*} convention;
 * reactors read the flags literally.
 * <ul>
 *   <li>{@code recruitment.ai.intake.enabled} — smart intake chips (P9).</li>
 *   <li>{@code recruitment.ai.brief.enabled} — candidate brief (P9).</li>
 *   <li>{@code recruitment.ai.referral-triage.enabled} — referral triage
 *       assist (P9).</li>
 *   <li>{@code recruitment.ai.email-composer.enabled} — AI email draft
 *       button in the compose dialog (P16).</li>
 *   <li>{@code recruitment.ai.digest.weekly-funnel.enabled} — seeded,
 *       inert until P24.</li>
 *   <li>{@code recruitment.ai.digest.rejection-patterns.enabled} —
 *       seeded, inert until P24.</li>
 * </ul>
 * All six are seeded {@code 'false'}, category {@code 'recruitment'},
 * by V440.
 */
@ApplicationScoped
public class RecruitmentAiFeatureFlag {

    static final String INTAKE_KEY = "recruitment.ai.intake.enabled";
    static final String BRIEF_KEY = "recruitment.ai.brief.enabled";
    static final String REFERRAL_TRIAGE_KEY = "recruitment.ai.referral-triage.enabled";
    static final String EMAIL_COMPOSER_KEY = "recruitment.ai.email-composer.enabled";
    static final String WEEKLY_FUNNEL_DIGEST_KEY = "recruitment.ai.digest.weekly-funnel.enabled";
    static final String REJECTION_PATTERNS_DIGEST_KEY = "recruitment.ai.digest.rejection-patterns.enabled";

    @Inject
    AppSettingService appSettingService;

    /** Smart intake chips (CV/answers → suggested candidate fields). */
    public boolean isIntakeEnabled() {
        return readFlag(INTAKE_KEY);
    }

    /** AI-generated candidate brief (descriptive bullets). */
    public boolean isBriefEnabled() {
        return readFlag(BRIEF_KEY);
    }

    /** Referral triage assist (practice/experience/teamlead suggestions). */
    public boolean isReferralTriageEnabled() {
        return readFlag(REFERRAL_TRIAGE_KEY);
    }

    /** AI email composer — the draft endpoint + compose-dialog button (P16). */
    public boolean isEmailComposerEnabled() {
        return readFlag(EMAIL_COMPOSER_KEY);
    }

    /** Weekly funnel narrative digest — toggle exists, feature ships in P24. */
    public boolean isWeeklyFunnelDigestEnabled() {
        return readFlag(WEEKLY_FUNNEL_DIGEST_KEY);
    }

    /** Quarterly rejection-pattern digest — toggle exists, feature ships in P24. */
    public boolean isRejectionPatternsDigestEnabled() {
        return readFlag(REJECTION_PATTERNS_DIGEST_KEY);
    }

    private boolean readFlag(String key) {
        Optional<AppSetting> setting = appSettingService.findByKey(key);
        return setting
                .map(AppSetting::getSettingValue)
                .map(Boolean::parseBoolean)
                .orElse(Boolean.FALSE);
    }
}
