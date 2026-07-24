package dk.trustworks.intranet.recruitmentservice.reporting;

/**
 * Fact keys of the P20 monthly reporting projection
 * ({@code recruitment_fact_monthly.fact}). Names are persisted verbatim —
 * never rename a value once projected (or rebuild after doing so).
 * <p>
 * Which dimension columns a fact uses is documented per constant; unused
 * dimensions hold the {@code ''} sentinel (V449 header).
 */
public enum ReportingFact {

    /** One candidate entered the database. Dims: source. */
    CANDIDATE_CREATED,

    /** One application attached to a position. Dims: position/practice/track, source, detail=origin. */
    APPLICATION_CREATED,

    /**
     * One stage move. Dims: position/practice/track, stage_from, stage_to,
     * outcome=direction (FORWARD|BACK|SKIP...). sum_days accumulates the time
     * spent in stage_from (fractional days).
     */
    STAGE_MOVED,

    /**
     * One application reached a terminal. Dims: position/practice/track,
     * source, stage_from, outcome=REJECTED|WITHDRAWN|RETURNED_TO_POOL,
     * detail=rejection reason code. sum_days = time spent in stage_from.
     */
    TERMINAL,

    /** One hire. Dims: position/practice/track, source, person=referrer (when referred). */
    HIRED,

    /** One scorecard submitted. Dims: position/practice/track, person=interviewer, outcome=origin (web|slack). */
    SCORECARD_SUBMITTED,

    /** One referral submitted. Dims: person=referrer, outcome=origin (web|slack). */
    REFERRAL_SUBMITTED,

    /** One referral triaged. Dims: person=referrer, outcome=CANDIDATE_CREATED|DISMISSED, detail=origin. */
    REFERRAL_TRIAGED,

    // --- GDPR compliance counters (all: cnt only) ------------------------
    /** Dims: detail=channel (EMAIL|MANUAL). */
    ART14_NOTICE_SENT,
    /** Dims: outcome=consent kind. */
    CONSENT_GRANTED,
    /** Dims: outcome=consent kind. */
    CONSENT_WITHDRAWN,
    /** Dims: outcome=consent kind. */
    CONSENT_EXPIRED,
    /** Dims: outcome=mode (AUTO|ON_REQUEST). */
    ANONYMIZED,
    DSAR_RECEIVED,
    DSAR_EXPORTED
}
