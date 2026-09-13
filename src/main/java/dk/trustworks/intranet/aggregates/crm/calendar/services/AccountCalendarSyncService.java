package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.account.services.AccountService;
import dk.trustworks.intranet.aggregates.crm.calendar.dto.CalendarSyncSummary;
import dk.trustworks.intranet.aggregates.crm.calendar.model.AccountMeeting;
import dk.trustworks.intranet.aggregates.crm.calendar.model.AccountMeetingAttendee;
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
 * <h2>Which meetings</h2>
 * An event is kept only when at least one attendee's e-mail domain matches a
 * {@code client_domain} row. Everything else — internal meetings, personal appointments,
 * meetings with companies we do not serve — is dropped without being written anywhere.
 * Cancelled events are dropped too.
 *
 * <h2>Transactions and Graph</h2>
 * A Graph round trip is never made while a transaction is open. A model or HTTP call
 * inside a transaction holds a pooled connection for its whole duration, which is the §P9
 * M1 rule the signal extractor already enforces; here it would hold one for the length of
 * ~100 mailbox reads. Each mailbox is: read (no transaction) → persist (its own short
 * transaction).
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

    private static final DateTimeFormatter GRAPH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Inject
    @RestClient
    GraphCalendarClient graphClient;

    @Inject
    CalendarConsentService consentService;

    @Inject
    AccountService accountService;

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
            return new CalendarSyncSummary(0, 0, 0, 0, 0);
        }

        Map<String, String> domainIndex = QuarkusTransaction.requiringNew().call(accountService::domainIndex);
        if (domainIndex.isEmpty()) {
            log.info("Account calendar sync: no client domains configured, nothing can be attributed");
            return new CalendarSyncSummary(0, 0, 0, 0, 0);
        }

        Set<String> mailboxes = QuarkusTransaction.requiringNew().call(consentService::consentedUserUuids);
        int eventsSeen = 0;
        int meetingsKept = 0;
        int attendees = 0;
        int failures = 0;

        for (String userUuid : mailboxes) {
            try {
                MailboxResult result = syncMailbox(userUuid, domainIndex);
                eventsSeen += result.eventsSeen();
                meetingsKept += result.meetingsKept();
                attendees += result.attendees();
            } catch (RuntimeException e) {
                failures++;
                // The message, not the stack, and never the event payload: a Graph error
                // body can echo back calendar content.
                log.warnf("Account calendar sync failed for mailbox %s: %s", userUuid, e.getMessage());
            }
        }

        log.infof("Account calendar sync done: mailboxes=%d events=%d meetings=%d attendees=%d failures=%d",
                mailboxes.size(), eventsSeen, meetingsKept, attendees, failures);
        return new CalendarSyncSummary(mailboxes.size(), eventsSeen, meetingsKept, attendees, failures);
    }

    record MailboxResult(int eventsSeen, int meetingsKept, int attendees) { }

    /**
     * One mailbox. The Graph read happens with NO transaction held; the write is a separate
     * short one.
     */
    MailboxResult syncMailbox(String userUuid, Map<String, String> domainIndex) {
        String principal = QuarkusTransaction.requiringNew().call(() -> mailboxAddressOf(userUuid));
        if (principal == null) {
            log.debugf("No mailbox address for %s — skipping", userUuid);
            return new MailboxResult(0, 0, 0);
        }

        boolean firstRun = QuarkusTransaction.requiringNew()
                .call(() -> AccountMeeting.count("userUuid", userUuid) == 0);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.minusDays(firstRun ? FIRST_RUN_BACK_DAYS : INCREMENTAL_BACK_DAYS);
        LocalDateTime to = now.plusDays(FORWARD_DAYS);

        GraphCalendarClient.AttendeeViewResponse response;
        if (!limiter.tryAcquire(principal)) {
            log.warnf("Graph mailbox %s busy — skipping this run", userUuid);
            return new MailboxResult(0, 0, 0);
        }
        try {
            response = graphClient.calendarViewWithAttendees(
                    principal, from.format(GRAPH_TIME), to.format(GRAPH_TIME), SELECT, PAGE_SIZE);
        } finally {
            limiter.release(principal);
        }

        if (response == null || response.value() == null || response.value().isEmpty()) {
            return new MailboxResult(0, 0, 0);
        }

        List<PendingMeeting> pending = new ArrayList<>();
        for (GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event : response.value()) {
            PendingMeeting meeting = toMeeting(userUuid, event, domainIndex);
            if (meeting != null) {
                pending.add(meeting);
            }
        }

        int attendeeRows = pending.stream().mapToInt(meeting -> meeting.attendees().size()).sum();
        if (!pending.isEmpty()) {
            QuarkusTransaction.requiringNew().run(() -> persist(pending, now));
        }
        return new MailboxResult(response.value().size(), pending.size(), attendeeRows);
    }

    /**
     * Turns one Graph event into a meeting, or null when it is not one we keep: cancelled,
     * undated, or with nobody from a known client in it.
     *
     * <p>When attendees from two different clients are in the same meeting the one with
     * the most attendees wins. Splitting a meeting across accounts would double-count it in
     * both, and attributing it to neither would lose it.
     */
    PendingMeeting toMeeting(String userUuid,
                             GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event,
                             Map<String, String> domainIndex) {
        if (event == null || event.id() == null || Boolean.TRUE.equals(event.isCancelled())) {
            return null;
        }
        LocalDateTime start = parseGraphTime(event.start());
        LocalDateTime end = parseGraphTime(event.end());
        if (start == null) {
            return null;
        }

        Map<String, List<PendingAttendee>> byClient = new LinkedHashMap<>();
        int attendeeCount = 0;
        if (event.attendees() != null) {
            for (GraphCalendarClient.CalendarEventDetails.EventAttendee attendee : event.attendees()) {
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
                    continue;
                }
                byClient.computeIfAbsent(clientUuid, key -> new ArrayList<>())
                        .add(new PendingAttendee(normalised, attendee.emailAddress().name(), domain));
            }
        }
        if (byClient.isEmpty()) {
            return null;
        }

        Map.Entry<String, List<PendingAttendee>> winner = byClient.entrySet().stream()
                .max((a, b) -> Integer.compare(a.getValue().size(), b.getValue().size()))
                .orElseThrow();

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
