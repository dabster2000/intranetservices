package dk.trustworks.intranet.aggregates.crm.bid.model.enums;

/**
 * Whether we decided to bid.
 *
 * <p>Worth recording separately from the outcome: the tenders we chose NOT to write are
 * evidence about our own judgement, and a bid record that only held the ones we sent would
 * make the firm look like it never said no.
 */
public enum BidGoNoGo {

    GO,

    /** We declined. {@code BidService} forces the outcome to NOGO — a bid we never wrote has no result. */
    NOGO,

    /** Not decided yet. */
    PENDING
}
