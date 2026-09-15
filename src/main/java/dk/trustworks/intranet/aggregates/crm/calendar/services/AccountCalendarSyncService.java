package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.account.services.AccountService;
import dk.trustworks.intranet.aggregates.crm.calendar.dto.CalendarSyncSummary;
import dk.trustworks.intranet.aggregates.crm.calendar.model.AccountMeeting;
import dk.trustworks.intranet.aggregates.crm.calendar.model.AccountMeetingAttendee;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarSyncTally.LearnedColleagueEmail;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.graph.GraphCalendarClient;
import dk.trustworks.intranet.graph.GraphMailboxConcurrencyLimiter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads metadata from enabled calendars and produces per-account meeting evidence.
 * No Graph call runs inside a database transaction. A per-mailbox generation fences both
 * consent changes and concurrent imports; only complete reads may reconcile missing rows.
 *
 * <p>Current SALES/PARTNER owners meeting an actual starred person may bypass recurrence
 * and delivery exclusions. Privacy, participant identity and mass/internal guards remain
 * mandatory. Excluded recurring/delivery contacts can be reviewed without inventing MET edges.
 */
@JBossLog
@ApplicationScoped
public class AccountCalendarSyncService {

    /**
     * Identity, series membership, bounds, the cancellation flag, the owner's answer and the
     * attendees. Never subject, never body.
     *
     * <p>{@code iCalUId} is the meeting across mailboxes (the timeline folds on it);
     * {@code type} and {@code seriesMasterId} are what say an event is an occurrence of a
     * series; {@code responseStatus} is the owner's own answer. All four are metadata about
     * the calendar item, not about what was said in it.
     */
    static final String SELECT = "id,iCalUId,type,seriesMasterId,start,end,isCancelled,responseStatus,attendees,sensitivity,organizer";

    /** A full read: a mailbox's first run, Sundays, a backfill, or a manual {@code ?full=true}. */
    static final int FULL_READ_BACK_DAYS = 365;

    /** Every other night. Long enough that a late cancellation or a moved meeting is caught. */
    static final int INCREMENTAL_BACK_DAYS = 14;

    /**
     * The night every mailbox is re-read over the whole window.
     *
     * <p>A fortnight's re-read keeps the last two weeks honest; it does nothing for a
     * meeting from March that a contract entered in September now makes delivery, or for a
     * rule that changed. Once a week the whole year is re-judged, on the quietest night
     * there is, and a week is the longest anything stale survives.
     */
    static final DayOfWeek FULL_READ_DAY = DayOfWeek.SUNDAY;

    /** Graph's {@code responseStatus.response} for an invitation the mailbox owner refused. */
    static final String DECLINED_RESPONSE = "declined";

    /** {@code account_meeting.ical_uid} is VARCHAR(255); a longer value is stored as unknown, never cut. */
    static final int MAX_ICAL_UID_LENGTH = 255;

    /**
     * The page size asked of calendarView. Graph documents 1 to 1000; 250 is the size the
     * rooms lookup already uses and has never been the bound on anything.
     *
     * <p><b>A page size, never a limit.</b> It was read as a limit for one release and the
     * cost was the whole point of the feature: Graph returns calendarView OLDEST-FIRST, so
     * a mailbox with more than 250 events in the 455-day first-run window kept the earliest
     * 250 and the read stopped — the months that vanished were the RECENT ones. The stored
     * meetings showed the cliff plainly (Sep 2025: 12, falling to Jul: 1, Aug: 0), and the
     * COO, who demonstrably met clients that month, was invisible on their accounts. The
     * continuation is followed in {@link #readCalendar} until Graph stops offering one.
     */
    static final int PAGE_SIZE = 250;

    /**
     * Runaway guard on the continuation loop — not an expected bound.
     *
     * <p>Forty pages is 10,000 events in a 455-day window: twenty-two meetings every single
     * day for fifteen months, which no mailbox in this firm has. Hitting it therefore means
     * a continuation that never terminates, not a busy person, and the loop stops rather
     * than reading Graph forever inside the nightly job. Because the guard cannot be told
     * apart from a complete read by the event count alone, a truncated read is COUNTED —
     * see {@link CalendarSyncTally#readTruncated()} — so "we read everything" and "we
     * stopped early" can never render as the same line.
     */
    static final int MAX_PAGES = 40;

    /**
     * The {@code attendee.type} Microsoft Graph puts on a meeting room or a piece of
     * equipment. The other values are {@code required} and {@code optional}, and a null
     * type is a normal attendee — Graph omits it on events created outside Outlook.
     */
    static final String RESOURCE_ATTENDEE_TYPE = "resource";

    /**
     * Our own tenant, exactly as {@link CalendarUnmatchedDomainFilter} draws it: the bare
     * domain, which lives in {@code AccountService.DENIED_DOMAINS} alongside the freemail
     * hosts, plus the two suffix forms.
     *
     * <p><b>These two lists and that one move together or they leave a hole.</b> The filter
     * asks "is this domain worth suggesting as a new client", which folds our own tenant in
     * with freemail and conferencing tooling and cannot answer the question here; this asks
     * "is this person one of ours", and only our own domains answer it. The cross-check is
     * pinned by a test rather than by a comment — every domain this recognises must be one
     * {@code CalendarUnmatchedDomainFilter.isSuggestable} refuses.
     */
    static final String OWN_TENANT_DOMAIN = "trustworks.dk";

    /** Both carry a leading dot, so {@code notonmicrosoft.com} is not us. */
    static final List<String> OWN_TENANT_DOMAIN_SUFFIXES = List.of(".trustworks.dk", ".onmicrosoft.com");

    private static final DateTimeFormatter GRAPH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Inject
    @RestClient
    GraphCalendarClient graphClient;

    @Inject
    CalendarConsentService consentService;

    @Inject
    AccountService accountService;

    @Inject
    CalendarFilterService filterService;

    @Inject
    CalendarSuggestionService suggestionService;

    @Inject
    GraphMailboxConcurrencyLimiter limiter;

    @Inject
    jakarta.persistence.EntityManager em;

