package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * What a {@code recruitment_consents} row is consent FOR (spec §4.1).
 * v1 has exactly one kind; the enum exists so new kinds (e.g. marketing
 * contact) can be added without schema changes.
 * <p>
 * Persisted as {@code VARCHAR(30)}; DB guard {@code chk_rcon_kind_enum}.
 */
public enum RecruitmentConsentKind {
    /** Keeping the candidate in the talent pool beyond the 6-month default. */
    TALENT_POOL_RETENTION
}
