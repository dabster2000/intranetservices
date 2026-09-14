package dk.trustworks.intranet.aggregates.crm.account.model.enums;

/**
 * What Trustworks is to a company today — the HISTORY axis (spec §2.1).
 *
 * <p><b>Derived, never stored.</b> There is no column, no form field and nothing to rot:
 * {@code AccountService.relationshipsForAll()} computes all four from consultant dates,
 * leads and the band on every read. A hand-maintained relationship status is exactly the
 * 0–4 Coffee-Clients ladder this design replaced.
 *
 * <p>The band (Strategic / Active / Backlog) stays the ATTENTION axis and is orthogonal:
 * the relationship never changes a band and a band never changes the relationship. A
 * former customer somebody is chasing reads <i>Former · win-back</i>, not <i>Prospect</i>
 * — the history is the fact, the band is the intent, and both show.
 *
 * <p>The rules are evaluated in declaration order, first match wins.
 */
public enum AccountRelationship {

    /** A consultant assignment ends today or later. */
    CUSTOMER,

    /** Not a customer, but a contract or a work row exists — we worked here once. */
    FORMER,

    /** Never billed, and either an open lead exists or somebody decided a band above Backlog. */
    PROSPECT,

    /** Never billed, no lead, Backlog. A company Intra knows and nothing is expected of. */
    CONTACT
}
