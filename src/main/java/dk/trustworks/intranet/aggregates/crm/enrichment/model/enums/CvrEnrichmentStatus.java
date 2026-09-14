package dk.trustworks.intranet.aggregates.crm.enrichment.model.enums;

/**
 * The state of a client's CVR verification (V610).
 *
 * <p>{@link #needsAttention()} is the set a person has to act on: a candidate to confirm,
 * a CVR to type in, a stored CVR the registry does not know, a CVR another client already
 * carries, or a lookup that keeps failing. {@code VERIFIED}, {@code DISMISSED} and
 * {@code SKIPPED} are quiet; {@code PENDING} is the nightly job's queue.
 */
public enum CvrEnrichmentStatus {
    PENDING,
    VERIFIED,
    /** The AI proposed a CVR whose registry name does not match the client's — a person decides. */
    CANDIDATE,
    /** No CVR on the row, and the AI could not find one it trusted. */
    NOT_FOUND,
    /** The registry knows no company for the stored CVR. */
    INVALID,
    /** The CVR (stored or proposed) is already on another client. */
    DUPLICATE,
    /** The lookup itself failed; retried after {@code retry-after-days}. */
    FAILED,
    /** A person said the proposed CVR is wrong, or that this row has none. */
    DISMISSED,
    /** Not a Danish row — Virkdata covers Denmark only. */
    SKIPPED;

    public boolean needsAttention() {
        return this == CANDIDATE || this == NOT_FOUND || this == INVALID || this == DUPLICATE || this == FAILED;
    }

    /** Unknown or null reads as {@link #PENDING}: the row is simply not done yet. */
    public static CvrEnrichmentStatus parse(String raw) {
        if (raw == null || raw.isBlank()) return PENDING;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
