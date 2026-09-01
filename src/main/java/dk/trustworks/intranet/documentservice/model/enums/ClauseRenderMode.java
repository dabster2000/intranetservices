package dk.trustworks.intranet.documentservice.model.enums;

/**
 * How a clause is rendered into a signing bundle (template-clauses spec D1
 * — the author decides per clause).
 */
public enum ClauseRenderMode {
    /** Woven into the main contract at the {@code {{CLAUSES}}} anchor. */
    INLINE,
    /** A numbered point in the combined "Tillæg til ansættelsesaftale". */
    ADDENDUM
}
