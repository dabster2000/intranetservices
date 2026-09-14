package dk.trustworks.intranet.aggregates.crm.enrichment.model.enums;

/**
 * The state of a client's sector check (V610). The check only ever runs for a client
 * filed under {@code OTHER}; a person's own choice of sector is final.
 */
public enum SectorEnrichmentStatus {
    PENDING,
    /** The model agreed: the client belongs under OTHER. Re-checked only if the row changes. */
    CONFIRMED_OTHER,
    /** The model chose a sector and the job applied it — shown on the account page. */
    CHANGED,
    /** A person chose a sector other than OTHER; nothing to check. */
    HUMAN_SET,
    /** A person put an AI-set client back to OTHER. Never re-checked automatically. */
    HUMAN_OVERRIDE,
    /** The model call failed or answered nothing usable; retried after {@code retry-after-days}. */
    FAILED,
    SKIPPED;

    public boolean needsAttention() {
        return this == FAILED;
    }

    public static SectorEnrichmentStatus parse(String raw) {
        if (raw == null || raw.isBlank()) return PENDING;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
