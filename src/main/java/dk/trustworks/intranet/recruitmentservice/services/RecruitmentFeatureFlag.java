package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Single source of truth for the recruitment module's core feature flags.
 * Read from the {@code app_settings} table on every call (settings tables
 * are tiny; caching is unnecessary and would only complicate the
 * invalidation story when an admin toggles a flag).
 * <p>
 * Every flag defaults to {@code false} when the row is missing or the value
 * is unparseable — recruitment features are opt-in.
 * <ul>
 *   <li>{@code recruitment.dossier.enabled} — the pre-ATS dossier feature
 *       ({@link #isEnabled()}).</li>
 *   <li>{@code recruitment.pipeline.enabled} — ATS expansion core flag 1
 *       (spec §11), gating the P2–P10 surfaces starting with the positions
 *       page ({@link #isPipelineEnabled()}). Seeded {@code false} by V433.</li>
 *   <li>{@code recruitment.interviews.enabled} — ATS expansion core flag 2
 *       (spec §11), gating the P11 interviews surfaces and scheduling
 *       affordances ({@link #isInterviewsEnabled()}). Seeded {@code false}
 *       by V433.</li>
 *   <li>{@code recruitment.apply.referrer-claim.enabled} — the public
 *       form's "do you know anyone at Trustworks?" question, the
 *       directory matching behind it and the notice to the named
 *       employee ({@link #isApplyReferrerClaimEnabled()}). Seeded
 *       {@code false} by V549; a GDPR launch gate (see the method).</li>
 *   <li>{@code recruitment.gdpr.enabled} — ATS expansion core flag 3
 *       (spec §11), gating the P19 GDPR engine ({@link #isGdprEnabled()}).
 *       Seeded {@code false} by V433. <b>Enabling this flag is the moment
 *       automatic deletion starts</b> (plan §P19): the nightly sweep begins
 *       anonymizing candidates past their retention deadline. Enable on
 *       staging for at least two sweep cycles before production.</li>
 * </ul>
 */
@ApplicationScoped
public class RecruitmentFeatureFlag {

    static final String SETTING_KEY = "recruitment.dossier.enabled";
    static final String FACTS_SETTING_KEY = "recruitment.facts.enabled";
    static final String INTERVIEW_ROOM_SETTING_KEY = "recruitment.interview-room.enabled";
    static final String PIPELINE_SETTING_KEY = "recruitment.pipeline.enabled";
    static final String INTERVIEWS_SETTING_KEY = "recruitment.interviews.enabled";
    static final String GDPR_SETTING_KEY = "recruitment.gdpr.enabled";
    static final String APPLY_REFERRER_CLAIM_KEY = "recruitment.apply.referrer-claim.enabled";

    @Inject
    AppSettingService appSettingService;

    /**
     * @return true iff the {@code recruitment.dossier.enabled} setting is
     *         present and parses to {@code true} via
     *         {@link Boolean#parseBoolean(String)}; false otherwise
     *         (missing row, null value, or any non-"true" value).
     */
    public boolean isEnabled() {
        return readFlag(SETTING_KEY);
    }

    /**
     * @return true iff the {@code recruitment.pipeline.enabled} setting is
     *         present and parses to {@code true}; false otherwise. Same
     *         missing-means-off semantics as {@link #isEnabled()}.
     */
    public boolean isPipelineEnabled() {
        return readFlag(PIPELINE_SETTING_KEY);
    }

    /**
     * @return true iff the {@code recruitment.interviews.enabled} setting is
     *         present and parses to {@code true}; false otherwise. Same
     *         missing-means-off semantics as {@link #isEnabled()}.
     */
    public boolean isInterviewsEnabled() {
        return readFlag(INTERVIEWS_SETTING_KEY);
    }

    /**
     * @return true iff the {@code recruitment.gdpr.enabled} setting is
     *         present and parses to {@code true}; false otherwise. Same
     *         missing-means-off semantics as {@link #isEnabled()}. Gates
     *         every P19 side effect — renewal emails, consent expiry and
     *         auto-anonymization (the sweep) as well as the DPO actions
     *         and the public consent page.
     */
    public boolean isGdprEnabled() {
        return readFlag(GDPR_SETTING_KEY);
    }

    /**
     * @return true iff {@code recruitment.facts.enabled} parses to true —
     *         the Interview Room slice-0 gate (fact vocabulary, ledger,
     *         completeness rings). Seeded {@code false} by V534.
     */
    public boolean isFactsEnabled() {
        return readFlag(FACTS_SETTING_KEY);
    }

    /**
     * @return true iff {@code recruitment.interview-room.enabled} parses to
     *         true — the Interview Room slice-1 gate (the room page and its
     *         endpoints). Seeded {@code false} by V534.
     */
    public boolean isInterviewRoomEnabled() {
        return readFlag(INTERVIEW_ROOM_SETTING_KEY);
    }

    /**
     * @return true iff {@code recruitment.apply.referrer-claim.enabled}
     *         parses to true — the single gate for change request (e),
     *         "do you know anyone at Trustworks?" (2026-09-01). Seeded
     *         {@code false} by V549.
     *         <p>
     *         <b>This is a legal launch gate, not a rollout knob.</b> The
     *         public privacy policy currently scopes processing to
     *         "oplysninger, du selv giver os" — data the applicant gives
     *         about THEMSELVES. The question asks for a colleague's name,
     *         which is personal data about a third party who was never
     *         informed (Art. 14) and who becomes the subject of an
     *         unverified assertion. Do not enable in production until the
     *         rewritten policy is live and the DPO has signed off.
     *         <p>
     *         Off ⇒ the question is not published on the form, an
     *         {@code answer_KNOWS_SOMEONE} posted anyway is dropped, no
     *         referrer column is written, no name reaches the matcher or
     *         OpenAI, and the notification reactor returns silently.
     */
    public boolean isApplyReferrerClaimEnabled() {
        return readFlag(APPLY_REFERRER_CLAIM_KEY);
    }

    private boolean readFlag(String key) {
        Optional<AppSetting> setting = appSettingService.findByKey(key);
        return setting
                .map(AppSetting::getSettingValue)
                .map(Boolean::parseBoolean)
                .orElse(Boolean.FALSE);
    }
}
