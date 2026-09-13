package dk.trustworks.intranet.aggregates.crm.account.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * The relationship graph for one account (CRM spec §3.7): Trustworks people on one side,
 * people at the client on the other, and what connects them.
 *
 * <p>Every node and every edge is derived. There is no contact table and nobody maintains
 * this by hand:
 * <ul>
 *   <li>{@code MET} edges come from {@code account_meeting} — calendar metadata from
 *       consenting mailboxes, attributed to the account by the attendee's e-mail domain.</li>
 *   <li>{@code KNOWS} edges come from {@code account_signal}: a colleague said how they
 *       know somebody, and the line they wrote is the edge's label.</li>
 *   <li>{@code CONNECTED} edges come from {@code trustlink_connection_trustworker} — the
 *       nightly mirror of TrustLink's LinkedIn graph. Weakest of the three: a connection
 *       is an accepted invitation, possibly a decade old, not evidence of a conversation.
 *       It is also by far the widest, which is why {@link #externalTotal} exists.</li>
 * </ul>
 *
 * <p>An empty graph is a real and common answer — nobody in the account's mailboxes has
 * consented, nobody has filed a signal, and no TrustLink company name maps to this client.
 * The tab says so rather than drawing nothing.
 */
public record AccountRelationshipsDTO(
        List<PersonDTO> trustworksPeople,
        List<ExternalPersonDTO> externalPeople,
        List<RelationEdgeDTO> edges,
        /** How many of the account's Trustworks people have consented to calendar reads. */
        int consentedPeople,
        /**
         * People on the Trustworks side who have an Intra user, i.e. who could consent at
         * all. A TrustLink trustworker name that matched no user is still drawn in the
         * graph but is deliberately not counted here: it would push "3 of 5 share calendar
         * metadata" permanently below 100% for a person who has no account to consent with.
         */
        int totalPeople,
        /**
         * How many external people the account has in total, before the display cap.
         * Novo Nordisk alone carries 203 tier-5 TrustLink connections; the graph draws a
         * dozen, and the tab needs this to say "showing 12 of 203" rather than implying
         * that a dozen is all there is.
         */
        int externalTotal) {

    /**
     * Someone at the client. {@code role} is whatever a signal said their role was, or the
     * job title TrustLink carries; a person known only from a calendar has no role, because
     * a calendar does not carry one. {@code linkedInUrl} is set only for people TrustLink
     * knows — it is the one field that lets a colleague act on the connection.
     */
    public record ExternalPersonDTO(String name, String role, String initials, String linkedInUrl) {
    }

    /**
     * One line of the graph.
     *
     * @param meetings    how many meetings the two were both in; 0 for a signal- or
     *                    connection-only edge
     * @param lastMet     the most recent of those meetings, or null when the edge is not a
     *                    meeting
     * @param knowsVia    the relation text from a signal ("Hans knows Benny from school"),
     *                    or null for a meeting or connection edge
     * @param source      {@link #MET}, {@link #KNOWS} or {@link #CONNECTED} — which record
     *                    produced this edge. The frontend orders and labels by it, because
     *                    a meeting last week and a LinkedIn connection from 2013 are not
     *                    the same claim and must not render the same.
     * @param connectedOn the date the LinkedIn connection was accepted, for
     *                    {@link #CONNECTED} edges only. May be null: TrustLink does not
     *                    have it for every connection.
     */
    public record RelationEdgeDTO(
            String twPersonName,
            String externalName,
            int meetings,
            LocalDate lastMet,
            String knowsVia,
            String source,
            LocalDate connectedOn) {

        /** They were both in a meeting — the strongest evidence the graph has. */
        public static final String MET = "MET";

        /** A colleague filed a signal saying how they know this person. */
        public static final String KNOWS = "KNOWS";

        /** They are connected on LinkedIn, per TrustLink. Weakest, and by far the most numerous. */
        public static final String CONNECTED = "CONNECTED";
    }
}
