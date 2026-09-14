package dk.trustworks.intranet.aggregates.crm.calendar.services;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one mailbox's pass learned and threw away.
 *
 * <p>{@link AccountCalendarSyncService#toMeeting} answers with a meeting or with null, and
 * null on its own cannot say WHY. That mattered the moment the filters went in: on
 * production they drop 630 of 960 meetings, so an operator reading the nightly log needs
 * to be able to tell "the filters worked" from "Graph returned nothing" — two states that
 * otherwise produce the identical line {@code meetings=0}. This carries the reasons out.
 *
 * <p>It also carries the colleague addresses the name match identified, because those have
 * to be WRITTEN — see {@link CalendarFilterService#rememberColleagueEmails} — and
 * {@code toMeeting} is deliberately pure, with no {@code EntityManager} anywhere near it.
 * A meeting that was dropped still teaches an address: the drop is precisely the evidence
 * that the address is a colleague's.
 *
 * <p>Mutable and short-lived: one per mailbox, handed to {@code toMeeting} per event, read
 * once when the mailbox's short write transaction opens.
 */
final class CalendarSyncTally {

    /**
     * One address a colleague holds at a client, ready to be written to
     * {@code crm_colleague_client_email}.
     *
     * @param email       lower-cased; the primary key of the table
     * @param userUuid    the Trustworks employee whose address it is
     * @param displayName the name Graph gave it, kept only so a human reading the table can
     *                    tell what it is looking at
     */
    record LearnedColleagueEmail(String email, String userUuid, String displayName) { }

    /** Keyed by address so one mailbox seeing Malthe in nine meetings writes one row. */
    private final Map<String, LearnedColleagueEmail> learnedEmails = new LinkedHashMap<>();

    private int newlyLearnedEmails;
    private int deliveryDropped;
    private int colleagueOnlyDropped;

    /**
     * Records an address identified by name.
     *
     * @param learned the address and whose it is
     * @param isNew   whether the run had never seen it before, from
     *                {@link CalendarFilters#rememberColleagueEmail}
     */
    void learnedColleagueEmail(LearnedColleagueEmail learned, boolean isNew) {
        if (learned == null || learned.email() == null || learned.userUuid() == null) {
            return;
        }
        learnedEmails.putIfAbsent(learned.email(), learned);
        if (isNew) {
            newlyLearnedEmails++;
        }
    }

    /** A meeting dropped because the mailbox owner was on a contract with that client (D1). */
    void deliveryDropped() {
        deliveryDropped++;
    }

    /**
     * A meeting dropped because every attendee on a client domain turned out to be one of
     * our own consultants sitting at that client (D2). 39 meetings in production.
     */
    void colleagueOnlyDropped() {
        colleagueOnlyDropped++;
    }

    Collection<LearnedColleagueEmail> learnedEmails() {
        return learnedEmails.values();
    }

    boolean hasLearnedEmails() {
        return !learnedEmails.isEmpty();
    }

    int newlyLearnedEmails() {
        return newlyLearnedEmails;
    }

    int deliveryDroppedCount() {
        return deliveryDropped;
    }

    int colleagueOnlyDroppedCount() {
        return colleagueOnlyDropped;
    }
}
