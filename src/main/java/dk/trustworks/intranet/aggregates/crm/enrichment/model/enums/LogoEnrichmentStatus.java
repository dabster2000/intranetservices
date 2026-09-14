package dk.trustworks.intranet.aggregates.crm.enrichment.model.enums;

/**
 * The state of a client's logo as far as the nightly job is concerned (V610).
 *
 * <p>Only {@link #FAILED} needs a person: neither the web nor the image model produced
 * anything storable, so somebody has to upload one. {@code FOUND} and {@code GENERATED}
 * are shown on the account page as the logo's provenance, not as a problem.
 */
public enum LogoEnrichmentStatus {
    PENDING,
    /** A logo was already stored when the job looked — nothing to do. */
    PRESENT,
    /** Downloaded from a page the AI found; {@code logo_source_url} says where. */
    FOUND,
    /** Drawn by the image model because nothing usable was found online. */
    GENERATED,
    /** Neither found nor generated; retried after {@code retry-after-days}. */
    FAILED,
    /** Not a CLIENT row — partners and prospects are out of scope. */
    SKIPPED;

    public boolean needsAttention() {
        return this == FAILED;
    }

    /** True for the two states where the logo on the row came from the job. */
    public boolean isAiProvided() {
        return this == FOUND || this == GENERATED;
    }

    public static LogoEnrichmentStatus parse(String raw) {
        if (raw == null || raw.isBlank()) return PENDING;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
