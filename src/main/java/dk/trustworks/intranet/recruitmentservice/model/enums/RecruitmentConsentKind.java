package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * What a {@code recruitment_consents} row is consent FOR (spec §4.1).
 * <p>
 * Persisted as {@code VARCHAR(30)}; DB guard {@code chk_rcon_kind_enum}
 * (widened in V479) — adding a kind here requires widening that CHECK.
 */
public enum RecruitmentConsentKind {
    /** Keeping the candidate in the talent pool beyond the 6-month default. */
    TALENT_POOL_RETENTION,
    /**
     * The mandatory application checkbox: storing and processing the
     * application and its documents for up to 6 months after the
     * recruitment closes. No {@code expires_at} — the retention sweep
     * governs the actual deadline.
     */
    APPLICATION_PROCESSING,
    /**
     * The ISAE 3000 acknowledgment ticked at application time: the
     * candidate may be asked to present a clean criminal record
     * (straffeattest) during the process. Feeds the random-sample
     * check control; auditors read this row as proof the candidate was
     * informed up front.
     */
    CRIMINAL_RECORD_ACKNOWLEDGED
}
