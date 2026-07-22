package dk.trustworks.intranet.recruitmentservice.events;

/**
 * Lifecycle of the {@code pii} JSON section of a recruitment event
 * (spec §3.3). The {@code payload}/{@code pii} split is the anonymization
 * contract: GDPR anonymization (P19) rewrites {@code pii} to
 * <code>{"anonymized": true}</code> and flips this state — the event row,
 * its ordering, type, and structural payload are never deleted.
 */
public enum RecruitmentPiiState {
    /** The pii section contains personal data. */
    PRESENT,
    /** The pii section has been rewritten by GDPR anonymization. */
    ANONYMIZED,
    /** The event never carried personal data (pii is null). */
    NONE
}
