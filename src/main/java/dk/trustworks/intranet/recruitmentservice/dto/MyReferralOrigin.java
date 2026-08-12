package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * How a "My referrals" row came to be — a read-model concept only, which is
 * why it lives in {@code dto} and not in {@code model.enums}: nothing is
 * persisted with these values.
 * <p>
 * The referrer↔candidate link exists in two places in this system, and both
 * are legitimate:
 * <ul>
 *   <li>{@link #REFERRAL_FORM} — a {@code recruitment_referrals} row, i.e.
 *       the employee submitted the refer form (web or the Slack
 *       {@code /refer} twin) and a recruiter triaged it;</li>
 *   <li>{@link #RECORDED_ON_CANDIDATE} — no referral row exists; the link is
 *       {@code recruitment_candidates.referred_by_user_uuid}, set either by
 *       the Airtable migration or by a recruiter picking "Referred by
 *       (colleague)" in the new-candidate dialog.</li>
 * </ul>
 * Before this discriminator existed, "My referrals" only read the first
 * source, so every referrer whose link was recorded the second way saw an
 * empty page while the candidate profile — and the referrer's own Slack
 * milestone DMs, which have always keyed on the candidate column — said
 * otherwise.
 */
public enum MyReferralOrigin {

    /** Backed by a {@code recruitment_referrals} row submitted by the referrer. */
    REFERRAL_FORM,

    /**
     * Backed only by {@code recruitment_candidates.referred_by_user_uuid}.
     * Such rows have no {@code referrerRelation} and no {@code whyText} —
     * nobody ever filled in the refer form — and their date is when the
     * candidate was registered, not when a referral was sent.
     */
    RECORDED_ON_CANDIDATE
}
