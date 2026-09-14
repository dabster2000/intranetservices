package dk.trustworks.intranet.aggregates.crm.calendar.services;

import java.time.LocalDate;
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

    /**
     * One sighting of a domain no {@code client_domain} row claims (spec §2.5).
     *
     * @param domain        lower-cased; a company, never a person
     * @param graphEventId  what makes the sighting idempotent — the same event re-read on
     *                      the next run is the same sighting, not a second meeting
     * @param occurredOn    the day of the meeting
     */
    record UnmatchedDomainSighting(String domain, String graphEventId, LocalDate occurredOn) { }

    /** Keyed by address so one mailbox seeing Malthe in nine meetings writes one row. */
    private final Map<String, LearnedColleagueEmail> learnedEmails = new LinkedHashMap<>();

    /**
     * Keyed by (event, domain) so one meeting with three people from dsb.dk is one
     * sighting of dsb.dk, not three.
     */
    private final Map<String, UnmatchedDomainSighting> unmatchedDomains = new LinkedHashMap<>();

    private int newlyLearnedEmails;
    private int deliveryDropped;
    private int colleagueOnlyDropped;
    private int internalDropped;
    private int colleagueByPlacement;

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

    /**
     * Records a domain on this event that no client claims.
     *
     * <p>Called for every attendee whose domain is not in the index, on events that are
     * kept as well as on events that are dropped: a meeting with both a client and an
     * unknown company still says somebody keeps meeting the unknown company.
     */
    void unmatchedDomain(String domain, String graphEventId, LocalDate occurredOn) {
        if (domain == null || graphEventId == null || occurredOn == null) {
            return;
        }
        unmatchedDomains.putIfAbsent(graphEventId + "|" + domain,
                new UnmatchedDomainSighting(domain, graphEventId, occurredOn));
    }

    Collection<UnmatchedDomainSighting> unmatchedDomains() {
        return unmatchedDomains.values();
    }

    boolean hasUnmatchedDomains() {
        return !unmatchedDomains.isEmpty();
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

    /**
     * A meeting dropped because enough of the room was ours that it was us talking to
     * ourselves (spec §4.2).
     *
     * <p>Counted rather than silent because the threshold is configuration: the only way to
     * know whether 8 is the right number is to watch this count move against
     * {@code meetings}. 53 of Banedanmark's 60 meetings and most of Arba Security's 77 have
     * eight or more of ours in them.
     */
    void internalDropped() {
        internalDropped++;
    }

    /**
     * An attendee identified as one of ours by the widened name rule — first and last token
     * with anything between, allowed only because the person has a placement at this client
     * (spec §4.1 rule b).
     *
     * <p>This is the one rule in the filter that could in principle take a real client
     * person away, which is exactly why it is counted separately from the strict rules: a
     * count that suddenly climbs is the evidence that it has started to. On production it
     * should account for 66 attendee rows across five people.
     */
    void colleagueByPlacement() {
        colleagueByPlacement++;
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

    int internalDroppedCount() {
        return internalDropped;
    }

    int colleagueByPlacementCount() {
        return colleagueByPlacement;
    }
}