    @Inject
    CalendarSyncStateService stateService;

    @Inject
    CalendarCandidateService candidateService;

    @Inject
    dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService personService;

    @ConfigProperty(name = "dk.trustworks.crm.calendar.sync.enabled", defaultValue = "false")
    boolean syncEnabled;

    /**
     * How many of our own people in a room make the event internal (spec §4.2).
     *
     * <p>Eight, and it is configuration rather than a constant on purpose: the only honest
     * way to know whether eight is right is to watch {@code internalDropped} move against
     * {@code meetings} in the nightly line, and re-tuning it must not need a release. A
     * value of zero or less switches the rule off rather than dropping everything — a typo
     * in a config map should cost the firm an unfiltered night, not every meeting it has.
     */
    @ConfigProperty(name = "dk.trustworks.crm.calendar.internal-meeting.min-own-attendees", defaultValue = "8")
    int internalMeetingMinOwnAttendees;

    /**
     * How many people from ONE client in a room make the event a mass event rather than
     * evidence that anybody knows anybody (spec §4.2, the other side of the same coin).
     *
     * <p>The internal rule above is one-sided: it catches "a meeting that was really ours
     * with a guest on it" and says nothing about "a mass event at the client where we were
     * the guests". Both are the same category. One event at Banedanmark with fifty people
     * in it stored forty-seven client attendee rows, and every one of those forty-seven
     * then read "met x1 — 228 d ago" on the account as though somebody had actually talked
     * to them. Firm-wide, ten meetings with eleven or more attendees produced 132 of the 330
     * client attendee rows: eight per cent of the meetings, forty per cent of the people.
     *
     * <p>Ten, and configuration for the same reason the internal threshold is: the honest
     * way to know whether ten is right is to watch {@code massMeetingsFlagged} move against
     * {@code meetings} in the nightly line and re-tune it without a release. On production
     * ten catches delegations of 47, 27, 14 and 14 and leaves 9 and below alone. Zero or
     * less switches the rule off rather than suppressing every attendee the firm has.
     */
    @ConfigProperty(name = "dk.trustworks.crm.calendar.mass-meeting.min-client-attendees", defaultValue = "10")
    int massMeetingMinClientAttendees;

    /**
     * One full pass over every consenting mailbox — the nightly job's entry point.
     *
     * <p>A mailbox Graph refuses does not stop the run. One person on holiday with a
     * broken licence must not cost the firm its whole relationship graph, so failures are
     * counted and logged and the loop continues.
     */
    public CalendarSyncSummary syncAll() {
        return syncAll(false);
    }

    /**
     * One full pass over every consenting mailbox.
     *
     * @param forceFullRead read every mailbox over the whole {@value #FULL_READ_BACK_DAYS}-day
     *                      window rather than letting {@link #fullReadDue} decide per mailbox
     *                      — the manual trigger's {@code ?full=true}, for the day a rule
     *                      changes and nobody wants to wait for Sunday
     */
    public CalendarSyncSummary syncAll(boolean forceFullRead) {
        if (!syncEnabled) {
            log.info("Account calendar sync is switched off (dk.trustworks.crm.calendar.sync.enabled=false)");
            return CalendarSyncSummary.nothing();
        }

        Map<String, String> domainIndex = QuarkusTransaction.requiringNew().call(accountService::domainIndex);
        if (domainIndex.isEmpty()) {
            log.info("Account calendar sync: no client domains configured, nothing can be attributed");
            return CalendarSyncSummary.nothing();
        }

        // Contracts, colleagues and learned addresses: read once, used by every mailbox.
        // Per mailbox these would be a hundred repetitions of the same answer, and they
        // cannot be read at all once the Graph loop is running without breaking the rule
        // that no transaction is open during a Graph call.
        CalendarFilters filters = QuarkusTransaction.requiringNew().call(filterService::load);

        // One clock bounds all mailboxes; integer generations independently identify their writes.
        Instant runStart = Instant.now();

        Set<String> mailboxes = QuarkusTransaction.requiringNew().call(consentService::consentedUserUuids);
        int eventsSeen = 0;
        int meetingsKept = 0;
        int attendees = 0;
        int failures = 0;
        int deliveryFiltered = 0;
        int colleagueFiltered = 0;
        int emailsLearned = 0;
        int internalDropped = 0;
        int colleagueByPlacement = 0;
        int graphPages = 0;
        int readsTruncated = 0;
        int massMeetingsFlagged = 0;
        int massAttendeesSuppressed = 0;
        int recurringDropped = 0;
        int declinedDropped = 0;
        int staleRemoved = 0;
        int fullReads = 0;

        for (String userUuid : mailboxes) {
            try {
                MailboxResult result = syncMailbox(userUuid, domainIndex, filters, runStart, forceFullRead);
                eventsSeen += result.eventsSeen();
                meetingsKept += result.meetingsKept();
                attendees += result.attendees();
                deliveryFiltered += result.deliveryFiltered();
                colleagueFiltered += result.colleagueFiltered();
                emailsLearned += result.colleagueEmailsLearned();
                internalDropped += result.internalDropped();
                colleagueByPlacement += result.colleagueByPlacement();
                graphPages += result.graphPages();
                readsTruncated += result.readsTruncated();
                massMeetingsFlagged += result.massMeetingsFlagged();
                massAttendeesSuppressed += result.massAttendeesSuppressed();
                recurringDropped += result.recurringDropped();
                declinedDropped += result.declinedDropped();
                staleRemoved += result.staleRemoved();
                fullReads += result.fullRead() ? 1 : 0;
            } catch (RuntimeException e) {
                failures++;
                // The message, not the stack, and never the event payload: a Graph error
                // body can echo back calendar content.
                log.warnf("Account calendar sync failed for mailbox %s: SYNC_FAILED", userUuid);
            }
        }

        // Rows ahead of the run's clock can never be right — the window closes at it — and
        // they go for EVERY mailbox, read tonight or not. A mailbox that opted out keeps what
        // was read while it was on, and "what was read" means meetings that happened; a row
        // for a meeting that was going to happen is not history anybody agreed to keep.
        int futureRemoved = QuarkusTransaction.requiringNew().call(() -> (int) purgeFuture(runStart));

        // Registry rebuild follows committed metadata; the UI must not wait for another nightly job.
        var registry = personService.rebuildAll();
        if (registry.failures() > 0 || registry.status()
                != dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.RebuildSummary.Status.RAN) {
            throw new IllegalStateException("REGISTRY_REBUILD_FAILED");
        }

        // The per-domain counts are recomputed from the ledger once, after every mailbox
        // has written its sightings — never incremented as they arrive, because the
        // incremental window re-reads the same fortnight every night.
        QuarkusTransaction.requiringNew().run(suggestionService::refreshAggregates);

        // graphPages and readsTruncated ride in the same line as the drop counters and for
        // the same reason: a truncated read and a complete one both end in "events=N", and
        // the truncated one is the shape that quietly loses the recent half of a calendar.
        // readsTruncated=0 is the assertion that events=N is ALL of them — and, since a
        // truncated read never reconciles, that staleRemoved was decided on whole calendars.
        log.infof("Account calendar sync done: mailboxes=%d events=%d meetings=%d attendees=%d failures=%d "
                        + "deliveryFiltered=%d colleagueFiltered=%d colleagueEmailsLearned=%d "
                        + "internalDropped=%d colleagueByPlacement=%d graphPages=%d readsTruncated=%d "
                        + "massMeetingsFlagged=%d massAttendeesSuppressed=%d "
                        + "recurringDropped=%d declinedDropped=%d staleRemoved=%d futureRemoved=%d fullReads=%d "
                        + "(internalThreshold=%d massThreshold=%d)",
                mailboxes.size(), eventsSeen, meetingsKept, attendees, failures,
                deliveryFiltered, colleagueFiltered, emailsLearned,
                internalDropped, colleagueByPlacement, graphPages, readsTruncated,
                massMeetingsFlagged, massAttendeesSuppressed,
                recurringDropped, declinedDropped, staleRemoved, futureRemoved, fullReads,
                internalMeetingMinOwnAttendees, massMeetingMinClientAttendees);
        return new CalendarSyncSummary(mailboxes.size(), eventsSeen, meetingsKept, attendees, failures,
                deliveryFiltered, colleagueFiltered, emailsLearned, internalDropped, colleagueByPlacement,
                graphPages, readsTruncated, massMeetingsFlagged, massAttendeesSuppressed,
                recurringDropped, declinedDropped, staleRemoved, futureRemoved, fullReads);
    }

