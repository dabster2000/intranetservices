package dk.trustworks.intranet.aggregates.crm.person.model.enums;

/**
 * The three things that can prove two sightings of a person are the same person, within one
 * client (spec §3.1, V604).
 *
 * <h2>Why there are three and not one</h2>
 * Each source knows the person by something different and by nothing else. A calendar knows
 * an address and whatever the client's Exchange typed next to it; TrustLink knows a LinkedIn
 * person id and the person's own spelling of their name; a signal and a Slack reading know
 * only a name somebody said out loud. Keying the registry on any single one of those would
 * lose whichever sources do not carry it — which is exactly what the display-name key did
 * before this table existed, and why "Sif S. Broby Madsen" from a calendar and "Sif Broby
 * Madsen" from TrustLink were two people on one page (defect D8).
 *
 * <h2>The merge rule, stated once</h2>
 * Two sightings are one person when they share <b>any</b> identity row. That is a graph over
 * the sightings, not a comparison between two of them: an address links a calendar sighting
 * to another calendar sighting, a name key links either of them to a TrustLink one, and the
 * transitive closure is the person. {@code account_person_identity} is UNIQUE on
 * {@code (client_uuid, kind, value)}, so an address or a LinkedIn id belongs to at most one
 * person <i>on one account</i> — the same address at two clients is deliberately two
 * relationships, because a consultant who changed employer is not one.
 */
public enum AccountPersonIdentityKind {

    /**
     * A lower-cased e-mail address, from {@code account_meeting_attendee.email}. The
     * strongest of the three: two sightings on one address are the same human even when the
     * client spelled the name two different ways on two invitations.
     */
    EMAIL,

    /**
     * {@code trustlink_connection.person_id}, read only under an <b>enabled</b> alias. Also
     * strong, and the only thing that merges the 26 people TrustLink lists under more than
     * one company.
     */
    TRUSTLINK,

    /**
     * {@code PersonNames.key(name)} — lower-cased first token, a pipe, lower-cased last
     * token. The weakest and the most useful: it is the only identity a signal or a Slack
     * reading can produce at all, and dropping the middle is what makes two spellings of one
     * person merge. Two different humans sharing a first and a last name at one client land
     * on one row; §3.1 accepts that on purpose, because the alternative merges on something
     * softer and nothing on the page would ever show it had happened.
     */
    NAME
}
