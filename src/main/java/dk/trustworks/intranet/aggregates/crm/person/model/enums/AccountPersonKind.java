package dk.trustworks.intranet.aggregates.crm.person.model.enums;

/**
 * What one row in {@code account_person} turned out to be: somebody at the client, a former
 * colleague of ours now working there, or one of us (spec §3.2, V604).
 *
 * <h2>Why "one of us" is a stored value and not a deletion</h2>
 * The defect this whole cut exists to fix is our own consultants being drawn on an account
 * as its client contacts — Sara Vest on Banedanmark with 53 meetings, Stephan Jensen on Novo
 * Nordisk. The obvious fix is to drop those sightings on the floor during the rebuild, and
 * it is the wrong one: the rebuild never deletes, so a name it silently refused to
 * materialise last night would be re-examined from scratch tonight, and any run whose
 * colleague directory was momentarily thin — a status row not yet written, a placement not
 * yet filed — would quietly promote our own consultant back onto the page as a stranger.
 *
 * <p>{@link #COLLEAGUE} is therefore a decision the registry <b>remembers</b>. The row is
 * built, classified and kept, and the read side drops it. That also makes the
 * misclassification visible to anybody querying the table, which a dropped sighting never is.
 *
 * <h2>COLLEAGUE never leaves the backend</h2>
 * The wire contract says {@code kind} is {@code "CONTACT" | "ALUMNI"}; a {@code COLLEAGUE}
 * row must not appear in {@code people[]}, in an edge, or in any count on any tab. Every
 * read over {@code account_person} has to filter it out explicitly — the column's default is
 * {@code CONTACT}, so forgetting the filter does not fail, it leaks a colleague.
 *
 * <h2>ALUMNI is the warm one</h2>
 * A former colleague who stayed at the client is one of the warmest contacts the firm has
 * (defect D4), which is why {@code RelationshipWarmth} clamps such a person to tier 3 however
 * quiet their edges are. The distinction between {@link #ALUMNI} and {@link #COLLEAGUE} is
 * asked about <b>today</b>, not about the day of the meeting, so a re-hire flips to
 * {@code COLLEAGUE} and a leaver flips to {@code ALUMNI} at the next rebuild (defect D2).
 */
public enum AccountPersonKind {

    /** Somebody at the client. No {@code user} row matched their name. The common case. */
    CONTACT,

    /**
     * A former colleague, now at the client. {@code alumni_user_uuid} names the {@code user}
     * row. Shown, with a "left 2019" chip, and ranked warm.
     */
    ALUMNI,

    /**
     * One of ours, employed today. {@code alumni_user_uuid} names the {@code user} row.
     * <b>Never shown</b> — the row exists so the rebuild remembers that this name at this
     * client is not a client person.
     */
    COLLEAGUE
}
