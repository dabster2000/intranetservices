package dk.trustworks.intranet.aggregates.crm.calendar.dto;

/**
 * What one run of the calendar sync did. Logged, and returned by the manual trigger so an
 * admin can see whether a run found anything without reading CloudWatch.
 *
 * <p><b>The drop counters are not decoration.</b> Since the delivery and
 * colleague-at-client filters went in (decisions D1/D2, 2026-09-14) the sync throws away
 * most of what Graph hands it — 630 of 960 meetings on production, and 158 of 206 on Novo
 * Nordisk alone. Without the counters, "the filters are working" and "Graph returned
 * nothing tonight" produce the identical line {@code meetings=0}, and the only way to tell
 * them apart would be to run the job by hand and read the mailbox by eye.
 *
 * <p><b>And the read counters exist for the same reason one rung lower down.</b> Graph
 * pages {@code calendarView} and returns it oldest-first; for one release the sync asked
 * for a single page of 250 and never followed the continuation, so a busy mailbox kept its
 * earliest 250 events and lost the recent months without a single number moving.
 * {@code eventsSeen} says how much was read and only {@code readsTruncated} says whether
 * that was all of it.
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
 * @param internalDropped        meetings dropped because enough of the room was ours that
 *                               the event was us talking to ourselves (spec §4.2). The
 *                               threshold behind it is configuration, and this count is the
 *                               only way to tell whether the configured number is right
 * @param colleagueByPlacement   attendees identified as ours by the widened name rule —
 *                               first and last token with anything between, allowed only
 *                               for somebody placed at that client (spec §4.1 rule b). It
 *                               is the one rule here that could take a real client person
 *                               away, so it is counted apart from the strict ones: a count
 *                               that climbs unexpectedly is the evidence it has started to
 * @param graphPages             how many Graph requests the run made in total. Graph pages
 *                               calendarView and returns it OLDEST-first, so a read that
 *                               stops after one page keeps the earliest events and loses
 *                               the recent months — one page per mailbox is what that
 *                               failure looks like from outside
 * @param readsTruncated         mailboxes whose read stopped before Graph ran out of pages.
 *                               <b>This is the counter that makes {@code eventsSeen}
 *                               meaningful.</b> A truncated read and a complete one both
 *                               end in {@code events=N}; only this says whether N was all
 *                               of them, and anything but zero means the relationship graph
 *                               was built from a partial calendar
 * @param massMeetingsFlagged    meetings kept but stored with NO attendee rows because the
 *                               winning client sent a delegation at or above the threshold
 *                               (spec §4.2). A fifty-person event is activity with the
 *                               account and stays on its timeline; it is not evidence that
 *                               anybody knows the fifty people
 * @param massAttendeesSuppressed how many client attendee rows those meetings would
 *                               otherwise have written. The pair is what says whether the
 *                               threshold is right: one meeting suppressing forty-seven
 *                               people is the shape the rule exists for, and a count that
 *                               climbs against few meetings says it has been set too low
 */
public record CalendarSyncSummary(
        int mailboxes,
        int eventsSeen,
        int meetingsKept,
        int attendees,
        int failures,
        int deliveryFiltered,
        int colleagueFiltered,
        int colleagueEmailsLearned,
        int internalDropped,
        int colleagueByPlacement,
        int graphPages,
        int readsTruncated,
        int massMeetingsFlagged,
        int massAttendeesSuppressed) {

    /**
     * A run that never started — the kill switch is off, or no client domain is configured.
     *
     * <p>A named constant rather than a row of zeroes at each call site: the record has
     * grown twice now, and a positional literal is where a counter silently lands in the
     * wrong slot when it grows again.
     */
    public static CalendarSyncSummary nothing() {
        return new CalendarSyncSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
