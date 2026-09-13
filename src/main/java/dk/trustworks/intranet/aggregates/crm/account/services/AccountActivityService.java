package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountActivityDTO;
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
import java.util.List;
import java.util.Map;

/**
 * The derived account feed (CRM spec §3.2). Every row but one is something a system
 * already saw; {@code NOTE} is the single hand-typed source, and it exists for what no
 * system recorded.
 *
 * <p>Eight sources are unioned here and sorted newest first:
 *
 * <table>
 *   <tr><td>CONTRACT</td><td>{@code client_activity_log} rows about contracts</td></tr>
 *   <tr><td>LEAD</td><td>{@code sales_lead} created / won / lost and {@code sales_lead_stage_history}</td></tr>
 *   <tr><td>SIGNAL</td><td>{@code account_signal} captured and decided</td></tr>
 *   <tr><td>KYC</td><td>{@code questionnaire_submission} about the client</td></tr>
 *   <tr><td>BAND</td><td>{@code client_band_history}</td></tr>
 *   <tr><td>CALENDAR</td><td>{@code account_meeting} + its external attendees</td></tr>
 *   <tr><td>NOTE</td><td>{@code client_note} — the one line somebody typed</td></tr>
 *   <tr><td>SLACK</td><td><b>nothing</b> — see below</td></tr>
 * </table>
 *
 * <p><b>There is no SLACK producer.</b> Account spaces, the {@code /signal} command and the
 * daily channel summary need Slack inbound, which has no staging signing secret and so
 * cannot be verified anywhere before production. The source is part of the contract the
 * frontend already renders, and the timeline shows the lane as having no source connected
 * rather than inventing rows for it. That is a recorded gap, not an oversight.
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

    /** Every source for one client, newest first, capped. */
    public List<AccountActivityDTO> forClient(String clientUuid, int limit) {
        if (clientUuid == null || clientUuid.isBlank()) {
            return List.of();
        }
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<AccountActivityDTO> rows = new ArrayList<>();
        rows.addAll(contractRows(clientUuid, capped));
        rows.addAll(leadRows(clientUuid, capped));
        rows.addAll(stageRows(clientUuid, capped));
        rows.addAll(signalRows(clientUuid, capped));
        rows.addAll(kycRows(clientUuid, capped));
        rows.addAll(bandRows(clientUuid, capped));
        rows.addAll(meetingRows(clientUuid, capped));
        rows.addAll(noteRows(clientUuid, capped));

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
        mergeNewest(newest, """
                select client_uuid, max(occurred_at) from account_meeting group by client_uuid
                """, "CALENDAR", "Meeting");
        // "Note added", not the note. mergeNewest takes a constant summary and never reads
        // row content, so the accounts list gets a label and a date for several hundred
        // clients while the words stay on the account page where only that page's reader
        // sees them.
        mergeNewest(newest, """
                select client_uuid, max(created_at) from client_note group by client_uuid
                """, "NOTE", "Note added");

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
     * Meetings, with the attendees folded into the summary in one extra query rather than
     * one per meeting.
     */
    private List<AccountActivityDTO> meetingRows(String clientUuid, int limit) {
        Query meetings = em.createNativeQuery("""
                select uuid, user_uuid, occurred_at, duration_minutes
                  from account_meeting
                 where client_uuid = :clientUuid
                 order by occurred_at desc
                """);
        meetings.setParameter("clientUuid", clientUuid);
        meetings.setMaxResults(limit);
        List<Object[]> meetingRows = rowsOf(meetings);
        if (meetingRows.isEmpty()) {
            return List.of();
        }

        List<String> uuids = meetingRows.stream().map(row -> asString(row[0])).toList();
        Query attendees = em.createNativeQuery("""
                select meeting_uuid, coalesce(display_name, email)
                  from account_meeting_attendee
                 where meeting_uuid in (:uuids)
                 order by meeting_uuid, coalesce(display_name, email)
                """);
        attendees.setParameter("uuids", uuids);
        Map<String, List<String>> namesByMeeting = new LinkedHashMap<>();
        for (Object[] row : rowsOf(attendees)) {
            namesByMeeting.computeIfAbsent(asString(row[0]), key -> new ArrayList<>()).add(asString(row[1]));
        }

        List<AccountActivityDTO> rows = new ArrayList<>();
        for (Object[] row : meetingRows) {
            String uuid = asString(row[0]);
            String actor = firstNameOf(asString(row[1]));
            List<String> names = namesByMeeting.getOrDefault(uuid, List.of());
            rows.add(new AccountActivityDTO(
                    "meeting:" + uuid,
                    "CALENDAR",
                    "Meeting with " + joinNames(names) + (actor == null ? "" : " (" + actor + ")"),
                    toLocalDate(row[2]),
                    actor,
                    null,
                    null));
        }
        return rows;
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

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private void mergeNewest(Map<String, AccountActivityDTO> into, String sql, String source, String summary) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
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

    static String joinNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "the client";
        }
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
