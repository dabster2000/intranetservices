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
 * Reads calendar METADATA from consenting mailboxes and turns it into account meetings
 * (CRM spec §3.2, §3.7).
 *
 * <h2>What it reads, and what it refuses to read</h2>
 * Attendees, start and end. The {@code $select} handed to Graph names exactly those
 * fields and never {@code subject} or {@code body}; {@code account_meeting} has no column
 * for a subject; the summary shown on the page is composed from attendee names. The text
 * of what people discussed never leaves Microsoft's tenant.
 *
 * <h2>Which mailboxes</h2>
 * Only the ones {@link CalendarConsentService#consentedUserUuids()} returns. The Graph app
 * registration holds tenant-wide {@code Calendars.ReadWrite} and could open any mailbox —
 * that list is the rule Intra imposes on itself, and this is the one place it has to hold.
 *
 * <h2>Which events are even candidates — three gates before any rule</h2>
 * Decided 2026-09-14, after the account timeline turned out to be half meetings that had
 * not happened: 209 of 415 stored rows were in the future, and Ældre Sagen's feed was one
 * daily standup read from two mailboxes, repeated every working day into December.
 * <ol>
 *   <li><b>It has to have ended.</b> The window closes at the moment the run starts and an
 *       event still running at that moment is left for the next night. A meeting that has
 *       not happened is not activity, does not make a relationship warm, and is not "the
 *       last time we talked to them". Nothing ahead of the run's clock is read and nothing
 *       ahead of it is kept.</li>
 *   <li><b>A standing meeting is a cadence, not a contact.</b> {@code calendarView} expands
 *       a recurring series into its occurrences, so a daily standup arrives as one event per
 *       day, each carrying the master's id. A standup, a weekly status, a sprint review are
 *       how a project is run — not somebody selling something — and they would drown the
 *       handful of meetings that were. Every occurrence and exception of a series is
 *       dropped, and the count is tallied so the size of what the rule takes is visible.</li>
 *   <li><b>A meeting the owner declined is not one they attended.</b> Outlook normally takes
 *       a declined meeting off the calendar, but "decline and keep" exists.</li>
 * </ol>
 *
 * <h2>Which meetings — five rules, and most of Graph's answer fails one of them</h2>
 * An event that passes the gates has to survive all five to be written. On production this
 * throws away roughly two thirds of everything Graph returns, which is the point: the
 * meetings that matter were buried under the ones that do not.
 *
 * <ol>
 *   <li><b>Somebody from a known client has to be in it.</b> At least one attendee's
 *       e-mail domain must match a {@code client_domain} row. Internal meetings, personal
 *       appointments and meetings with companies we do not serve are not kept as meetings.
 *       Cancelled and undated events are dropped here too.
 *
 *       <p><b>An unmatched domain is no longer dropped silently</b> (spec §2.5, V601). Only
 *       34 of 307 clients have a domain, so this rule was quietly discarding exactly the
 *       companies the customers/prospects/contacts split exists for — the ones several
 *       colleagues keep meeting and Intra has never heard of. The DOMAIN and the DAY are
 *       tallied into {@code calendar_unmatched_meeting} and surface on the Contacts view as
 *       "Seen in calendars" with three things to do about them. Never an attendee, never a
 *       subject: a domain identifies a company, not a person, which is a smaller footprint
 *       than {@code account_meeting_attendee} already has. Freemail, our own and
 *       deny-listed domains never land there.</li>
 *
 *   <li><b>Room and equipment attendees are not people</b> (decision D3). Graph marks them
 *       {@code type="resource"}, and {@code "KIT-LLV-Modelokale-2@politi.dk"} on a client
 *       domain would otherwise be drawn in the relationship graph as somebody the firm
 *       knows at Rigspolitiet. They are dropped, and they no longer count towards
 *       {@code attendeeCount} either — that number is the size of the room in PEOPLE.
 *       Attendees known only by their e-mail address, with no display name at all, are
 *       <b>kept</b>: politi.dk never sends display names, and dropping the nameless would
 *       empty Rigspolitiet's whole relationship graph.</li>
 *
 *   <li><b>Our own all-hands is not a meeting with a client</b> (spec §4.2). The moment one
 *       attendee's domain matches, a firm-wide internal event with a single guest on it is
 *       indistinguishable from a sales meeting — and the account with the most internal
 *       traffic then comes out looking like the warmest relationship in the book. Arba
 *       Security's 77 meetings are one external guest on 76 recurring internal events seen
 *       by 22 mailboxes; 53 of Banedanmark's 60 have eight or more people in them. The
 *       signal that separates the two is how many of OURS were there, so the attendees on
 *       our own tenant are counted, stored on {@code account_meeting.own_attendee_count},
 *       and the event is dropped once the count reaches the configured threshold. Eight by
 *       default: above any sales meeting or steering committee, below any firm-wide event,
 *       and a workshop with eight of ours at the client is delivery, which the next rule
 *       excludes anyway. The threshold is configuration rather than a constant so it can be
 *       re-tuned from a log line instead of a release.
 *
 *       <p><b>The same rule from the other side: a mass event AT the client.</b> Counting
 *       only OUR OWN people caught "a meeting that was really ours with a guest on it" and
 *       said nothing about the event where we were the guests. One meeting at Banedanmark
 *       with fifty people in it stored forty-seven client attendee rows, and each of those
 *       forty-seven then appeared on the account as somebody the firm had met. Firm-wide,
 *       ten meetings with eleven or more attendees produced 132 of the 330 client attendee
 *       rows. So when the WINNING client's delegation reaches its own threshold the
 *       {@code account_meeting} row is kept — a fifty-person event is real activity with
 *       the account — and NO attendee rows are written for it: the event is not evidence
 *       that anybody knows anybody. Ten by default, and configuration for the same reason
 *       eight is.</li>
 *
 *   <li><b>Our own consultants at the client are not client contacts</b> (decision D2). A
 *       consultant placed at a client gets a mailbox there — {@code mygx@novonordisk.com}
 *       is Malthe Yde Andreasen — so they arrived as EXTERNAL people the firm had "met"
 *       and were drawn as the firm's network into its own account. An attendee is dropped
 *       when the display name matches somebody employed here ON THE DAY OF THE MEETING —
 *       by the strict token rules for anybody, and by first-token-then-last-token for
 *       somebody with a placement at that client (spec §4.1 rule b, the shape behind
 *       "Sara Louise Vest (XSVES)" and 53 of the 441 attendee rows) — or when the address
 *       is already known to be a colleague's (see
 *       {@link CalendarFilterService}). <b>Employment is part of the rule, not an
 *       optimisation</b>: a FORMER colleague now working at the client is one of the best
 *       client contacts the firm has and must be kept — and the address branch asks the
 *       same employment question about the same day, because a learned address says whose
 *       a mailbox is and nothing at all about when. If dropping colleagues leaves the
 *       event with no client-domain attendee at all, the whole meeting goes — 39 such
 *       meetings on production.</li>
 *
 *   <li><b>Delivery is not sales</b> (decision D1). If the mailbox owner was on a contract
 *       with the winning client on the day of the meeting, the meeting is dropped. A
 *       consultant sitting at a client has standups, refinements and sprint reviews with
 *       them all day; counted as client contact they made "who last saw them" answer with
 *       a standup. The rule is a {@code contract_consultants} row whose date window
 *       contains the meeting date — <b>contract status is deliberately ignored</b>, because
 *       the date window is the record of when somebody actually sat there. 630 of 960
 *       meetings on production, 158 of 206 on Novo Nordisk.</li>
 * </ol>
 *
 * <h2>Transactions and Graph</h2>
 * A Graph round trip is never made while a transaction is open. A model or HTTP call
 * inside a transaction holds a pooled connection for its whole duration, which is the §P9
 * M1 rule the signal extractor already enforces; here it would hold one for the length of
 * ~100 mailbox reads. Each mailbox is: read (no transaction) → persist (its own short
 * transaction). The filters are loaded ONCE for the whole run, in their own transaction
 * before the loop, for the same reason — and the learned colleague addresses are written
 * inside the mailbox's existing short write transaction, never between it and the next
 * Graph call.
 *
 * <h2>Windows, and what is stored mirrors what was read</h2>
 * Every window closes at the run's clock. A mailbox is read over the last
 * {@value #INCREMENTAL_BACK_DAYS} days each night, which catches late edits and
 * cancellations without re-reading a year; and over the whole {@value #FULL_READ_BACK_DAYS}
 * days on its first run, on Sundays, when rows from before V614 still need their identity
 * filled in, and when the manual trigger asks for it — see {@link #fullReadDue}. The full
 * read is what re-judges old rows under a rule that changed, or under a contract that was
 * entered late.
 *
 * <p>After a COMPLETE read of a window, the rows of that mailbox inside it that the read did
 * not produce are removed: the event was cancelled, deleted, declined, moved, or a rule now
 * drops it. The sync was append-only before this, which is how a meeting that was dropped
 * on every read since the delivery rule shipped still sat on the account. A truncated read
 * removes nothing — it proves nothing about the pages it never fetched. Rows ahead of the
 * clock are removed for every mailbox at the end of the run, whether or not the mailbox was
 * read: they cannot be right, and a mailbox that has opted out keeps what happened, not
 * what was going to.
 *
 * <p>Graph reads the window bounds as UTC when they carry no offset — the documentation
 * says so, and the Prefer header does not change it — so the bounds are formatted from the
 * run's instant in UTC rather than from whatever wall clock the JVM happens to run on.
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
    static final String SELECT = "id,iCalUId,type,seriesMasterId,start,end,isCancelled,responseStatus,attendees";

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

        // ONE clock for the whole run. Every mailbox's window closes at this instant, every
        // row written tonight carries it as synced_at, and the reconcile that removes what a
        // read no longer produced compares against it. A per-mailbox clock would let the
        // fiftieth mailbox's window close ten minutes after the first one's and leave the
        // reconcile comparing rows against a boundary that moved while it ran.
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
                log.warnf("Account calendar sync failed for mailbox %s: %s", userUuid, e.getMessage());
            }
        }

        // Rows ahead of the run's clock can never be right — the window closes at it — and
        // they go for EVERY mailbox, read tonight or not. A mailbox that opted out keeps what
        // was read while it was on, and "what was read" means meetings that happened; a row
        // for a meeting that was going to happen is not history anybody agreed to keep.
        int futureRemoved = QuarkusTransaction.requiringNew().call(() -> (int) purgeFuture(runStart));

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

        static MailboxResult nothing() {
            return new MailboxResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        }
    }

    /**
     * One mailbox. The Graph read happens with NO transaction held; the write and the
     * reconcile are one separate short transaction.
     *
     * <h2>Which window</h2>
     * The last {@value #INCREMENTAL_BACK_DAYS} days, closing at the run's clock, unless a
     * full read is due — {@link #fullReadDue}. Nothing ahead of the clock is read.
     *
     * <h2>Reconcile — what a complete read no longer produces is removed</h2>
     * Every row this pass keeps is rewritten with the run's {@code synced_at}. After that,
     * every row of this mailbox whose {@code occurred_at} lies inside the window just read
     * and whose {@code synced_at} is older than the run is a row the calendar no longer
     * vouches for — cancelled, deleted, declined, moved out of the window, or dropped by a
     * rule that has changed since it was written — and it is deleted, attendees cascading
     * with it. That is what makes the stored meetings a mirror of the mailbox rather than a
     * ledger of everything it ever said.
     *
     * <p>Only a COMPLETE read reconciles. A read that hit the page guard, or a continuation
     * Graph promised and did not deliver, says nothing about the events on the pages it
     * never fetched, and deleting on its evidence would erase real meetings from exactly the
     * busiest calendars. And an empty complete read reconciles like any other: a fortnight
     * in which every meeting was cancelled has to end with no rows for that fortnight.
     */
    MailboxResult syncMailbox(String userUuid, Map<String, String> domainIndex, CalendarFilters filters,
                              Instant runStart, boolean forceFullRead) {
        String principal = QuarkusTransaction.requiringNew().call(() -> mailboxAddressOf(userUuid));
        if (principal == null) {
            log.debugf("No mailbox address for %s — skipping", userUuid);
            return MailboxResult.nothing();
        }

        LocalDate runDay = LocalDate.ofInstant(runStart, CalendarTime.ZONE);
        LocalDateTime fullWindowStart = CalendarTime.wallClock(runStart.minus(Duration.ofDays(FULL_READ_BACK_DAYS)));
        boolean full = QuarkusTransaction.requiringNew().call(() -> {
            boolean firstRun = AccountMeeting.count("userUuid", userUuid) == 0;
            // Rows written before V614 have no identity; the feed shows them one per mailbox
            // until a full read fills the column in. Scoped to the window a full read can
            // actually reach, or a row older than a year would make every night a full night.
            boolean backfillNeeded = !firstRun && AccountMeeting.count(
                    "userUuid = ?1 and icalUid is null and occurredAt >= ?2", userUuid, fullWindowStart) > 0;
            return fullReadDue(forceFullRead, firstRun, backfillNeeded, runDay);
        });
        ReadWindow window = ReadWindow.of(runStart, full);
        // synced_at keeps the JVM's own wall clock, as it always has; what matters is that
        // every row this run writes carries the SAME value and that the reconcile compares
        // against exactly that value.
        LocalDateTime syncedAt = LocalDateTime.ofInstant(runStart, ZoneId.systemDefault());

        // The tally is opened BEFORE the read, not after it: how many pages the read took
        // and whether it finished are facts about the mailbox's pass, exactly like the
        // reasons meetings were dropped, and a mailbox that yields no events at all still
        // has to be able to say whether that was the whole calendar.
        CalendarSyncTally tally = new CalendarSyncTally();

        // One permit for the WHOLE continuation loop, not one per page. The limiter exists
        // to stop two passes hammering the same mailbox; releasing it between pages would
        // let a second reader interleave halfway through somebody's calendar.
        CalendarRead read;
        if (!limiter.tryAcquire(principal)) {
            log.warnf("Graph mailbox %s busy — skipping this run", userUuid);
            return MailboxResult.nothing();
        }
        try {
            read = readCalendar(userUuid, principal, window.graphFrom(), window.graphTo());
        } finally {
            limiter.release(principal);
        }
        tally.graphPagesFetched(read.pages());
        if (!read.complete()) {
            tally.readTruncated();
        }

        List<PendingMeeting> pending = new ArrayList<>();
        for (GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event : read.events()) {
            // Graph returns every event that OVERLAPS the window, so one still running at the
            // run's clock comes back too. It is not activity yet — the next run sees it whole
            // — and writing it now would put a meeting on the timeline with people still in
            // it. Asked here rather than in toMeeting, which holds no clock on purpose.
            if (!hasEnded(event, runStart)) {
                continue;
            }
            // Both thresholds are read here and handed down, because toMeeting has no
            // configuration of its own — see its javadoc on staying pure.
            PendingMeeting meeting = toMeeting(userUuid, event, domainIndex, filters, tally,
                    internalMeetingMinOwnAttendees, massMeetingMinClientAttendees);
            if (meeting != null) {
                pending.add(meeting);
            }
        }

        int attendeeRows = pending.stream().mapToInt(meeting -> meeting.attendees().size()).sum();
        int staleRemoved = 0;
        if (read.complete() || !pending.isEmpty() || tally.hasLearnedEmails() || tally.hasUnmatchedDomains()) {
            // One transaction, opened after the last Graph call for this mailbox and closed
            // before the next one. The learned addresses ride along in it rather than in a
            // second transaction of their own: they are a by-product of the same pass and
            // are worthless if the meetings they came from were not written. The reconcile
            // is last and in the same transaction, so a write that fails removes nothing.
            staleRemoved = QuarkusTransaction.requiringNew().call(() -> {
                persist(pending, syncedAt);
                filterService.rememberColleagueEmails(tally.learnedEmails(), syncedAt);
                suggestionService.record(userUuid, tally.unmatchedDomains(), syncedAt);
                return read.complete() ? (int) reconcile(userUuid, window, syncedAt) : 0;
            });
        }
        if (staleRemoved > 0) {
            log.infof("Account calendar sync: mailbox %s — %d stored meeting(s) no longer in the calendar "
                    + "(%s-day window) removed", userUuid, staleRemoved, full ? FULL_READ_BACK_DAYS : INCREMENTAL_BACK_DAYS);
        }
        return new MailboxResult(
                read.events().size(),
                pending.size(),
                attendeeRows,
                tally.deliveryDroppedCount(),
                tally.colleagueOnlyDroppedCount(),
                tally.newlyLearnedEmails(),
                tally.internalDroppedCount(),
                tally.colleagueByPlacementCount(),
                tally.graphPagesCount(),
                tally.readsTruncatedCount(),
                tally.massMeetingsFlaggedCount(),
                tally.massAttendeesSuppressedCount(),
                tally.recurringDroppedCount(),
                tally.declinedDroppedCount(),
                staleRemoved,
                full);
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
            return new ReadWindow(runStart.minus(Duration.ofDays(days)), runStart, full);
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

    /**
     * Removes this mailbox's rows inside the window that this run did not write.
     *
     * <p>Must be called inside the same transaction as {@link #persist}, after it, so the
     * kept rows already carry the run's {@code synced_at} and are excluded by it. Attendees
     * go with their meeting through the {@code ON DELETE CASCADE} of V588 — nothing sweeps
     * them by hand, and nothing has to.
     */
    long reconcile(String userUuid, ReadWindow window, LocalDateTime syncedAt) {
        return AccountMeeting.delete(
                "userUuid = ?1 and occurredAt >= ?2 and occurredAt <= ?3 and syncedAt < ?4",
                userUuid, window.fromWallClock(), window.toWallClock(), syncedAt);
    }

    /** Every row, any mailbox, whose meeting had not started when this run began. */
    long purgeFuture(Instant runStart) {
        return AccountMeeting.delete("occurredAt > ?1", CalendarTime.wallClock(runStart));
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
        List<GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent> events = new ArrayList<>();
        GraphContinuation continuation = null;
        int page = 0;
        boolean complete = true;
        do {
            GraphCalendarClient.AttendeeViewResponse response = graphClient.calendarViewWithAttendees(
                    principal, startDateTime, endDateTime, SELECT, PAGE_SIZE,
                    continuation == null ? null : continuation.skipToken(),
                    continuation == null ? null : continuation.skip());
            page++;
            if (response == null || response.value() == null) {
                // Nothing on the FIRST request is an empty window, which is ordinary for a
                // mailbox with a quiet fortnight. Nothing on a CONTINUATION is a truncation:
                // Graph said there was more and then did not hand it over.
                if (page > 1) {
                    complete = false;
                }
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
            log.warnf("Account calendar sync: could not read an @odata.nextLink — stopping pagination: %s",
                    e.getMessage());
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

    /**
     * Turns one Graph event into a meeting, or null when it is not one we keep — the five
     * rules in this class's javadoc, in the order they can be decided:
     *
     * <pre>
     *   rooms -> INTERNAL -> per attendee: domain index
     *                                   -> colleague by name (strict, then placed)
     *                                   -> colleague by address
     *         -> nobody from the client left -> largest delegation wins
     *         -> MASS EVENT (keep the meeting, store no attendees) -> delivery
     * </pre>
     *
     * <p>The internal test comes before the attendee loop because an event that is our own
     * all-hands is not evidence about anybody: attributing one guest's domain to a client,
     * learning an address from it or tallying an unknown company off it would all be
     * conclusions drawn from a meeting that never was one. The delivery test comes last
     * because it is the only one that needs the winning client.
     *
     * <p>When attendees from two different clients are in the same meeting the one with
     * the most attendees wins. Splitting a meeting across accounts would double-count it in
     * both, and attributing it to neither would lose it. The delivery check is applied
     * AFTER that winner is known, because "was the mailbox owner delivering" is a question
     * about a specific client and there is no answer to it until the meeting has one.
     *
     * <p><b>Pure, and it has to stay that way.</b> No {@code EntityManager}, no Graph call,
     * no clock: everything it needs arrives as an argument, which is what lets the fast
     * tier hold the whole set of rules without booting Quarkus or a database. The
     * consequence is {@code tally} — a colleague address identified here has to be WRITTEN,
     * and the writing happens in the caller's transaction. Note that a meeting this method
     * drops still teaches an address: the drop is precisely the evidence that the address
     * belongs to one of ours.
     *
     * @param userUuid           the mailbox owner, and the person the delivery rule asks about
     * @param event              one event from Graph's calendarView
     * @param domainIndex        {@code domain → clientUuid}, from {@code client_domain}
     * @param filters            the run's contract index, colleague directory, placement
     *                           index and known colleague addresses; its address set GROWS
     *                           as this method identifies more
     * @param tally              collects what was learned and why things were dropped
     * @param minOwnAttendees    how many of our own people make the event internal (spec
     *                           §4.2). A parameter rather than a field for the same reason
     *                           {@code tally} is one: this method holds no configuration
     *                           and reads no clock. Zero or less switches the rule off
     * @param minClientAttendees how many people from the WINNING client make the event a
     *                           mass event whose attendees are no evidence of a personal
     *                           relationship. A parameter for exactly the same reason.
     *                           Zero or less switches the rule off
     */
    PendingMeeting toMeeting(String userUuid,
                             GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event,
                             Map<String, String> domainIndex,
                             CalendarFilters filters,
                             CalendarSyncTally tally,
                             int minOwnAttendees,
                             int minClientAttendees) {
        if (event == null || event.id() == null || Boolean.TRUE.equals(event.isCancelled())) {
            return null;
        }
        // A standing meeting is a cadence, not a contact (decided 2026-09-14). Before the
        // attendee loop for the same reason the internal rule is: an occurrence of a daily
        // standup must not teach an address, tally an unknown company as "seen in
        // calendars" twenty times a month, or attribute anything to anybody.
        if (isSeriesOccurrence(event)) {
            tally.recurringDropped();
            return null;
        }
        // A meeting the owner said no to is not one they attended.
        if (isDeclined(event)) {
            tally.declinedDropped();
            return null;
        }
        LocalDateTime start = parseGraphTime(event.start());
        LocalDateTime end = parseGraphTime(event.end());
        if (start == null) {
            return null;
        }
        // Every date question below is asked about the day of the MEETING, never about
        // today: a meeting from eighteen months ago is judged against the contracts and
        // the employments that were in force eighteen months ago.
        LocalDate meetingDate = start.toLocalDate();

        // How many of the room were ours, decided before anything is attributed to anybody.
        // An internal event with one client guest on it must not teach an address or tally
        // an unknown domain either: it is not a meeting with that company at all, and every
        // conclusion drawn from it would be drawn from our own all-hands.
        int ownAttendeeCount = countOwnTenantAttendees(event.attendees());
        if (minOwnAttendees > 0 && ownAttendeeCount >= minOwnAttendees) {
            tally.internalDropped();
            return null;
        }

        Map<String, List<PendingAttendee>> byClient = new LinkedHashMap<>();
        boolean sawClientAttendee = false;
        int attendeeCount = 0;
        if (event.attendees() != null) {
            for (GraphCalendarClient.CalendarEventDetails.EventAttendee attendee : event.attendees()) {
                if (isResourceAttendee(attendee)) {
                    // D3: a meeting room is not a person and must not be counted as one.
                    continue;
                }
                attendeeCount++;
                if (attendee == null || attendee.emailAddress() == null) {
                    continue;
                }
                String email = attendee.emailAddress().address();
                if (email == null || !email.contains("@")) {
                    continue;
                }
                String normalised = email.trim().toLowerCase(Locale.ROOT);
                String domain = normalised.substring(normalised.lastIndexOf('@') + 1);
                String clientUuid = domainIndex.get(domain);
                if (clientUuid == null) {
                    // Nobody claims this domain. Write down THAT, so a company several
                    // colleagues keep meeting stops being invisible (spec §2.5).
                    if (CalendarUnmatchedDomainFilter.isSuggestable(domain)) {
                        tally.unmatchedDomain(domain, event.id(), meetingDate);
                    }
                    continue;
                }
                sawClientAttendee = true;

                // D2, by name — the strict rules first and then, for somebody placed at
                // THIS client, the widened one (spec §4.1 a then b). The client is passed
                // in because rule b is only safe confined to placements: without it,
                // "Lars Peter Jensen" matches our Lars Jensen and a client person is
                // deleted. The uuid is what makes this worth more than a boolean: it is
                // written down against the address so the next run recognises it even when
                // Graph sends no display name at all.
                String displayName = attendee.emailAddress().name();
                ColleagueDirectory.ColleagueMatch colleague =
                        filters.colleagues().colleagueOn(displayName, meetingDate, clientUuid);
                if (colleague != null) {
                    if (colleague.byPlacement()) {
                        tally.colleagueByPlacement();
                    }
                    boolean firstTimeThisRun = filters.rememberColleagueEmail(normalised, colleague.userUuid());
                    tally.learnedColleagueEmail(
                            new LearnedColleagueEmail(normalised, colleague.userUuid(), displayName),
                            firstTimeThisRun);
                    continue;
                }
                // D2, by address. Covers the bare-address case the name rule cannot reach
                // — and asks the SAME employment question, about the person the address is
                // known to belong to. A date-blind address rule would quietly override the
                // name rule's employment test and drop a former colleague's meetings for
                // ever; production already holds that shape, in an address whose owner left
                // on 2026-04-01 and which still appears on a meeting in June.
                if (filters.isColleagueEmailOn(normalised, meetingDate)) {
                    continue;
                }

                byClient.computeIfAbsent(clientUuid, key -> new ArrayList<>())
                        .add(new PendingAttendee(normalised, displayName, domain));
            }
        }
        if (byClient.isEmpty()) {
            if (sawClientAttendee) {
                // The client's domain WAS in the room, but every address on it was one of
                // ours. Two of our consultants comparing notes at the client site is not a
                // meeting with the client.
                tally.colleagueOnlyDropped();
            }
            return null;
        }

        Map.Entry<String, List<PendingAttendee>> winner = byClient.entrySet().stream()
                .max((a, b) -> Integer.compare(a.getValue().size(), b.getValue().size()))
                .orElseThrow();

        // The mass event, decided here because it is a question about the WINNING
        // delegation and there is no delegation to measure until the winner is known.
        //
        // KEEP THE MEETING, DROP THE ATTENDEES — and that asymmetry is the whole rule. A
        // fifty-person event at Banedanmark IS activity with that account and belongs on
        // its timeline; what it is not is evidence that anybody in this firm knows the
        // forty-seven people who were in the room. Stored as attendees they each became a
        // person on the account reading "met x1 — 228 d ago", which is a relationship the
        // firm does not have. §7's retention purge already leaves account_meeting rows with
        // no attendees behind, and AccountActivityService.joinNames renders exactly that
        // shape as "Meeting with the client", so nothing downstream is surprised by it.
        boolean massMeeting = minClientAttendees > 0 && winner.getValue().size() >= minClientAttendees;

        // D1, last: the winning client is the one the delivery question is about. The mass
        // rule above deliberately does not return, so this still runs on exactly the
        // meetings it ran on before — a mass event the mailbox owner was delivering is
        // still a delivery drop, and is counted as one rather than as two things at once.
        if (filters.delivery().isDelivering(winner.getKey(), userUuid, meetingDate)) {
            tally.deliveryDropped();
            return null;
        }

        // Tallied only now that the meeting is actually being written. Counting it before
        // the delivery check would attribute suppressed attendee rows to meetings that were
        // never stored, and the number's only job is to say how many people the threshold
        // took off real meetings — the evidence for re-tuning it from a log line.
        List<PendingAttendee> attendees = massMeeting ? List.of() : winner.getValue();
        if (massMeeting) {
            tally.massMeeting(winner.getValue().size());
        }

        int minutes = end == null ? 0 : (int) Duration.between(start, end).toMinutes();
        return new PendingMeeting(
                deterministicUuid(event.id(), userUuid),
                winner.getKey(),
                userUuid,
                event.id(),
                icalUidOf(event),
                start,
                Math.max(minutes, 0),
                attendeeCount,
                ownAttendeeCount,
                attendees);
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
     * <p>Addresses are counted as they arrive, without de-duplication. Graph does not
     * repeat an attendee on an event, and {@code attendeeCount} is already "the size of the
     * room" on the same terms.
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
        for (PendingMeeting item : pending) {
            AccountMeeting meeting = AccountMeeting.findById(item.uuid());
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
            meeting.persist();

            // The attendee list is replaced wholesale: somebody removed from an invitation
            // must not stay on the meeting forever, and the set is small.
            AccountMeetingAttendee.delete("meetingUuid", item.uuid());
            for (PendingAttendee attendee : item.attendees()) {
                AccountMeetingAttendee row = new AccountMeetingAttendee();
                row.setUuid(UUID.randomUUID().toString());
                row.setMeetingUuid(item.uuid());
                row.setEmail(attendee.email());
                row.setDisplayName(attendee.displayName());
                row.setDomain(attendee.domain());
                row.persist();
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
            List<PendingAttendee> attendees) { }
}
