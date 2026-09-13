package dk.trustworks.intranet.aggregates.crm.trustlink.model.enums;

/**
 * Which rung of the trustworker-name ladder produced the link from a TrustLink trustworker
 * name to an Intra {@code User}.
 *
 * <p>This column exists to make a wrong match diagnosable rather than mysterious. Two of
 * the four automatic rungs are frankly heuristics — they pair "Ditte Hjorth" with "Ditte
 * Marie Hjorth" and "Marie Dorthea" with "Marie Dorthea Sørensen" — and when somebody one
 * day reports that a colleague is credited with a relationship that is not theirs, the
 * first question is which rule made the claim. Measured against the 246 production users
 * the ladder resolved all 61 trustworkers: 38 by {@link #EMAIL}, 17 by {@link #FULLNAME},
 * 4 by {@link #FIRST_LAST}, 2 by {@link #PREFIX}.
 *
 * <p>The rungs run in order and each one requires a UNIQUE hit. A rule that matches two
 * different users yields no match at all — an unattributed connection is a small loss,
 * while attributing a client relationship to the wrong colleague is a claim the firm might
 * act on. {@link #MANUAL} beats all four, and a manual override mapping a name to no user
 * means "this name is deliberately unmapped, stop trying".
 *
 * <p>A row with no match method and no user is not a failure to be hidden: the external
 * person is real and so is the fact that somebody at Trustworks knows them, so the edge is
 * still shown under the raw TrustLink name.
 */
public enum MatchMethod {

    /** Rung 1: the TrustLink e-mail equals the user's e-mail, ignoring case. */
    EMAIL,

    /** Rung 2: the normalised full names are equal. */
    FULLNAME,

    /** Rung 3: first token and last token both equal, and only one user fits. */
    FIRST_LAST,

    /** Rung 4: the TrustLink tokens are a prefix subsequence of the user's, uniquely. */
    PREFIX,

    /** A row in {@code trustlink_trustworker_map}: a person decided this one by hand. */
    MANUAL
}
