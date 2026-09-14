package dk.trustworks.intranet.aggregates.crm.calendar.model.enums;

/**
 * What somebody decided about a meeting domain Intra could not attribute (spec §2.5).
 *
 * <p>Three states because there are exactly three useful answers to "we keep meeting
 * dsb.dk and Intra does not know who that is".
 */
public enum UnmatchedDomainStatus {

    /** Nobody has looked at it yet. The only state the panel shows. */
    NEW,

    /** Never suggest this domain again — a per-domain deny-list, not a deletion. */
    IGNORED,

    /**
     * It belongs to a client Intra already has; the domain was added to that client, so
     * from the next sync its meetings land on that account and it stops being unmatched.
     */
    LINKED
}
