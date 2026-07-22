package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * How the referrer knows the person they are referring (ATS spec §4.1).
 * Captured on submission; carried verbatim into reporting — the referral
 * leaderboard (P20) breaks down by relation.
 * <p>
 * Persisted as strings in {@code recruitment_referrals.referrer_relation}
 * — never rename a value once rows exist.
 */
public enum RecruitmentReferralRelation {

    /** A current colleague of the referred person. */
    COLLEAGUE,

    /** Worked together in the past. */
    FORMER_COLLEAGUE,

    /**
     * The reference comes from outside Trustworks (client contact, network
     * acquaintance) — pairs with {@code external_referrer_name}.
     */
    EXTERNAL_PARTNER,

    /** Any other relation. */
    OTHER
}
