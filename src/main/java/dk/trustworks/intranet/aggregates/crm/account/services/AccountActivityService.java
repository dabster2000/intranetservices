package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountActivityDTO;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarTime;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestContent;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestDTO;
import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackDigestService;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The derived account feed (CRM spec §3.2). Every row but one is something a system
 * already saw; {@code NOTE} is the single hand-typed source, and it exists for what no
 * system recorded.
 *
 * <p>Nine sources are unioned here and sorted newest first. Nine is the number of values
 * {@code source} can take — which is what the frontend keys its chips and its filter on —
 * and not the number of queries: LEAD is composed from two of them and SLACK from two, so
 * eleven producers feed these nine rows. The two numbers had already drifted apart before
 * the mention lane arrived, which is why this now says which of them it is counting.
 *
 * <table>
 *   <tr><td>CONTRACT</td><td>{@code client_activity_log} rows about contracts</td></tr>
 *   <tr><td>RELATIONSHIP</td><td>{@code client_activity_log} rows where {@code client.type} changed — a prospect becoming a customer</td></tr>
 *   <tr><td>LEAD</td><td>{@code sales_lead} created / won / lost and {@code sales_lead_stage_history}</td></tr>
 *   <tr><td>SIGNAL</td><td>{@code account_signal} captured and decided</td></tr>
 *   <tr><td>KYC</td><td>{@code questionnaire_submission} about the client</td></tr>
 *   <tr><td>BAND</td><td>{@code client_band_history}</td></tr>
 *   <tr><td>CALENDAR</td><td>{@code account_meeting} + its external attendees</td></tr>
 *   <tr><td>NOTE</td><td>{@code client_note} — the one line somebody typed</td></tr>
 *   <tr><td>SLACK</td><td>{@code account_slack_digest} — one row per day of the linked account space (V594) — and {@code account_slack_mention} — one row per day a listed general channel talked about this client (V602)</td></tr>
 * </table>
 *
 * <p><b>Neither SLACK producer reads Slack.</b> Both read a row a nightly job left behind.
 * {@code AccountSlackSyncJob} pulls the linked {@code a_*} channel with the Slack API — an
 * outbound read, which is why it needed no inbound signing secret — and stores one row per
 * Copenhagen day: counts, participants, and a model's validated reading of the day
 * (headline, decisions, next steps, risks, client asks, people named, topics).
 * {@code SlackSourceSyncJob} does the same for the general channels an admin listed, except
 * that a day there is about many clients or none, so it stores a row per client the day
 * actually talked about. No message text is stored by either; {@link #slackRows} and
 * {@link #slackMentionRows} compose their line from the headline and hand the reading to
 * the row as {@code slackDigest}. The two carry the same {@code source} deliberately — a
 * reader does not care which channel a thing was said in — and are told apart by
 * {@code refType}, because only a mention can be wrong about which client it is about and
 * so only a mention offers the affordance to say so. The {@code /signal} command and the
 * buttons of spec §4.9 still need inbound and are still not built.
 *
 * <p><b>Native queries.</b> {@code sales_lead_stage_history} has no entity (it is written
 * with a native INSERT in {@code SalesService}) and the meeting summary needs a join whose
 * result is neither entity. Everything is parameter-bound; no string is concatenated into
 * SQL.
 *
 * <p><b>Meeting summaries carry no subject.</b> {@code account_meeting} has no subject
 * column and the sync never asks Graph for one, so the line is composed from who was in
 * the room: "Meeting with Mette Kjær, Søren Bjerre (Mikkel, Jonas)".
 */
@JBossLog
@ApplicationScoped
public class AccountActivityService {

    /** Rows returned when the caller does not say. The page shows five and the tab scrolls. */
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    /** How many names a summary line lists before it says "+n more". */
    private static final int MAX_NAMES_IN_SUMMARY = 3;

    @Inject
    EntityManager em;

    /** Only for reading the stored digest JSON back; it never calls a model from here. */
    @Inject
    AccountSlackDigestService slackDigestService;

    /**
     * What the person registry calls the person at each address on this account.
     *
     * <p>The feed and the people table must not disagree about what somebody is called, and
     * before the registry existed they did: the feed rendered whatever the mailbox typed while
     * the table rendered its own pick. The registry is now the single answer and
     * {@link AccountRelationshipService#bestExternalNames} is the fallback for an address no
     * rebuild has reached yet.
     */
    @Inject
    AccountPersonService personService;

    /** Every source for one client, newest first, capped. */
    public List<AccountActivityDTO> forClient(String clientUuid, int limit) {
        if (clientUuid == null || clientUuid.isBlank()) {
            return List.of();
        }
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<AccountActivityDTO> rows = new ArrayList<>();
        rows.addAll(contractRows(clientUuid, capped));
        rows.addAll(relationshipRows(clientUuid, capped));
        rows.addAll(leadRows(clientUuid, capped));
        rows.addAll(stageRows(clientUuid, capped));
        rows.addAll(signalRows(clientUuid, capped));
        rows.addAll(kycRows(clientUuid, capped));
        rows.addAll(bandRows(clientUuid, capped));
        rows.addAll(meetingRows(clientUuid, capped));
        rows.addAll(noteRows(clientUuid, capped));
        rows.addAll(slackRows(clientUuid, capped));
        rows.addAll(slackMentionRows(clientUuid, capped));

        rows.sort(Comparator.comparing(AccountActivityDTO::occurredAt).reversed()
                .thenComparing(AccountActivityDTO::id));
        return rows.size() > capped ? rows.subList(0, capped) : rows;
    }

    /**
     * The newest activity of any kind per client, for the accounts list's "Last activity"
     * column. One query per source over the whole table rather than one call per row — the
     * list renders several hundred clients.
     */
    public Map<String, AccountActivityDTO> lastActivityForAll() {
        Map<String, AccountActivityDTO> newest = new HashMap<>();

        mergeNewest(newest, """
                select client_uuid, max(modified_at) from client_activity_log
                 where entity_type = 'CONTRACT' group by client_uuid
                """, "CONTRACT", "Contract updated");
        mergeNewest(newest, """
                select client_uuid, max(modified_at) from client_activity_log
                 where entity_type = 'CLIENT' and field_name = 'type' group by client_uuid
                """, "RELATIONSHIP", "Became a customer");
        mergeNewest(newest, """
                select clientuuid, max(created) from sales_lead group by clientuuid
                """, "LEAD", "Lead created");
        mergeNewest(newest, """
                select client_uuid, max(created_at) from account_signal group by client_uuid
                """, "SIGNAL", "Somebody heard something");
        mergeNewest(newest, """
                select client_uuid, max(submitted_at) from questionnaire_submission group by client_uuid
                """, "KYC", "Know-your-client answer");
        mergeNewest(newest, """
                select client_uuid, max(changed_at) from client_band_history group by client_uuid
                """, "BAND", "Band changed");
        // Only meetings that have happened. The sync no longer stores a meeting ahead of its
        // clock, but a stale row here would make an account's "Last activity" a date nobody
        // has lived yet and keep the portfolio's "quiet" badge off an account that is quiet.
        mergeNewest(newest, """
                select client_uuid, max(occurred_at) from account_meeting
                 where occurred_at <= :now group by client_uuid
                """, "CALENDAR", "Meeting", Map.of("now", CalendarTime.now()));
        // "Note added", not the note. mergeNewest takes a constant summary and never reads
        // row content, so the accounts list gets a label and a date for several hundred
        // clients while the words stay on the account page where only that page's reader
        // sees them.
        mergeNewest(newest, """
                select client_uuid, max(created_at) from client_note group by client_uuid
                """, "NOTE", "Note added");
        // A label, not the headline: the list shows several hundred clients and the
        // headline is a paraphrase of a conversation that belongs on the account page.
        mergeNewest(newest, """
                select client_uuid, max(digest_date) from account_slack_digest group by client_uuid
                """, "SLACK", "Slack activity");
        // The same label for a mention in a general channel, because the difference between
        // the two Slack sources is not one this column could carry. Note what this call is
        // competing with: mergeNewest keeps ONE row per client across every source above, so
        // a mention shows as the last activity only when its day beats the newest contract,
        // lead, note and account-space day as well. Dismissed rows are excluded, or the list
        // would report Slack activity on a day the account page itself refuses to show.
        mergeNewest(newest, """
                select client_uuid, max(mention_date) from account_slack_mention
                 where dismissed_at is null group by client_uuid
                """, "SLACK", "Slack activity");

        return newest;
    }

    // ------------------------------------------------------------------------
    // Sources
    // ------------------------------------------------------------------------

    private List<AccountActivityDTO> contractRows(String clientUuid, int limit) {
        Query query = em.createNativeQuery("""
                select id, entity_uuid, entity_name, action, field_name, new_value, modified_by, modified_at
                  from client_activity_log
                 where client_uuid = :clientUuid and entity_type = 'CONTRACT'
                 order by modified_at desc
                """);
        query.setParameter("clientUuid", clientUuid);
        query.setMaxResults(limit);

        List<AccountActivityDTO> rows = new ArrayList<>();
        for (Object[] row : rowsOf(query)) {
            String name = asString(row[2]);
            String action = asString(row[3]);
            String field = asString(row[4]);
            String newValue = asString(row[5]);
            String summary = switch (action == null ? "" : action) {
                case "CREATED" -> "Contract " + orDash(name) + " created";
                case "DELETED" -> "Contract " + orDash(name) + " deleted";
                default -> field == null
                        ? "Contract " + orDash(name) + " updated"
                        : "Contract " + orDash(name) + ": " + field + " → " + orDash(newValue);
            };
            rows.add(new AccountActivityDTO(
                    "contract:" + asString(row[0]),
                    "CONTRACT",
                    summary,
                    toLocalDate(row[7]),
                    firstNameOf(asString(row[6])),
                    "CONTRACT",
                    asString(row[1])));
        }
        return rows;
    }

    /**
     * The day a prospect became a customer.
     *
     * <p>Its own source rather than a CONTRACT row, even though a contract is what caused
     * it: "Became a customer" is a fact about the relationship and it should read that way
     * five years later, when nobody remembers which contract it was. The row is written by
     * {@code ContractService.graduateProspect}, which is the one place the transition can
     * happen.
     */
    private List<AccountActivityDTO> relationshipRows(String clientUuid, int limit) {
        Query query = em.createNativeQuery("""
                select id, old_value, new_value, modified_by, modified_at
                  from client_activity_log
                 where client_uuid = :clientUuid and entity_type = 'CLIENT' and field_name = 'type'
                 order by modified_at desc
                """);
        query.setParameter("clientUuid", clientUuid);
        query.setMaxResults(limit);

        List<AccountActivityDTO> rows = new ArrayList<>();
        for (Object[] row : rowsOf(query)) {
            String from = asString(row[1]);
            String to = asString(row[2]);
            String summary = "PROSPECT".equals(from) && "CLIENT".equals(to)
                    ? "Became a customer — first contract signed"
                    : "Client type changed: " + orDash(from) + " → " + orDash(to);
            rows.add(new AccountActivityDTO(
                    "relationship:" + asString(row[0]),
                    "RELATIONSHIP",
                    summary,
                    toLocalDate(row[4]),
                    firstNameOf(asString(row[3])),
                    null,
                    null));
        }
        return rows;
    }

    private List<AccountActivityDTO> leadRows(String clientUuid, int limit) {
        Query query = em.createNativeQuery("""
                select uuid, description, status, created, won_date, leadmanager
                  from sales_lead
                 where clientuuid = :clientUuid
                 order by created desc
                """);
        query.setParameter("clientUuid", clientUuid);
        query.setMaxResults(limit);

        List<AccountActivityDTO> rows = new ArrayList<>();
        for (Object[] row : rowsOf(query)) {
            String uuid = asString(row[0]);
            String description = orDash(asString(row[1]));
            String status = asString(row[2]);
            String actor = firstNameOf(asString(row[5]));
            rows.add(new AccountActivityDTO(
                    "lead:" + uuid,
                    "LEAD",
                    "Lead created — " + description,
                    toLocalDate(row[3]),
                    actor,
                    "LEAD",
                    uuid));
            if ("WON".equals(status) && row[4] != null) {
                rows.add(new AccountActivityDTO(
                        "lead-won:" + uuid, "LEAD", "Lead won — " + description,
                        toLocalDate(row[4]), actor, "LEAD", uuid));
            }
        }
        return rows;
    }

    /**
     * Stage moves. {@code sales_lead_stage_history} has no entity — {@code SalesService}
     * writes it with a native INSERT — so it is read the same way.
     */
    private List<AccountActivityDTO> stageRows(String clientUuid, int limit) {
        Query query = em.createNativeQuery("""
                select h.id, h.lead_uuid, h.from_stage, h.to_stage, h.changed_by, h.changed_at, l.description
                  from sales_lead_stage_history h
                  join sales_lead l on l.uuid = h.lead_uuid
                 where l.clientuuid = :clientUuid
                 order by h.changed_at desc
                """);
        query.setParameter("clientUuid", clientUuid);
        query.setMaxResults(limit);

        List<AccountActivityDTO> rows = new ArrayList<>();
        for (Object[] row : rowsOf(query)) {
            String from = asString(row[2]);
            String to = asString(row[3]);
            rows.add(new AccountActivityDTO(
                    "stage:" + asString(row[0]),
                    "LEAD",
                    orDash(asString(row[6])) + ": " + (from == null ? "new" : titled(from)) + " → " + titled(to),
                    toLocalDate(row[5]),
                    firstNameOf(asString(row[4])),
                    "LEAD",
                    asString(row[1])));
        }
        return rows;
    }

    /**
     * Signals — captured and decided.
     *
     * <p>The verbatim line is NOT put in the feed. It names a real person at a client
     * organisation and the feed is the most-read surface on the page; the plan tab shows
     * the quote in the one place where somebody is actually working the signal.
     */
    private List<AccountActivityDTO> signalRows(String clientUuid, int limit) {
        Query query = em.createNativeQuery("""
                select uuid, person_name, person_role, signal_type, status, author_uuid,
                       created_at, decided_by, decided_at
                  from account_signal
                 where client_uuid = :clientUuid
                 order by created_at desc
                """);
        query.setParameter("clientUuid", clientUuid);
        query.setMaxResults(limit);

        List<AccountActivityDTO> rows = new ArrayList<>();
        for (Object[] row : rowsOf(query)) {
            String uuid = asString(row[0]);
            String person = asString(row[1]);
            String role = asString(row[2]);
            String type = asString(row[3]);
            rows.add(new AccountActivityDTO(
                    "signal:" + uuid, "SIGNAL", heardSummary(person, role, type),
                    toLocalDate(row[6]), firstNameOf(asString(row[5])), "SIGNAL", uuid));
            if (row[8] != null) {
                rows.add(new AccountActivityDTO(
                        "signal-decided:" + uuid, "SIGNAL",
                        decidedSummary(person, role, type, asString(row[4])),
                        toLocalDate(row[8]), firstNameOf(asString(row[7])), "SIGNAL", uuid));
            }
        }
        return rows;
    }

    private List<AccountActivityDTO> kycRows(String clientUuid, int limit) {
        Query query = em.createNativeQuery("""
                select s.uuid, s.user_uuid, s.submitted_at, q.title
                  from questionnaire_submission s
                  left join questionnaire q on q.uuid = s.questionnaire_uuid
                 where s.client_uuid = :clientUuid
                 order by s.submitted_at desc
                """);
        query.setParameter("clientUuid", clientUuid);
        query.setMaxResults(limit);

        List<AccountActivityDTO> rows = new ArrayList<>();
        for (Object[] row : rowsOf(query)) {
            String title = asString(row[3]);
            rows.add(new AccountActivityDTO(
                    "kyc:" + asString(row[0]),
                    "KYC",
                    "Answered " + (title == null ? "the account questionnaire" : title),
                    toLocalDate(row[2]),
                    firstNameOf(asString(row[1])),
                    "KYC",
                    asString(row[0])));
        }
        return rows;
    }

    private List<AccountActivityDTO> bandRows(String clientUuid, int limit) {
        Query query = em.createNativeQuery("""
                select uuid, from_band, to_band, note, changed_by, changed_at
                  from client_band_history
                 where client_uuid = :clientUuid
                 order by changed_at desc
                """);
        query.setParameter("clientUuid", clientUuid);
        query.setMaxResults(limit);

        List<AccountActivityDTO> rows = new ArrayList<>();
        for (Object[] row : rowsOf(query)) {
            String from = asString(row[1]);
            String to = titled(asString(row[2]));
            String note = asString(row[3]);
            rows.add(new AccountActivityDTO(
                    "band:" + asString(row[0]),
                    "BAND",
                    (from == null ? "Band set to " + to : titled(from) + " → " + to)
                            + (note == null ? "" : " (" + note + ")"),
                    toLocalDate(row[5]),
                    firstNameOf(asString(row[4])),
                    null,
                    null));
        }
        return rows;
    }

    /**
     * Meetings — one line per meeting that has happened, however many of ours were in it —
     * with the attendees folded into the summary in one extra query rather than one per
     * meeting.
     *
     * <p><b>Only meetings that have happened.</b> The sync used to read ninety days ahead
     * and this feed rendered the result as history: on 2026-09-14 half of every account's
     * meeting rows were in the future, and Ældre Sagen's timeline was a daily standup
     * repeated into December. The sync no longer stores a meeting ahead of its clock, but
     * the guard stays here too — the rows it left behind are removed by its next run, not by
     * the deploy, and a timeline must not spend the hours in between showing December. The
     * comparison is on the calendar's own clock ({@link CalendarTime}), which is what
     * {@code occurred_at} is written in; the JVM's UTC would put a meeting still in progress
     * on the feed for two hours every summer afternoon.
     *
     * <p><b>One meeting seen from two mailboxes is one line.</b> Each consenting mailbox
     * stores its own {@code account_meeting} row for the same real-world event, which is what
     * the relationship graph needs and what a reader of the feed does not: <i>"Meeting with
     * Kim Landgrebe (Jeppe)"</i> directly above <i>"Meeting with Kim Landgrebe (Simon)"</i>
     * reads as two meetings. Rows that share {@code ical_uid} — Graph's identity for the
     * event across calendars, V614 — are folded by {@link #foldMeetingRows} into one line
     * naming everybody of ours who was there: <i>"Meeting with Kim Landgrebe (Jeppe,
     * Simon)"</i>. A row with no identity yet (written before V614 and not re-read since) is
     * a line on its own, exactly as before.
     *
     * <p><b>An attendee is named by the best name the feed has seen for their address, not
     * by whatever this meeting's own row happened to carry.</b> Microsoft Graph does not
     * answer two mailboxes the same way: it returned {@code "MYGX (Malthe Yde Andreasen)"}
     * to one and no display name at all to the other, and read per row that produced
     * <i>"MYGX (Malthe Yde Andreasen)"</i> and <i>"mygx@novonordisk.com"</i> as two people.
     * The resolution rule is deliberately the one {@link AccountRelationshipService} uses,
     * shared rather than re-implemented, so the feed and the relationship graph can never
     * disagree about what a person is called.
     *
     * <p>The names are resolved over the attendee rows of the meetings this call is
     * returning, not over the client's entire history, because those rows are already being
     * fetched and a second client-wide query to name people who appear only on meetings the
     * feed is not showing would buy nothing a reader could see.
     *
     * <p>The ordering within a line is alphabetical by displayed name, in Java: the name is
     * not a column the database can sort on. {@link String#CASE_INSENSITIVE_ORDER} rather
     * than natural order keeps the behaviour of {@code utf8mb4_general_ci}, where a bare
     * lower-case address does not jump behind every capitalised name.
     */
    private List<AccountActivityDTO> meetingRows(String clientUuid, int limit) {
        Query meetings = em.createNativeQuery("""
                select uuid, user_uuid, occurred_at, ical_uid
                  from account_meeting
                 where client_uuid = :clientUuid
                   and occurred_at <= :now
                 order by occurred_at desc, uuid
                """);
        meetings.setParameter("clientUuid", clientUuid);
        meetings.setParameter("now", CalendarTime.now());
        meetings.setMaxResults(limit);
        List<Object[]> meetingRows = rowsOf(meetings);
        if (meetingRows.isEmpty()) {
            return List.of();
        }

        List<String> uuids = meetingRows.stream().map(row -> asString(row[0])).toList();
        Query attendees = em.createNativeQuery("""
                select meeting_uuid, lower(email), display_name
                  from account_meeting_attendee
                 where meeting_uuid in (:uuids)
                """);
        attendees.setParameter("uuids", uuids);
        List<Object[]> attendeeRows = rowsOf(attendees);

        List<String[]> named = new ArrayList<>(attendeeRows.size());
        for (Object[] row : attendeeRows) {
            named.add(new String[]{asString(row[1]), asString(row[2])});
        }
        Map<String, String> namesByEmail = AccountRelationshipService.bestExternalNames(named);
        // The registry first. It has already decided that "STMJ (Stephan Mosko Jensen)" and
        // "Stephan Jensen" are one person and what that person is called; asking the raw
        // attendee rows again would put the feed back to spelling them two different ways.
        Map<String, String> registryNames = personService.displayNamesByEmail(clientUuid);

        // A set, because resolving by address makes one new collision possible: two
        // addresses of one person that carry the same display name were two attendee rows
        // and are now one name, and "Meeting with Malthe, Malthe" is worse than the line it
        // replaces.
        Map<String, Set<String>> namesByMeeting = new LinkedHashMap<>();
        for (Object[] row : attendeeRows) {
            String name = registryName(registryNames, asString(row[1]));
            if (name == null) {
                name = AccountRelationshipService.externalNameOf(asString(row[1]), namesByEmail);
            }
            if (name == null) {
                continue;
            }
            namesByMeeting.computeIfAbsent(asString(row[0]), key -> new LinkedHashSet<>()).add(name);
        }

        List<MeetingRow> rows = new ArrayList<>(meetingRows.size());
        for (Object[] row : meetingRows) {
            rows.add(new MeetingRow(asString(row[0]), firstNameOf(asString(row[1])),
                    toLocalDate(row[2]), asString(row[3])));
        }
        return foldMeetingRows(rows, namesByMeeting);
    }

    /**
     * One {@code account_meeting} row as the fold needs it: which row, who read it, when,
     * and — when the sync has filled it in — which meeting it is.
     */
    record MeetingRow(String uuid, String actor, LocalDate occurredOn, String icalUid) { }

    /**
     * One line per meeting, folding the rows that share an identity.
     *
     * <p>Rows arrive newest first and the fold keeps that order: a group's line takes the
     * position of its first row, its id is that row's uuid — stable across loads, because
     * the query orders by uuid within a day — and its date is the latest the group carries,
     * which for rows that share an identity is the same date. Attendee names are the union
     * across the group, so a name one mailbox had and another lacked is still named; the
     * actors are every mailbox owner, alphabetical, and go both into the line and into
     * {@code actor}, where the footer shows them.
     *
     * <p>A row with no identity folds with nothing and is its own line. Static and free of
     * the database so the fold can be pinned without one.
     */
    static List<AccountActivityDTO> foldMeetingRows(List<MeetingRow> rows, Map<String, Set<String>> namesByMeeting) {
        Map<String, List<MeetingRow>> byMeeting = new LinkedHashMap<>();
        for (MeetingRow row : rows) {
            String key = row.icalUid() == null || row.icalUid().isBlank()
                    ? "row:" + row.uuid()
                    : "meeting:" + row.icalUid();
            byMeeting.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<AccountActivityDTO> out = new ArrayList<>(byMeeting.size());
        for (List<MeetingRow> group : byMeeting.values()) {
            MeetingRow first = group.get(0);
            Set<String> names = new LinkedHashSet<>();
            List<String> actors = new ArrayList<>();
            LocalDate occurredOn = first.occurredOn();
            for (MeetingRow row : group) {
                names.addAll(namesByMeeting.getOrDefault(row.uuid(), Set.of()));
                if (row.actor() != null && !actors.contains(row.actor())) {
                    actors.add(row.actor());
                }
                if (row.occurredOn() != null && (occurredOn == null || row.occurredOn().isAfter(occurredOn))) {
                    occurredOn = row.occurredOn();
                }
            }
            List<String> sortedNames = new ArrayList<>(names);
            sortedNames.sort(String.CASE_INSENSITIVE_ORDER);
            actors.sort(String.CASE_INSENSITIVE_ORDER);
            out.add(new AccountActivityDTO(
                    "meeting:" + first.uuid(),
                    "CALENDAR",
                    meetingLine(sortedNames, actors),
                    occurredOn,
                    actors.isEmpty() ? null : String.join(", ", actors),
                    null,
                    null));
        }
        return out;
    }

    /** <i>"Meeting with Kim Landgrebe, Janni Høyer Thoft (Jeppe, Simon)"</i>. */
    static String meetingLine(List<String> names, List<String> actors) {
        String line = "Meeting with " + joinNames(names);
        return actors == null || actors.isEmpty() ? line : line + " (" + cutList(actors) + ")";
    }

    /**
     * Notes — the one source in this feed a person typed.
     *
     * <p><b>The text IS in the feed, unlike a signal's.</b> {@link #signalRows} keeps the
     * verbatim quote out (see its javadoc) because the plan tab shows that quote where
     * somebody is actually working the signal. A note has no second surface: strip its text
     * and the row reads "Hans added a note", which nobody would ever bother to write. So the
     * line goes in, and this feed becomes a surface carrying free text that may name a third
     * party — see {@code ClientNote}'s javadoc for what that obliges and what is not built.
     *
     * <p>{@code created_at} is the only date a note has, deliberately: a note can be removed
     * but never rewritten, so nothing can ever re-date a row under the person reading it.
     *
     * <p>{@code refType}/{@code refUuid} name the note itself rather than the null/null that
     * {@link #bandRows} and {@link #meetingRows} pass, so the row can say which note it came
     * from. Nothing offers an affordance on it yet; a row that cannot name its source could
     * never grow one.
     */
    private List<AccountActivityDTO> noteRows(String clientUuid, int limit) {
        Query query = em.createNativeQuery("""
                select uuid, note_text, author_uuid, created_at
                  from client_note
                 where client_uuid = :clientUuid
                 order by created_at desc
                """);
        query.setParameter("clientUuid", clientUuid);
        query.setMaxResults(limit);

        List<AccountActivityDTO> rows = new ArrayList<>();
        for (Object[] row : rowsOf(query)) {
            String uuid = asString(row[0]);
            rows.add(new AccountActivityDTO(
                    "note:" + uuid,
                    "NOTE",
                    orDash(asString(row[1])),
                    toLocalDate(row[3]),
                    firstNameOf(asString(row[2])),
                    "NOTE",
                    uuid));
        }
        return rows;
    }

    /**
     * Slack days — one row per Copenhagen day of the linked account space that had
     * something to say (V594).
     *
     * <p><b>No message ever reaches this feed.</b> The row's line is the model's headline,
     * and under it the row carries the validated reading — decisions, next steps, risks,
     * client asks, people named, topics — plus the deep link into Slack, which is the one
     * place the actual words live. {@code refUuid} names the digest; nothing in Intra
     * opens it yet, but a row that cannot name its source could never grow an affordance.
     *
     * <h2>A day with no reading is not a row</h2>
     * This used to emit every digested day, falling back to a counts line
     * ("#a_novo: 4 messages (Nicolas, Stephan)") when the model wrote no headline, on the
     * reasoning that "a day with activity is never silently absent". That reasoning
     * confused two different readers. Whether the lane ran and what it cost is an
     * operational question, and Settings → CRM answers it from {@code crm_slack_sync_run};
     * the account timeline answers "what happened with this client", and a counts line
     * says nothing about that. It is strictly worse than absence: it occupies a row, reads
     * like news, and resolves to a thread about how to get a Coupa account.
     *
     * <p>The v2 rubric made this acute rather than causing it. Grading by what KIND of
     * event a day held — instead of by whether the sentence contained a decision, a risk
     * or an ask — correctly demotes our own engineering to NONE, so the first v2 run
     * turned 8 empty days into 12 of 20. Every one of them was drawing a line on somebody's
     * account page.
     *
     * <p>And they did not merely sit there. {@link #forClient} caps each source at the
     * limit, merges, sorts by date and truncates to the limit again — so a nothing-row
     * competes by date with a contract, a meeting or a note, and twelve of them push real
     * history off the end of the feed.
     *
     * <p>So: no headline, no row. That is exactly what {@link #slackMentionRows} has always
     * done ("a day the model found nothing to say about a client produces no row at all,
     * rather than a row saying nothing"), and the two lanes disagreeing was the bug. The
     * filter is in the query, not applied afterwards, so the limit is spent on rows that
     * carry something.
     *
     * <p>Nothing is lost: the digest row, its counts, its participants and its cursor all
     * still exist in {@code account_slack_digest}, which is what the next run reads to know
     * where it got to.
     *
     * <p>{@code actor} is null: a day is many people, and they are listed in the digest.
     */
    private List<AccountActivityDTO> slackRows(String clientUuid, int limit) {
        Query digests = em.createNativeQuery("""
                select uuid, channel_name, digest_date, message_count, thread_reply_count,
                       permalink, relevance, headline, digest_json
                  from account_slack_digest
                 where client_uuid = :clientUuid
                   and headline is not null and headline <> ''
                 order by digest_date desc
                """);
        digests.setParameter("clientUuid", clientUuid);
        digests.setMaxResults(limit);
        List<Object[]> digestRows = rowsOf(digests);
        if (digestRows.isEmpty()) {
            return List.of();
        }

        List<String> uuids = digestRows.stream().map(row -> asString(row[0])).toList();
        Query participants = em.createNativeQuery("""
                select digest_uuid, user_uuid
                  from account_slack_digest_participant
                 where digest_uuid in (:uuids)
                 order by digest_uuid, message_count desc, user_uuid
                """);
        participants.setParameter("uuids", uuids);
        Map<String, List<String>> namesByDigest = new LinkedHashMap<>();
        for (Object[] row : rowsOf(participants)) {
            String name = firstNameOf(asString(row[1]));
            if (name != null) {
                namesByDigest.computeIfAbsent(asString(row[0]), key -> new ArrayList<>()).add(name);
            }
        }

        List<AccountActivityDTO> rows = new ArrayList<>();
        for (Object[] row : digestRows) {
            String uuid = asString(row[0]);
            String channel = asString(row[1]);
            int messages = asInt(row[3]);
            int replies = asInt(row[4]);
            String headline = asString(row[7]);
            List<String> names = namesByDigest.getOrDefault(uuid, List.of());
            SlackDigestContent content = slackDigestService.fromJson(asString(row[8]));
            rows.add(new AccountActivityDTO(
                    "slack:" + uuid,
                    "SLACK",
                    slackSummary(channel, headline, messages, replies, names),
                    toLocalDate(row[2]),
                    null,
                    "SLACK",
                    uuid,
                    new SlackDigestDTO(channel, messages, replies, names, asString(row[5]),
                            asString(row[6]), content)));
        }
        return rows;
    }

    /**
     * Slack mentions — one row per day a general channel the firm listed talked about this
     * client (V602).
     *
     * <p>Same shape as {@link #slackRows} and the same {@code SLACK} source, because to a
     * reader "#ledelse said this about Ørsted on Tuesday" is the same kind of fact as a day
     * in the account's own channel. Three things differ, and each one follows from the
     * channel being somebody else's rather than the account's:
     *
     * <ul>
     *   <li><b>A dismissed row is gone from here.</b> A general channel says a company name
     *       and a model decides which client that was; it can be wrong, so the account's
     *       people can say "not about this client". That is a judgement about the row, not
     *       a deletion — {@code dismissed_by} and {@code dismissed_at} keep who said so —
     *       but the feed must honour it immediately and everywhere, so the filter is in the
     *       query rather than applied to what it returned.</li>
     *   <li><b>There is no reply count.</b> The extractor cites individual lines, and a
     *       cited line may itself be a thread reply; there is no top-level/reply split to
     *       report and inventing one would double-count. The zero passed on to
     *       {@link SlackDigestDTO} is the honest value, and {@code messageCount} is the
     *       number of lines that were about THIS client, not the channel's traffic that
     *       day — which is why a mention row's counts read so much smaller than a digest's.</li>
     *   <li><b>The headline always exists.</b> {@code headline} is NOT NULL and a stored row
     *       is never {@code NONE}-relevant — a day the model found nothing to say about a
     *       client produces no row at all, rather than a row saying nothing. That makes
     *       {@link #slackSummary}'s counts fallback unreachable from here; the line goes
     *       through it anyway, because two composers for one kind of row drift apart. As of
     *       the v2 rubric {@link #slackRows} filters the same way, so the fallback is now
     *       unreachable from BOTH lanes and survives only as a guard.</li>
     * </ul>
     *
     * <p>{@code refType} is {@code SLACK_MENTION} so the tab can offer the dismissal on
     * exactly these rows; {@code actor} is null for the same reason it is on a digest — a
     * day is many people, and they are in the participants.
     */
    private List<AccountActivityDTO> slackMentionRows(String clientUuid, int limit) {
        Query mentions = em.createNativeQuery("""
                select uuid, channel_name, mention_date, message_count, permalink,
                       relevance, headline, digest_json
                  from account_slack_mention
                 where client_uuid = :clientUuid and dismissed_at is null
                 order by mention_date desc
                """);
        mentions.setParameter("clientUuid", clientUuid);
        mentions.setMaxResults(limit);
        List<Object[]> mentionRows = rowsOf(mentions);
        if (mentionRows.isEmpty()) {
            return List.of();
        }

        List<String> uuids = mentionRows.stream().map(row -> asString(row[0])).toList();
        Query participants = em.createNativeQuery("""
                select mention_uuid, user_uuid
                  from account_slack_mention_participant
                 where mention_uuid in (:uuids)
                 order by mention_uuid, message_count desc, user_uuid
                """);
        participants.setParameter("uuids", uuids);
        Map<String, List<String>> namesByMention = new LinkedHashMap<>();
        for (Object[] row : rowsOf(participants)) {
            String name = firstNameOf(asString(row[1]));
            if (name != null) {
                namesByMention.computeIfAbsent(asString(row[0]), key -> new ArrayList<>()).add(name);
            }
        }

        List<AccountActivityDTO> rows = new ArrayList<>();
        for (Object[] row : mentionRows) {
            String uuid = asString(row[0]);
            String channel = asString(row[1]);
            int messages = asInt(row[3]);
            List<String> names = namesByMention.getOrDefault(uuid, List.of());
            SlackDigestContent content = slackDigestService.fromJson(asString(row[7]));
            rows.add(new AccountActivityDTO(
                    "slack-mention:" + uuid,
                    "SLACK",
                    slackSummary(channel, asString(row[6]), messages, 0, names),
                    toLocalDate(row[2]),
                    null,
                    "SLACK_MENTION",
                    uuid,
                    new SlackDigestDTO(channel, messages, 0, names, asString(row[4]),
                            asString(row[5]), content)));
        }
        return rows;
    }

    /**
     * The Slack row's line. The channel first, as spec §3.2's own example has it
     * ("#a_banedanmark: …"); then the headline, or the counts and who was talking when the
     * model wrote nothing — a day that was only "jeg er hjemmefra" still happened.
     */
    static String slackSummary(String channel, String headline, int messages, int replies, List<String> names) {
        String prefix = "#" + (channel == null || channel.isBlank() ? "slack" : channel) + ": ";
        if (headline != null && !headline.isBlank()) {
            return prefix + headline.trim();
        }
        int total = Math.max(messages, 0) + Math.max(replies, 0);
        String count = total == 1 ? "1 message" : total + " messages";
        if (names == null || names.isEmpty()) {
            return prefix + count;
        }
        return prefix + count + " (" + joinNames(names) + ")";
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private void mergeNewest(Map<String, AccountActivityDTO> into, String sql, String source, String summary) {
        mergeNewest(into, sql, source, summary, Map.of());
    }

    /** As above, with the query's named parameters bound — never a value spliced into the SQL. */
    private void mergeNewest(Map<String, AccountActivityDTO> into, String sql, String source, String summary,
                             Map<String, Object> params) {
        Query query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        List<Object[]> rows = rowsOf(query);
        for (Object[] row : rows) {
            String clientUuid = asString(row[0]);
            LocalDate when = toLocalDate(row[1]);
            if (clientUuid == null || when == null) {
                continue;
            }
            AccountActivityDTO existing = into.get(clientUuid);
            if (existing == null || existing.occurredAt().isBefore(when)) {
                into.put(clientUuid, new AccountActivityDTO(
                        source.toLowerCase() + ":" + clientUuid, source, summary, when, null, null, null));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rowsOf(Query query) {
        return query.getResultList();
    }

    /**
     * A first name for a uuid. Cached per request-scoped call chain would be nicer, but the
     * feed is capped at {@value #MAX_LIMIT} rows and {@code User.findById} hits Hibernate's
     * first-level cache after the first lookup of the same person.
     */
    private static String firstNameOf(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            return null;
        }
        User user = User.findById(userUuid.trim());
        if (user == null) {
            return null;
        }
        return user.getFirstname() != null && !user.getFirstname().isBlank()
                ? user.getFirstname()
                : user.getUsername();
    }

    /**
     * What the registry calls the person at this address, or null when it has never seen it.
     *
     * <p>Keyed on the lower-cased address exactly as {@code displayNamesByEmail} returns it.
     * {@link Locale#ROOT}, never the default locale — a Turkish default lower-cases {@code I}
     * to a dotless {@code ı} and an address quietly stops matching itself, which would show up
     * as the feed silently falling back to the mailbox's own spelling for one person.
     */
    private static String registryName(Map<String, String> registryNames, String email) {
        if (registryNames == null || registryNames.isEmpty() || email == null) {
            return null;
        }
        String key = email.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return null;
        }
        String name = registryNames.get(key);
        return name == null || name.isBlank() ? null : name;
    }

    static String joinNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "the client";
        }
        return cutList(names);
    }

    /** The first {@value #MAX_NAMES_IN_SUMMARY}, then "+n more". The caller decides what an empty list says. */
    private static String cutList(List<String> names) {
        if (names.size() <= MAX_NAMES_IN_SUMMARY) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, MAX_NAMES_IN_SUMMARY))
                + " +" + (names.size() - MAX_NAMES_IN_SUMMARY) + " more";
    }

    static String titled(String enumName) {
        if (enumName == null || enumName.isBlank()) {
            return "—";
        }
        String lower = enumName.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * <b>A signal's subject is a person, or it is nothing.</b> The extractor names one when
     * the line contained one; when it did not, the four typed kinds still say what the
     * capture was ABOUT, and {@code OTHER} — the honest default for a line nothing could
     * classify — says only that something was heard. Reusing the type label as if it were
     * the subject is what produced "Heard: something" and "Signal parked — something".
     */
    static String heardSummary(String personName, String personRole, String signalType) {
        String person = namedPerson(personName, personRole);
        if (person != null) {
            return "Heard: " + person;
        }
        String topic = typedTopic(signalType);
        return topic == null ? "Heard something" : "Heard about " + topic;
    }

    /**
     * The decision row. It shares the capture row's subject, so it shared the defect; with
     * nothing to name it stops after the decision rather than trailing a dash into nothing.
     */
    static String decidedSummary(String personName, String personRole, String signalType, String status) {
        String decision = "Signal " + decisionLabel(status);
        String person = namedPerson(personName, personRole);
        if (person != null) {
            return decision + " — " + person;
        }
        String topic = typedTopic(signalType);
        return topic == null ? decision : decision + " — " + topic;
    }

    /** "Mette Kjær (CIO)", "Mette Kjær", or null when the extractor found nobody. */
    private static String namedPerson(String name, String role) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return role == null || role.isBlank() ? name : name + " (" + role + ")";
    }

    /**
     * The four typed kinds as a noun phrase that reads after "about". Null for {@code OTHER}
     * and for anything unrecognised, because {@link #typeLabel}'s "something" is a
     * placeholder standing in for a label, not a topic a sentence can be built on.
     */
    private static String typedTopic(String signalType) {
        return switch (signalType == null ? "" : signalType) {
            case "ORG_CHANGE", "COMING_PROJECT", "CONTACT_MOVED", "TENDER" -> typeLabel(signalType);
            default -> null;
        };
    }

    /**
     * The four signal kinds as English.
     *
     * <p><b>The {@code default} branch no longer reaches a reader.</b> {@link #typedTopic} is
     * its only production caller and never passes {@code OTHER}, null or an unrecognised
     * value, so "something" now survives only in {@code AccountActivityServiceTest}. Keep
     * both: the branch is what makes the switch total, and that assertion is the only place
     * the four labels are pinned.
     */
    static String typeLabel(String signalType) {
        return switch (signalType == null ? "" : signalType) {
            case "ORG_CHANGE" -> "an organisational change";
            case "COMING_PROJECT" -> "a coming project";
            case "CONTACT_MOVED" -> "a contact moving on";
            case "TENDER" -> "a tender";
            default -> "something";
        };
    }

    static String decisionLabel(String status) {
        return switch (status == null ? "" : status) {
            case "LEAD_CREATED" -> "turned into a lead";
            case "PARKED" -> "parked";
            case "NOT_RELEVANT" -> "closed as not relevant";
            default -> "decided";
        };
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigInteger || value instanceof Number) {
            return value.toString();
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        return null;
    }
}
