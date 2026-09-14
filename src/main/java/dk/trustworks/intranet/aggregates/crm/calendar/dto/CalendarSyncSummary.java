package dk.trustworks.intranet.aggregates.crm.calendar.dto;

/**
 * What one run of the calendar sync did. Logged, and returned by the manual trigger so an
 * admin can see whether a run found anything without reading CloudWatch.
 *
 * <p><b>The three drop counters are not decoration.</b> Since the delivery and
 * colleague-at-client filters went in (decisions D1/D2, 2026-09-14) the sync throws away
 * most of what Graph hands it — 630 of 960 meetings on production, and 158 of 206 on Novo
 * Nordisk alone. Without the counters, "the filters are working" and "Graph returned
 * nothing tonight" produce the identical line {@code meetings=0}, and the only way to tell
 * them apart would be to run the job by hand and read the mailbox by eye.
 *
 * @param mailboxes              how many consenting mailboxes were read
 * @param eventsSeen             calendar events returned by Graph
 * @param meetingsKept           events actually written as account meetings
 * @param attendees              external attendee rows written
 * @param failures               mailboxes Graph refused; the run continues past them
 * @param deliveryFiltered       meetings dropped because the mailbox owner was on a
 *                               contract with that client on the day — delivery, not sales
 * @param colleagueFiltered      meetings dropped because every attendee on a client domain
 *                               turned out to be one of our own consultants sitting there
 * @param colleagueEmailsLearned client addresses newly identified as a colleague's and
 *                               written to {@code crm_colleague_client_email}; this settles
 *                               to zero once the population is known, so a run that is
 *                               still learning is a run whose filter is still improving
 */
public record CalendarSyncSummary(
        int mailboxes,
        int eventsSeen,
        int meetingsKept,
        int attendees,
        int failures,
        int deliveryFiltered,
        int colleagueFiltered,
        int colleagueEmailsLearned) {
}