    record MailboxResult(int eventsSeen,
                         int meetingsKept,
                         int attendees,
                         int deliveryFiltered,
                         int colleagueFiltered,
                         int colleagueEmailsLearned,
                         int internalDropped,
                         int colleagueByPlacement,
                         int graphPages,
                         int readsTruncated,
                         int massMeetingsFlagged,
                         int massAttendeesSuppressed,
                         int recurringDropped,
                         int declinedDropped,
                         int staleRemoved,
                         boolean fullRead) {

        static MailboxResult incomplete() {
            return new MailboxResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, false);
        }

        static MailboxResult nothing() {
            return new MailboxResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        }
    }

    /** Reserve, read outside a transaction, then commit and reconcile under the same mailbox fence. */
    MailboxResult syncMailbox(String userUuid, Map<String, String> domainIndex, CalendarFilters filters,
                              Instant runStart, boolean forceFullRead) {
        String principal = QuarkusTransaction.requiringNew().call(() -> mailboxAddressOf(userUuid));
        // The process-local limiter is acquired before reservation: a skipped duplicate must
        // not invalidate the generation belonging to the reader already using this mailbox.
        if (principal != null && !limiter.tryAcquire(principal)) return MailboxResult.incomplete();
        CalendarSyncStateService.Lease lease = null;
        try {
            lease = stateService.reserve(userUuid, runStart);
            if (lease == null) return MailboxResult.nothing();
            if (principal == null) {
                stateService.fail(lease, "NO_MAILBOX");
                throw new IllegalStateException("NO_MAILBOX");
            }
            CalendarSyncStateService.Lease reservation = lease;
            // Read changeable rules AFTER reserving. A rule edit before this lease is observed;
            // an edit after it invalidates the lease, so old rules cannot mark recovery complete.
            Map<String, String> currentDomains = QuarkusTransaction.requiringNew().call(accountService::domainIndex);
            QuarkusTransaction.requiringNew().run(() -> filterService.refreshCommercialStars(filters));
            LocalDate runDay = LocalDate.ofInstant(runStart, CalendarTime.ZONE);
            boolean full = fullReadDue(forceFullRead, lease.recoveryRequired(), false, runDay);
            ReadWindow window = ReadWindow.of(runStart, full);
            LocalDateTime syncedAt = LocalDateTime.ofInstant(runStart, ZoneOffset.UTC);
            CalendarSyncTally tally = new CalendarSyncTally();
            CalendarRead read = readCalendar(userUuid, principal, window.graphFrom(), window.graphTo(),
                    () -> stateService.canRead(reservation));
            tally.graphPagesFetched(read.pages());
            if (!read.complete()) tally.readTruncated();
            Map<String, PendingMeeting> uniquePending = new LinkedHashMap<>();
            for (var event : read.events()) {
                if (!hasEnded(event, runStart)) continue;
                for (PendingMeeting meeting : toMeetings(userUuid, event, currentDomains, filters, tally,
                        internalMeetingMinOwnAttendees, massMeetingMinClientAttendees)) {
                    uniquePending.put(meeting.uuid(), meeting);
                }
            }
            List<PendingMeeting> pending = List.copyOf(uniquePending.values());
            CalendarSyncStateService.Completion committed = stateService.complete(lease, full, read.complete(), () -> {
                persist(pending, syncedAt, reservation.generation());
                filterService.rememberColleagueEmails(tally.learnedEmails(), syncedAt);
                suggestionService.record(userUuid, tally.unmatchedDomains(), syncedAt, reservation.generation(),
                        window.fromWallClock(), window.toWallClock(), read.complete());
                candidateService.record(userUuid, reservation.generation(), tally.candidates(),
                        window.fromWallClock(), window.toWallClock(), read.complete());
                AccountMeeting.flush();
                int removed = read.complete() ? (int) reconcile(userUuid, window, reservation.generation()) : 0;
                int kept = (int) AccountMeeting.count("userUuid = ?1 and syncGeneration = ?2", userUuid, reservation.generation());
                int attendeeRows = ((Number) AccountMeeting.getEntityManager().createNativeQuery("""
                        select count(*) from account_meeting_attendee a
                        join account_meeting m on m.uuid = a.meeting_uuid
                        where m.user_uuid = :user and m.sync_generation = :generation
                        """).setParameter("user", userUuid).setParameter("generation", reservation.generation())
                        .getSingleResult()).intValue();
                return new CalendarSyncStateService.SyncCounts(kept, attendeeRows, removed);
            });
            if (!committed.applied()) {
                boolean stillEnabled = QuarkusTransaction.requiringNew().call(() -> consentService.isEnabled(userUuid));
                return stillEnabled ? MailboxResult.incomplete() : MailboxResult.nothing();
            }
            return new MailboxResult(read.events().size(), committed.counts().meetings(),
                    committed.counts().attendees(), tally.deliveryDroppedCount(), tally.colleagueOnlyDroppedCount(),
                    tally.newlyLearnedEmails(), tally.internalDroppedCount(), tally.colleagueByPlacementCount(),
                    tally.graphPagesCount(), tally.readsTruncatedCount(), tally.massMeetingsFlaggedCount(),
                    tally.massAttendeesSuppressedCount(), tally.recurringDroppedCount(), tally.declinedDroppedCount(),
                    committed.counts().staleRemoved(), full);
        } catch (RuntimeException failure) {
            stateService.fail(lease, principal == null ? "NO_MAILBOX" : "SYNC_FAILED");
            throw failure;
        } finally {
            if (principal != null) limiter.release(principal);
        }
    }

    /**
     * Whether this mailbox is read over the whole year tonight.
     *
     * <p>Four reasons, any one of which is enough, and each is a different kind of fact:
     * <ul>
     *   <li><b>forced</b> — the manual trigger said so, because a rule changed today;</li>
     *   <li><b>first run</b> — a mailbox with no rows needs a year of history for "who last
     *       saw them" to mean anything on day one;</li>
     *   <li><b>backfill</b> — rows from before V614 that still lack their identity;</li>
     *   <li><b>the weekly night</b> — {@link #FULL_READ_DAY}, so nothing stale survives a
     *       week and a contract entered late still takes its meetings off the account.</li>
     * </ul>
     * Pure, so the fast tier can pin all four without a database.
     *
     * @param runDay the run's date in the calendar's own zone — a run at 02:20 Copenhagen is
     *               Sunday's run even though the JVM's UTC clock still says Saturday
     */
    static boolean fullReadDue(boolean forced, boolean firstRun, boolean backfillNeeded, LocalDate runDay) {
        return forced || firstRun || backfillNeeded
                || (runDay != null && runDay.getDayOfWeek() == FULL_READ_DAY);
    }

    /**
     * The window one mailbox is read over: a number of days back from the run's clock, and
     * never past it.
     *
     * <p>The bounds go to Graph in UTC with no offset, because that is how Graph reads a
     * bound with no offset — the documentation is explicit that the Prefer header does not
     * change it. Formatting the JVM's local wall clock, as the first version did, only
     * worked because the container happens to run on UTC. The same bounds go to the
     * reconcile as Copenhagen wall-clock times, because that is what {@code occurred_at} is.
     *
     * @param from the instant the window opens
     * @param to   the run's clock, where it closes
     * @param full whether this is the whole {@value #FULL_READ_BACK_DAYS}-day window
     */
    record ReadWindow(Instant from, Instant to, boolean full) {

        static ReadWindow of(Instant runStart, boolean full) {
            int days = full ? FULL_READ_BACK_DAYS : INCREMENTAL_BACK_DAYS;
            Instant boundary = runStart.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            return new ReadWindow(boundary.minus(Duration.ofDays(days)), boundary, full);
        }

        String graphFrom() {
            return graphTime(from);
        }

        String graphTo() {
            return graphTime(to);
        }

        LocalDateTime fromWallClock() {
            return CalendarTime.wallClock(from);
        }

        LocalDateTime toWallClock() {
            return CalendarTime.wallClock(to);
        }
    }

    /** An instant as Graph reads a window bound: UTC wall clock, no offset. */
    static String graphTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(GRAPH_TIME);
    }

    /**
     * Has this event ended, on its own clock, by the moment the run started?
     *
     * <p>The event's end, in the zone Graph stamped on it, against the run's instant — two
     * absolute points in time, so the JVM's zone and the calendar's zone cannot disagree.
     * An event with no end is judged on its start; an event with neither cannot be shown to
     * have ended and is not kept, which is also what {@link #toMeeting} would decide about
     * a start-less event.
     */
    static boolean hasEnded(GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event, Instant runStart) {
        if (event == null || runStart == null) {
            return false;
        }
        Instant end = instantOf(event.end());
        if (end == null) {
            end = instantOf(event.start());
        }
        return end != null && !end.isAfter(runStart);
    }

    /** A Graph {@code dateTimeTimeZone} as an instant, or null when it cannot be read. */
    static Instant instantOf(GraphCalendarClient.CalendarViewResponse.GraphDateTime value) {
        LocalDateTime local = parseGraphTime(value);
        if (local == null) {
            return null;
        }
        return local.atZone(CalendarTime.zoneOf(value.timeZone())).toInstant();
    }

    /**
     * Is this event one instance of a recurring series?
     *
     * <p>Either signal is enough. {@code seriesMasterId} is the one Graph sets on every
     * occurrence and exception; {@code type} says the same in words, and is checked too so
     * that a master item — which {@code calendarView} does not return, but a future caller
     * might — is never written as a meeting either.
     */
    static boolean isSeriesOccurrence(GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event) {
        if (event == null) {
            return false;
        }
        if (event.seriesMasterId() != null && !event.seriesMasterId().isBlank()) {
            return true;
        }
        String type = event.type() == null ? "" : event.type().trim();
        return type.equalsIgnoreCase("occurrence")
                || type.equalsIgnoreCase("exception")
                || type.equalsIgnoreCase("seriesMaster");
    }

    /** Did the mailbox owner decline this invitation? */
    static boolean isDeclined(GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event) {
        return event != null
                && event.responseStatus() != null
                && event.responseStatus().response() != null
                && DECLINED_RESPONSE.equalsIgnoreCase(event.responseStatus().response().trim());
    }

    /**
     * The event's identity across mailboxes, or null when Graph sent none or sent one the
     * column cannot hold. Never cut to fit: a shortened identifier is not an identifier,
     * and two different meetings cut to the same prefix would fold into one line.
     */
    static String icalUidOf(GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event) {
        if (event == null || event.iCalUId() == null) {
            return null;
        }
        String value = event.iCalUId().trim();
        return value.isEmpty() || value.length() > MAX_ICAL_UID_LENGTH ? null : value;
    }

    /** Complete-read reconciliation uses integer membership, never a rounded wall-clock timestamp. */
    long reconcile(String userUuid, ReadWindow window, long generation) {
        return em.createQuery("delete from AccountMeeting where userUuid = :user "
                        + "and occurredAt >= :from and occurredAt <= :to and syncGeneration <> :generation")
                .setParameter("user", userUuid).setParameter("from", window.fromWallClock())
                .setParameter("to", window.toWallClock()).setParameter("generation", generation).executeUpdate();
    }

    /** Every row, any mailbox, whose meeting had not started when this run began. */
    long purgeFuture(Instant runStart) {
        return AccountMeeting.delete("syncGeneration = 0 and occurredAt > ?1", CalendarTime.wallClock(runStart));
    }

    /**
     * Every event Graph has for this mailbox in the window, following {@code @odata.nextLink}
     * to the end.
     *
     * <p><b>Why this loop exists.</b> {@link #PAGE_SIZE} is a page size and was read as a
     * limit: one request, {@code $top=250}, no continuation. Graph returns calendarView
     * OLDEST-FIRST, so every mailbox with more than 250 events in the 455-day first-run
     * window kept its earliest 250 and lost everything after — the recent months, which are
     * the entire subject of "when did we last really talk to them". A production run read
     * 11,373 events over 50 mailboxes, an average of 227 against a cap of 250, and the
     * stored meetings fell from 12 in Sep 2025 to 1 in Jul and 0 in Aug 2026.
     *
     * <p><b>It reports whether it finished.</b> A truncated read and a complete one both
     * end in a list of events; only {@link CalendarRead#complete()} distinguishes "that is
     * all of them" from "we stopped early", and the caller counts the difference so the
     * nightly line can never state one as the other. Truncation has three causes and all
     * three are counted the same way: the {@link #MAX_PAGES} guard, a continuation page
     * that came back with no {@code value} at all after Graph promised more, and a
     * {@code nextLink} naming a continuation in a form we do not read.
     *
     * <p><b>A Graph failure is NOT swallowed here</b>, unlike the rooms lookup this loop is
     * otherwise copied from. A throwing mailbox must keep reaching {@code syncAll}'s catch
     * so it counts as a {@code failure}: degrading it to "a short read" would make a
     * mailbox Graph refuses indistinguishable from a mailbox with a quiet fortnight, and
     * the whole reason these counters exist is that those two must never look alike.
     *
     * @param userUuid  the mailbox owner, for the log line — never the address
     * @param principal the mailbox to read
     */
    CalendarRead readCalendar(String userUuid, String principal, String startDateTime, String endDateTime) {
        return readCalendar(userUuid, principal, startDateTime, endDateTime, () -> true);
    }

    CalendarRead readCalendar(String userUuid, String principal, String startDateTime, String endDateTime,
                              java.util.function.BooleanSupplier mayRead) {
        List<GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent> events = new ArrayList<>();
        GraphContinuation continuation = null;
        int page = 0;
        boolean complete = true;
        do {
            if (!mayRead.getAsBoolean()) return new CalendarRead(events, page, false);
            GraphCalendarClient.AttendeeViewResponse response = graphClient.calendarViewWithAttendees(
                    principal, startDateTime, endDateTime, SELECT, PAGE_SIZE,
                    continuation == null ? null : continuation.skipToken(),
                    continuation == null ? null : continuation.skip());
            page++;
            if (response == null || response.value() == null) {
                // A malformed response is never proof that the calendar is empty.
                complete = false;
                break;
            }
            events.addAll(response.value());

            String nextLink = response.odataNextLink();
            continuation = parseContinuation(nextLink);
            if (nextLink != null && !nextLink.isBlank() && continuation == null) {
                // Graph says there is more but names the continuation in a way we do not
                // read. Stopping silently here is exactly how a calendar quietly stops
                // being the whole calendar.
                log.warnf("Account calendar sync: mailbox %s had an @odata.nextLink with no readable "
                        + "continuation — read truncated after %d page(s), %d event(s)", userUuid, page, events.size());
                complete = false;
                break;
            }
            if (continuation != null && page >= MAX_PAGES) {
                // Counts only — never the mailbox address and never an attendee name; a
                // calendar read's log line must stay as free of people as the $select is.
                log.warnf("Account calendar sync: mailbox %s hit the %d-page guard after %d event(s) "
                        + "— read truncated", userUuid, MAX_PAGES, events.size());
                complete = false;
                break;
            }
        } while (continuation != null);
        return new CalendarRead(events, page, complete);
    }

    /**
     * The continuation out of an {@code @odata.nextLink}, or null when there is no next page
     * — or when the link names one in a form we cannot read, which stops the loop rather
     * than making it spin on the same page.
     *
     * <p>Both spellings, because Graph is not consistent on this collection: calendarView
     * commonly continues with a numeric {@code $skip} while other collections use an opaque
     * {@code $skiptoken}. Whichever the link carried is the one that goes back; neither is
     * ever computed here. Paging a calendar by a number of our own arithmetic re-reads or
     * skips events the moment Graph's window semantics and ours disagree, and a skipped
     * event is an account meeting that never existed.
     */
    static GraphContinuation parseContinuation(String nextLink) {
        if (nextLink == null || nextLink.isBlank()) {
            return null;
        }
        String skipToken = null;
        Integer skip = null;
        try {
            String query = java.net.URI.create(nextLink).getRawQuery();
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (key.equalsIgnoreCase("$skiptoken") || key.equalsIgnoreCase("skiptoken")) {
                    skipToken = value.isBlank() ? null : value;
                } else if (key.equalsIgnoreCase("$skip") || key.equalsIgnoreCase("skip")) {
                    skip = parsePositiveInt(value);
                }
            }
        } catch (RuntimeException e) {
            // An unparseable link stops pagination; it must never throw the mailbox away,
            // because the pages already read are real meetings.
            log.warn("Account calendar sync: unreadable continuation — stopping pagination");
            return null;
        }
        return skipToken == null && skip == null ? null : new GraphContinuation(skipToken, skip);
    }

    /**
     * A {@code $skip} value, or null when it is not a positive whole number. Zero is null
     * on purpose: {@code $skip=0} is the first page again, and honouring it is an infinite
     * loop rather than a continuation.
     */
    private static Integer parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * What Graph named as the next page. Exactly one of the two is normally set; both are
     * handed back untouched and a null one is omitted from the request.
     */
    record GraphContinuation(String skipToken, Integer skip) { }

    /**
     * One mailbox's events plus whether that is ALL of them.
     *
     * <p>The distinction is the whole fix: a list of 250 events that stopped at a page
     * boundary and a list of 250 events that is the entire calendar are the same list, and
     * one of them is missing the months the feature is about.
     *
     * @param events   every event the read collected, in Graph's own order
     * @param pages    how many requests it took, complete or not
     * @param complete false when the read stopped before Graph ran out of pages
     */
    record CalendarRead(List<GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent> events,
                        int pages,
                        boolean complete) { }

    /** Compatibility helper for single-projection callers; the import uses {@link #toMeetings}. */
    PendingMeeting toMeeting(String userUuid,
                             GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event,
                             Map<String, String> domainIndex, CalendarFilters filters,
                             CalendarSyncTally tally, int minOwnAttendees, int minClientAttendees) {
        // Compatibility for callers needing one projection. The importer uses all projections.
        return toMeetings(userUuid, event, domainIndex, filters, tally, minOwnAttendees, minClientAttendees)
                .stream().max(java.util.Comparator.comparingInt(meeting -> meeting.attendees().size()))
                .orElse(null);
    }

    /** One projection per represented account; every decision is made against that account's people. */
    List<PendingMeeting> toMeetings(String userUuid,
                             GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event,
                             Map<String, String> domainIndex, CalendarFilters filters,
                             CalendarSyncTally tally, int minOwnAttendees, int minClientAttendees) {
        if (event == null || event.id() == null || event.id().isBlank()
                || Boolean.TRUE.equals(event.isCancelled()) || isSensitive(event)
                || "seriesMaster".equalsIgnoreCase(event.type())) return List.of();
        if (isDeclined(event)) {
            tally.declinedDropped();
            return List.of();
        }
        Instant started = instantOf(event.start());
        Instant ended = instantOf(event.end());
        if (started == null) return List.of();
        LocalDateTime start = CalendarTime.wallClock(started);
        LocalDate meetingDate = start.toLocalDate();
        List<GraphCalendarClient.CalendarEventDetails.EventAttendee> participants = participants(event);
        int ownAttendeeCount = countOwnTenantAttendees(participants);
        if (minOwnAttendees > 0 && ownAttendeeCount >= minOwnAttendees) {
            tally.internalDropped();
            return List.of();
        }

        boolean recurring = isSeriesOccurrence(event);
        Map<String, List<PendingAttendee>> byClient = new LinkedHashMap<>();
        Map<String, List<PendingAttendee>> sharedByClient = new LinkedHashMap<>();
        boolean sawClientAttendee = false;
        for (var attendee : participants) {
            String normalised = attendee.emailAddress().address().trim().toLowerCase(Locale.ROOT);
            String domain = normalised.substring(normalised.lastIndexOf('@') + 1);
            String clientUuid = domainIndex.get(domain);
            if (clientUuid == null) {
                // Repeated working cadences must not inflate the unknown-company suggestions.
                if (!recurring && CalendarUnmatchedDomainFilter.isSuggestable(domain)) {
                    tally.unmatchedDomain(domain, event.id(), icalUidOf(event), meetingDate);
                }
                continue;
            }
            sawClientAttendee = true;
            String displayName = attendee.emailAddress().name();
            ColleagueDirectory.ColleagueMatch colleague =
                    filters.colleagues().colleagueOn(displayName, meetingDate, clientUuid);
            if (colleague != null) {
                if (colleague.byPlacement()) tally.colleagueByPlacement();
                boolean first = filters.rememberColleagueEmail(normalised, colleague.userUuid());
                tally.learnedColleagueEmail(new LearnedColleagueEmail(normalised, colleague.userUuid(), displayName), first);
                continue;
            }
            if (filters.isColleagueEmailOn(normalised, meetingDate)) continue;
            PendingAttendee person = new PendingAttendee(normalised, displayName, domain);
            (CalendarSharedAddressFilter.isShared(normalised) ? sharedByClient : byClient)
                    .computeIfAbsent(clientUuid, ignored -> new ArrayList<>()).add(person);
        }

        List<PendingMeeting> meetings = new ArrayList<>();
        Set<String> clients = new java.util.LinkedHashSet<>(byClient.keySet());
        clients.addAll(sharedByClient.keySet());
        // A multi-account event is still one room: splitting the room cannot evade the mass guard.
        int clientParticipants = byClient.values().stream().mapToInt(List::size).sum()
                + sharedByClient.values().stream().mapToInt(List::size).sum();
        boolean mass = minClientAttendees > 0 && clientParticipants >= minClientAttendees;
        for (String clientUuid : clients) {
            List<PendingAttendee> people = byClient.getOrDefault(clientUuid, List.of());
            List<PendingAttendee> shared = sharedByClient.getOrDefault(clientUuid, List.of());
            boolean delivery = filters.delivery().isDelivering(clientUuid, userUuid, meetingDate);
            boolean override = !mass && filters.hasStarredOverride(userUuid, clientUuid, people);
            if (!mass && !shared.isEmpty()) {
                addCandidates(tally, userUuid, event, clientUuid, start, "SHARED_ADDRESS", shared);
            }
            if ((recurring || delivery) && !override) {
                if (!mass && !people.isEmpty()) {
                    addCandidates(tally, userUuid, event, clientUuid, start,
                            recurring ? "RECURRING" : "DELIVERY", people);
                }
                continue;
            }
            // A group mailbox by itself is no evidence of a relationship or a client meeting.
            if (people.isEmpty() && !mass) continue;
            List<PendingAttendee> admitted = mass ? List.of() : people;
            if (mass) tally.massMeeting(people.size());
            meetings.add(new PendingMeeting(deterministicUuid(event.id(), userUuid, clientUuid),
                    clientUuid, userUuid, event.id(), icalUidOf(event), start,
                    ended == null ? 0 : Math.max(0, (int) Duration.between(started, ended).toMinutes()),
                    participants.size(), ownAttendeeCount, admitted, event.seriesMasterId(), recurring,
                    override && (recurring || delivery) ? "STARRED_OVERRIDE" : "NORMAL"));
        }
        if (meetings.isEmpty()) {
            if (recurring) tally.recurringDropped();
            else if (clients.stream().anyMatch(client -> filters.delivery().isDelivering(client, userUuid, meetingDate))) {
                tally.deliveryDropped();
            } else if (sawClientAttendee && clients.isEmpty()) tally.colleagueOnlyDropped();
        }
        return meetings;
    }

    private static void addCandidates(CalendarSyncTally tally, String userUuid,
            GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event, String clientUuid,
            LocalDateTime start, String reason, List<PendingAttendee> people) {
        tally.candidate(new CalendarCandidateService.PendingCandidate(clientUuid, userUuid, event.id(),
                icalUidOf(event), start, event.seriesMasterId(), reason,
                people.stream().map(person -> new CalendarCandidateService.Attendee(
                        person.email(), person.displayName(), person.domain())).toList()));
    }

    static boolean isSensitive(GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event) {
        // An omitted/unknown sensitivity cannot establish that the event is shareable.
        return event.sensitivity() == null || !"normal".equalsIgnoreCase(event.sensitivity().trim());
    }

    /** Normalize identities once, include the organizer, and retain a decline/resource veto on duplicates. */
    static List<GraphCalendarClient.CalendarEventDetails.EventAttendee> participants(
            GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event) {
        Map<String, GraphCalendarClient.CalendarEventDetails.EventAttendee> unique = new LinkedHashMap<>();
        Set<String> excluded = new java.util.HashSet<>();
        if (event.attendees() != null) {
            for (var attendee : event.attendees()) {
                String email = attendeeEmail(attendee);
                if (email == null) continue;
                if (isResourceAttendee(attendee) || attendee.status() != null
                        && "declined".equalsIgnoreCase(attendee.status().response())) {
                    excluded.add(email);
                } else {
                    unique.merge(email, attendee, (a, b) -> a.emailAddress().name() == null ? b : a);
                }
            }
        }
        if (event.organizer() != null && event.organizer().emailAddress() != null) {
            var organizer = new GraphCalendarClient.CalendarEventDetails.EventAttendee(
                    event.organizer().emailAddress(), "required", null);
            String email = attendeeEmail(organizer);
            if (email != null) unique.putIfAbsent(email, organizer);
        }
        excluded.forEach(unique::remove);
        return List.copyOf(unique.values());
    }

    private static String attendeeEmail(GraphCalendarClient.CalendarEventDetails.EventAttendee attendee) {
        if (attendee == null || attendee.emailAddress() == null) return null;
        String email = attendee.emailAddress().address();
        if (email == null) return null;
        String value = email.trim().toLowerCase(Locale.ROOT);
        int at = value.indexOf('@');
        return at > 0 && at == value.lastIndexOf('@') && at < value.length() - 1
                && value.length() <= 320 && !value.matches(".*\\s+.*") ? value : null;
    }

    /**
     * How many attendees were on our own tenant (spec §4.2).
     *
     * <p>Rooms first, exactly as the attendee loop does it: a conference room booked on a
     * Trustworks calendar is not one of our people, and counting it would push a meeting
     * over the internal threshold on the strength of the furniture. Everything else the
     * attendee loop in {@code toMeeting} skips — a null address, an address with no
     * {@code @} — is skipped here too, so the count is over the same population.
     *
     * <p>The importer passes its normalized, deduplicated participant list here.
     */
    static int countOwnTenantAttendees(List<GraphCalendarClient.CalendarEventDetails.EventAttendee> attendees) {
        if (attendees == null) {
            return 0;
        }
        int count = 0;
        for (GraphCalendarClient.CalendarEventDetails.EventAttendee attendee : attendees) {
            if (isResourceAttendee(attendee)
                    || attendee == null
                    || attendee.emailAddress() == null) {
                continue;
            }
            String email = attendee.emailAddress().address();
            if (email == null || !email.contains("@")) {
                continue;
            }
            String normalised = email.trim().toLowerCase(Locale.ROOT);
            if (isOwnTenantDomain(normalised.substring(normalised.lastIndexOf('@') + 1))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Is this domain ours?
     *
     * <p>The bare tenant domain, or a subdomain of it, or anything under
     * {@code onmicrosoft.com} — {@code mail.trustworks.dk} and
     * {@code trustworks.onmicrosoft.com} are both Trustworks. The suffixes carry a leading
     * dot so that {@code trustworks.dk} is matched by the exact test and a hypothetical
     * {@code nottrustworks.dk} or {@code notonmicrosoft.com} is matched by neither.
     *
     * <p>It trims and lower-cases its own argument, like
     * {@link CalendarUnmatchedDomainFilter#isSuggestable}, because the two are read
     * together and one of them silently expecting pre-normalised input would be a trap.
     */
    static boolean isOwnTenantDomain(String domain) {
        if (domain == null) {
            return false;
        }
        String value = domain.trim().toLowerCase(Locale.ROOT);
        if (value.equals(OWN_TENANT_DOMAIN)) {
            return true;
        }
        for (String suffix : OWN_TENANT_DOMAIN_SUFFIXES) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A room or a piece of equipment rather than a person (decision D3).
     *
     * <p>Case-insensitive because Graph's casing on this field is not something to bet the
     * relationship graph on, and a null type is a normal attendee — it is omitted on events
     * that were not created in Outlook, and reading a missing value as "resource" would
     * silently delete real people.
     */
    static boolean isResourceAttendee(GraphCalendarClient.CalendarEventDetails.EventAttendee attendee) {
        return attendee != null
                && attendee.type() != null
                && RESOURCE_ATTENDEE_TYPE.equalsIgnoreCase(attendee.type().trim());
    }

    /** Upsert: the deterministic uuid means a re-sync updates the row instead of adding one. */
    void persist(List<PendingMeeting> pending, LocalDateTime now) {
        persist(pending, now, 0);
    }

    void persist(List<PendingMeeting> pending, LocalDateTime now, long generation) {
        for (PendingMeeting item : pending) {
            AccountMeeting meeting = em.find(AccountMeeting.class, item.uuid());
            if (meeting == null) {
                // Pre-generation imports used a mailbox/event UUID without the account.
                // Reuse that row by its natural key so the first recovery does not hit the
                // unique constraint before it can reconcile older generations.
                meeting = em.createQuery("from AccountMeeting where graphEventId = :event "
                                + "and userUuid = :user and clientUuid = :client", AccountMeeting.class)
                        .setParameter("event", item.graphEventId()).setParameter("user", item.userUuid())
                        .setParameter("client", item.clientUuid()).getResultStream().findFirst().orElse(null);
            }
            if (meeting == null) {
                meeting = new AccountMeeting();
                meeting.setUuid(item.uuid());
                meeting.setGraphEventId(item.graphEventId());
                meeting.setUserUuid(item.userUuid());
            }
            meeting.setClientUuid(item.clientUuid());
            meeting.setIcalUid(item.icalUid());
            meeting.setOccurredAt(item.occurredAt());
            meeting.setDurationMinutes(item.durationMinutes());
            meeting.setAttendeeCount(item.attendeeCount());
            meeting.setOwnAttendeeCount(item.ownAttendeeCount());
            meeting.setSyncedAt(now);
            meeting.setSyncGeneration(generation);
            meeting.setSeriesMasterId(item.seriesMasterId());
            meeting.setRecurring(item.recurring());
            meeting.setInclusionReason(item.inclusionReason());
            em.persist(meeting);

            // The attendee list is replaced wholesale: somebody removed from an invitation
            // must not stay on the meeting forever, and the set is small.
            em.createQuery("delete from AccountMeetingAttendee where meetingUuid = :meeting")
                    .setParameter("meeting", meeting.getUuid()).executeUpdate();
            for (PendingAttendee attendee : item.attendees()) {
                AccountMeetingAttendee row = new AccountMeetingAttendee();
                row.setUuid(UUID.randomUUID().toString());
                row.setMeetingUuid(meeting.getUuid());
                row.setEmail(attendee.email());
                row.setDisplayName(attendee.displayName());
                row.setDomain(attendee.domain());
                em.persist(row);
            }
        }
    }

    /**
     * The mailbox to read. {@code User.email} is the Trustworks address; {@code username}
     * is the login name, which for this tenant is the same prefix but not guaranteed to be
     * routable, so the e-mail is preferred and the username is only a fallback.
     */
    String mailboxAddressOf(String userUuid) {
        User user = User.findById(userUuid);
        if (user == null) {
            return null;
        }
        if (user.getEmail() != null && user.getEmail().contains("@")) {
            return user.getEmail().trim();
        }
        return null;
    }

    /**
     * A stable 36-character id for (event, mailbox). SHA-1 over the pair, formatted as a
     * uuid — the column is CHAR(36) and a random uuid would make every nightly run insert
     * duplicates of meetings it has already seen.
     */
    static String deterministicUuid(String graphEventId, String userUuid, String clientUuid) {
        return deterministicUuid(graphEventId, userUuid + "|" + clientUuid);
    }

    static String deterministicUuid(String graphEventId, String userUuid) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((graphEventId + "|" + userUuid).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                    + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is guaranteed present on every JVM; this branch exists only to satisfy
            // the checked exception.
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    static LocalDateTime parseGraphTime(GraphCalendarClient.CalendarViewResponse.GraphDateTime value) {
        if (value == null || value.dateTime() == null || value.dateTime().isBlank()) {
            return null;
        }
        try {
            // Graph returns "2026-09-12T09:00:00.0000000"; LocalDateTime.parse handles the
            // fractional seconds, and the Prefer header already made it Copenhagen wall clock.
            return LocalDateTime.parse(value.dateTime());
        } catch (RuntimeException e) {
            return null;
        }
    }

    record PendingAttendee(String email, String displayName, String domain) { }

    record PendingMeeting(
            String uuid,
            String clientUuid,
            String userUuid,
            String graphEventId,
            String icalUid,
            LocalDateTime occurredAt,
            int durationMinutes,
            int attendeeCount,
            int ownAttendeeCount,
            List<PendingAttendee> attendees,
            String seriesMasterId,
            boolean recurring,
            String inclusionReason) {
        PendingMeeting(String uuid, String clientUuid, String userUuid, String graphEventId, String icalUid,
                LocalDateTime occurredAt, int durationMinutes, int attendeeCount, int ownAttendeeCount,
                List<PendingAttendee> attendees) {
            this(uuid, clientUuid, userUuid, graphEventId, icalUid, occurredAt, durationMinutes,
                    attendeeCount, ownAttendeeCount, attendees, null, false, "NORMAL");
        }
    }
}
