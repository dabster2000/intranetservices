package dk.trustworks.intranet.aggregates.crm.slack.model.enums;

/**
 * What somebody decided about a company name the model read out of Slack that Intra could
 * not attribute to any client (spec §5.3).
 *
 * <p>The three values are the ones {@code UnmatchedDomainStatus} already settled on for the
 * calendar lane, and the two panels sit side by side on the Contacts view for exactly that
 * reason. This is a SEPARATE enum rather than a shared one because the two lanes are owned
 * and released independently, and a shared file would make each of them a compile-time
 * dependency of the other for no gain — the values happen to coincide, they are not the
 * same decision.
 */
public enum UnmatchedCompanyStatus {

    /** Nobody has looked at it yet. The only state the suggestion panel shows. */
    NEW,

    /** Never suggest this name again — a deny-list entry, not a deletion. */
    IGNORED,

    /**
     * It is a client Intra already has, or a prospect that was created for it. The row then
     * doubles as an ALIAS: from the next run the matcher resolves the name straight to
     * {@code linked_client_uuid} and the mentions land on the account instead of here.
     */
    LINKED
}
