package dk.trustworks.intranet.aggregates.crm.bid.model.enums;

/** How a bid ended (CRM spec §3.6). Win rate counts only WON and LOST. */
public enum BidOutcome {

    /** Submitted, no answer yet. */
    OPEN,

    WON,

    LOST,

    /** We declined to bid. Set automatically when go/no-go is NOGO. */
    NOGO,

    /** The tender never materialised — neither a win nor a loss, and it must not count as one. */
    NOT_PUBLISHED
}
