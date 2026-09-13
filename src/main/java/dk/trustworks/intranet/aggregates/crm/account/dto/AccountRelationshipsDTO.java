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
 * </ul>
 *
 * <p>An empty graph is a real and common answer — nobody in the account's mailboxes has
 * consented, or nobody has filed a signal. The tab says so rather than drawing nothing.
 */
public record AccountRelationshipsDTO(
        List<PersonDTO> trustworksPeople,
        List<ExternalPersonDTO> externalPeople,
        List<RelationEdgeDTO> edges,
        /** How many of the account's Trustworks people have consented to calendar reads. */
        int consentedPeople,
        int totalPeople) {

    /**
     * Someone at the client. {@code role} is whatever a signal said their role was; a
     * person known only from a calendar has no role, because a calendar does not carry one.
     */
    public record ExternalPersonDTO(String name, String role, String initials) {
    }

    /**
     * One line of the graph.
     *
     * @param meetings how many meetings the two were both in; 0 for a signal-only edge
     * @param lastMet  the most recent of those meetings, or null for a signal-only edge
     * @param knowsVia the relation text from a signal ("Hans knows Benny from school"),
     *                 or null for a meeting edge
     */
    public record RelationEdgeDTO(
            String twPersonName,
            String externalName,
            int meetings,
            LocalDate lastMet,
            String knowsVia) {
    }
}
