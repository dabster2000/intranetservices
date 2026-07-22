package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * How a candidate entered the funnel (spec §4.1). Mandatory on every ATS
 * create; the structured follow-up (channel, event name, job-listing ref,
 * reference name) lives in the candidate's {@code source_detail} JSON —
 * one enum + one adaptive follow-up instead of ten mostly-empty fields.
 * <p>
 * Persisted as {@code VARCHAR(20)}; DB guard {@code chk_rc_source_enum}.
 */
public enum CandidateSource {
    /** Referred by an employee or external reference. */
    REFERRAL,
    /** Partner mandate referral — drives the P4 reject-block for non-recruiters. */
    PARTNER_REFERRAL,
    /** Actively sourced on LinkedIn (paste-URL import; no LinkedIn API). */
    LINKEDIN_SEARCH,
    /** Applied via a LinkedIn ad (public form entry channel, P5). */
    LINKEDIN_AD,
    /** Applied via the website form (public form entry channel, P5). */
    WEBSITE,
    /** Applied via a Jobindex listing (public form entry channel, P5). */
    JOBINDEX,
    /** Social media (channel in {@code source_detail}). */
    SOME,
    /** Met at an external conference (name in {@code source_detail}). */
    CONFERENCE,
    /** Met at a Trustworks event (name in {@code source_detail}). */
    TW_EVENT,
    OTHER;

    /**
     * GDPR Art. 14 applies when personal data was NOT collected from the
     * data subject: referrals (data from the referrer) and sourcing
     * (LinkedIn search, social-media sourcing). Direct applications
     * (website, ads, job boards) and in-person meetings (conferences,
     * TW events) are Art. 13 — the candidate supplied the data.
     * <p>
     * Used at create time to set {@code art14_required} and
     * {@code art14_deadline} (created_at + 30 days). The clock reactor
     * that acts on the deadline arrives in P19.
     */
    public boolean requiresArt14Notice() {
        return this == REFERRAL
                || this == PARTNER_REFERRAL
                || this == LINKEDIN_SEARCH
                || this == SOME;
    }
}
