package dk.trustworks.intranet.aggregates.crm.calendar.dto;

/**
 * What one run of the calendar sync did. Logged, and returned by the manual trigger so an
 * admin can see whether a run found anything without reading CloudWatch.
 *
 * @param mailboxes     how many consenting mailboxes were read
 * @param eventsSeen    calendar events returned by Graph
 * @param meetingsKept  events with at least one attendee on a known client domain
 * @param attendees     external attendee rows written
 * @param failures      mailboxes Graph refused; the run continues past them
 */
public record CalendarSyncSummary(
        int mailboxes,
        int eventsSeen,
        int meetingsKept,
        int attendees,
        int failures) {
}
