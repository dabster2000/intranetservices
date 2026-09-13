package dk.trustworks.intranet.aggregates.crm.bid.model.enums;

/** What kind of document we wrote (CRM spec §3.6). Danish names, because that is what they are called. */
public enum BidType {

    /** An offer we wrote because a client asked for one. */
    TILBUD,

    /** A public tender with a formal process. */
    UDBUD,

    /** A mini-tender under an existing SKI framework. */
    SKI_MINI
}
