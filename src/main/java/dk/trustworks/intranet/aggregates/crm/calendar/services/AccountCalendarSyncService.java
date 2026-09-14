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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * <h2>Which meetings — four rules, and most of Graph's answer fails one of them</h2>
 * An event has to survive all four to be written. On production this throws away roughly
 * two thirds of everything Graph returns, which is the point: the meetings that matter
 * were buried under the ones that do not.
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
 *   <li><b>Our own consultants at the client are not client contacts</b> (decision D2). A
 *       consultant placed at a client gets a mailbox there — {@code mygx@novonordisk.com}
 *       is Malthe Yde Andreasen — so they arrived as EXTERNAL people the firm had "met"
 *       and were drawn as the firm's network into its own account. An attendee is dropped
 *       when the display name matches somebody employed here ON THE DAY OF THE MEETING, or
 *       when the address is already known to be a colleague's (see
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
 * <h2>Windows</h2>
 * A mailbox with no meetings yet is read 12 months back — enough history for meeting
 * counts and "who last saw them" to mean something on day one. Afterwards only the last
 * {@value #INCREMENTAL_BACK_DAYS} days and {@value #FORWARD_DAYS} forward, which catches
 * late edits and accepted invitations without re-reading a year every night.
 */
@JBossLog
@ApplicationScoped
public class AccountCalendarSyncService {

    /** Attendees, bounds and the cancellation flag. Never subject, never body. */
    static final String SELECT = "id,start,end,isCancelled,attendees";

    static final int FIRST_RUN_BACK_DAYS = 365;
    static final int INCREMENTAL_BACK_DAYS = 14;
    static final int FORWARD_DAYS = 90;

    /** Graph's page cap for calendarView. Bigger asks are silently truncated anyway. */
    static final int PAGE_SIZE = 250;

    /**
     * The {@code attendee.type} Microsoft Graph puts on a meeting room or a piece of
     * equipment. The other values are {@code required} and {@code optional}, and a null
     * type is a normal attendee — Graph omits it on events created outside Outlook.
     */
    static final String RESOURCE_ATTENDEE_TYPE = "resource";

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
     * One full pass over every consenting mailbox.
     *
     * <p>A mailbox Graph refuses does not stop the run. One person on holiday with a
     * broken licence must not cost the firm its whole relationship graph, so failures are
     * counted and logged and the loop continues.
     */
    public CalendarSyncSummary syncAll() {
        if (!syncEnabled) {
            log.info("Account calendar sync is switched off (dk.trustworks.crm.calendar.sync.enabled=false)");
            return new CalendarSyncSummary(0, 0, 0, 0, 0, 0, 0, 0);
        }

        Map<String, String> domainIndex = QuarkusTransaction.requiringNew().call(accountService::domainIndex);
        if (domainIndex.isEmpty()) {
            log.info("Account calendar sync: no client domains configured, nothing can be attributed");
            return new CalendarSyncSummary(0, 0, 0, 0, 0, 0, 0, 0);
        }

        // Contracts, colleagues and learned addresses: read once, used by every mailbox.
        // Per mailbox these would be a hundred repetitions of the same answer, and they
        // cannot be read at all once the Graph loop is running without breaking the rule
        // that no transaction is open during a Graph call.
        CalendarFilters filters = QuarkusTransaction.requiringNew().call(filterService::load);

        Set<String> mailboxes = QuarkusTransaction.requiringNew().call(consentService::consentedUserUuids);
        int eventsSeen = 0;
        int meetingsKept = 0;
        int attendees = 0;
        int failures = 0;
        int deliveryFiltered = 0;
        int colleagueFiltered = 0;
        int emailsLearned = 0;

        for (String userUuid : mailboxes) {
            try {
                MailboxResult result = syncMailbox(userUuid, domainIndex, filters);
                eventsSeen += result.eventsSeen();
                meetingsKept += result.meetingsKept();
                attendees += result.attendees();
                deliveryFiltered += result.deliveryFiltered();
                colleagueFiltered += result.colleagueFiltered();
                emailsLearned += result.colleagueEmailsLearned();
            } catch (RuntimeException e) {
                failures++;
                // The message, not the stack, and never the event payload: a Graph error
                // body can echo back calendar content.
                log.warnf("Account calendar sync failed for mailbox %s: %s", userUuid, e.getMessage());
            }
        }

        // The per-domain counts are recomputed from the ledger once, after every mailbox
        // has written its sightings — never incremented as they arrive, because the
        // incremental window re-reads the same fortnight every night.
        QuarkusTransaction.requiringNew().run(suggestionService::refreshAggregates);

        log.infof("Account calendar sync done: mailboxes=%d events=%d meetings=%d attendees=%d failures=%d "
                        + "deliveryFiltered=%d colleagueFiltered=%d colleagueEmailsLearned=%d",
                mailboxes.size(), eventsSeen, meetingsKept, attendees, failures,
                deliveryFiltered, colleagueFiltered, emailsLearned);
        return new CalendarSyncSummary(mailboxes.size(), eventsSeen, meetingsKept, attendees, failures,
                deliveryFiltered, colleagueFiltered, emailsLearned);
    }

    record MailboxResult(int eventsSeen,
                         int meetingsKept,
                         int attendees,
                         int deliveryFiltered,
                         int colleagueFiltered,
                         int colleagueEmailsLearned) {

        static MailboxResult nothing() {
            return new MailboxResult(0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * One mailbox. The Graph read happens with NO transaction held; the write is a separate
     * short one.
     */
    MailboxResult syncMailbox(String userUuid, Map<String, String> domainIndex, CalendarFilters filters) {
        String principal = QuarkusTransaction.requiringNew().call(() -> mailboxAddressOf(userUuid));
        if (principal == null) {
            log.debugf("No mailbox address for %s — skipping", userUuid);
            return MailboxResult.nothing();
        }

        boolean firstRun = QuarkusTransaction.requiringNew()
                .call(() -> AccountMeeting.count("userUuid", userUuid) == 0);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.minusDays(firstRun ? FIRST_RUN_BACK_DAYS : INCREMENTAL_BACK_DAYS);
        LocalDateTime to = now.plusDays(FORWARD_DAYS);

        GraphCalendarClient.AttendeeViewResponse response;
        if (!limiter.tryAcquire(principal)) {
            log.warnf("Graph mailbox %s busy — skipping this run", userUuid);
            return MailboxResult.nothing();
        }
        try {
            response = graphClient.calendarViewWithAttendees(
                    principal, from.format(GRAPH_TIME), to.format(GRAPH_TIME), SELECT, PAGE_SIZE);
        } finally {
            limiter.release(principal);
        }

        if (response == null || response.value() == null || response.value().isEmpty()) {
            return MailboxResult.nothing();
        }

        CalendarSyncTally tally = new CalendarSyncTally();
        List<PendingMeeting> pending = new ArrayList<>();
        for (GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event : response.value()) {
            PendingMeeting meeting = toMeeting(userUuid, event, domainIndex, filters, tally);
            if (meeting != null) {
                pending.add(meeting);
            }
        }

        int attendeeRows = pending.stream().mapToInt(meeting -> meeting.attendees().size()).sum();
        if (!pending.isEmpty() || tally.hasLearnedEmails() || tally.hasUnmatchedDomains()) {
            // One transaction, opened after the last Graph call for this mailbox and closed
            // before the next one. The learned addresses ride along in it rather than in a
            // second transaction of their own: they are a by-product of the same pass and
            // are worthless if the meetings they came from were not written.
            QuarkusTransaction.requiringNew().run(() -> {
                persist(pending, now);
                filterService.rememberColleagueEmails(tally.learnedEmails(), now);
                suggestionService.record(userUuid, tally.unmatchedDomains(), now);
            });
        }
        return new MailboxResult(
                response.value().size(),
                pending.size(),
                attendeeRows,
                tally.deliveryDroppedCount(),
                tally.colleagueOnlyDroppedCount(),
                tally.newlyLearnedEmails());
    }

    /**
     * Turns one Graph event into a meeting, or null when it is not one we keep — the four
     * rules in this class's javadoc, in the order they can be decided.
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
     * @param userUuid    the mailbox owner, and the person the delivery rule asks about
     * @param event       one event from Graph's calendarView
     * @param domainIndex {@code domain → clientUuid}, from {@code client_domain}
     * @param filters     the run's contract index, colleague directory and known colleague
     *                    addresses; its address set GROWS as this method identifies more
     * @param tally       collects what was learned and why things were dropped
     */
    PendingMeeting toMeeting(String userUuid,
                             GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event,
                             Map<String, String> domainIndex,
                             CalendarFilters filters,
                             CalendarSyncTally tally) {
        if (event == null || event.id() == null || Boolean.TRUE.equals(event.isCancelled())) {
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

                // D2, by name. The uuid is what makes this worth more than a boolean: it
                // is written down against the address so the next run recognises it even
                // when Graph sends no display name at all.
                String displayName = attendee.emailAddress().name();
                String colleagueUuid = filters.colleagues().colleagueUuidOn(displayName, meetingDate);
                if (colleagueUuid != null) {
                    boolean firstTimeThisRun = filters.rememberColleagueEmail(normalised, colleagueUuid);
                    tally.learnedColleagueEmail(
                            new LearnedColleagueEmail(normalised, colleagueUuid, displayName), firstTimeThisRun);
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

        // D1, last: the winning client is the one the delivery question is about.
        if (filters.delivery().isDelivering(winner.getKey(), userUuid, meetingDate)) {
            tally.deliveryDropped();
            return null;
        }

        int minutes = end == null ? 0 : (int) Duration.between(start, end).toMinutes();
        return new PendingMeeting(
                deterministicUuid(event.id(), userUuid),
                winner.getKey(),
                userUuid,
                event.id(),
                start,
                Math.max(minutes, 0),
                attendeeCount,
                winner.getValue());
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
            meeting.setOccurredAt(item.occurredAt());
            meeting.setDurationMinutes(item.durationMinutes());
            meeting.setAttendeeCount(item.attendeeCount());
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
            LocalDateTime occurredAt,
            int durationMinutes,
            int attendeeCount,
            List<PendingAttendee> attendees) { }
}
