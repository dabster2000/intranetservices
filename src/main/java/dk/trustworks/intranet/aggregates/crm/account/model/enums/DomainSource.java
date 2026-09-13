package dk.trustworks.intranet.aggregates.crm.account.model.enums;

/**
 * Where a client e-mail domain came from.
 *
 * <p>Worth keeping apart: {@link #SEEDED} domains were inferred by V585 from whatever
 * address billing happens to use, and are a guess. {@link #MANUAL} domains are something
 * a person asserted. When the calendar join attributes a meeting to the wrong account,
 * this column is the first thing to look at.
 */
public enum DomainSource {

    /** Inferred by the V585 migration from the client's billing e-mail addresses. */
    SEEDED,

    /** Typed by a person on the client form. */
    MANUAL
}
