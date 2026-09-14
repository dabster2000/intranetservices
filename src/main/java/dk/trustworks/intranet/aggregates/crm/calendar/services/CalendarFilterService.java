package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarSyncTally.LearnedColleagueEmail;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The database half of the calendar filters: it reads the four things
 * {@link AccountCalendarSyncService} needs in order to tell a sales meeting from a standup,
 * and it writes back the one thing the sync works out for itself.
 *
 * <h2>Read once per run, never per mailbox</h2>
 * {@link #load()} is called exactly once at the top of {@code syncAll()}, inside its own
 * short transaction, in the same breath as the domain index and the consent list. The
 * queries behind it are whole-table reads of the contract, employee-status, placement and
 * learned-address tables — per mailbox they would be a hundred repetitions of the same
 * answer, and worse, they could not be done at all without either holding a transaction
 * across a Graph call or opening one between every pair of them. The §P9 M1 rule at the top
 * of the sync service forbids the first; the second is just waste.
 *
 * <h2>The self-learning address table, and the hole it patches</h2>
 * The colleague-at-client filter matches on the display name Graph returns. For some
 * mailboxes Graph returns no display name at all: {@code mygx@novonordisk.com} arrives as
 * {@code "MYGX (Malthe Yde Andreasen)"} on ten attendee rows and as a bare
 * {@code mygx@novonordisk.com} on twenty. A bare address can never be name-matched, so
 * Malthe kept reappearing on the Novo Nordisk account as an external contact called
 * "mygx@novonordisk.com" — and, because the relationship graph groups on the display name,
 * as a SECOND external person distinct from the named one. Six addresses in production are
 * affected.
 *
 * <p>{@code crm_colleague_client_email} closes it by writing down what the name match has
 * already proved. The sync does not guess and a human does not maintain it: every row is a
 * fact the sync derived on a run where the name happened to be present, kept so that a
 * later run which sees only the address still knows whose it is. There is no UI, and there
 * is deliberately no attempt to infer ownership from the address itself — {@code mygx} says
 * nothing about Malthe to anything but Novo Nordisk's own directory.
 *
 * <h2>Writes happen inside the mailbox's own short transaction</h2>
 * {@link #rememberColleagueEmails} is called from the same {@code QuarkusTransaction} block
 * that persists the mailbox's meetings, never while a Graph round trip is in flight. Same
 * §P9 M1 rule, same reason: a pooled connection held across an HTTP call to Microsoft is a
 * connection the rest of the application cannot have.
 */
@JBossLog
@ApplicationScoped
public class CalendarFilterService {

    @Inject
    EntityManager em;

    /**
     * Everything one run needs, loaded together.
     *
     * <p>Must be called inside a transaction — {@code syncAll()} wraps it in
     * {@code QuarkusTransaction.requiringNew()} exactly as it does the domain index.
     */
    CalendarFilters load() {
        CalendarFilters filters = CalendarFilters.of(
                DeliveryContractIndex.of(loadDeliveryContracts()),
                ColleagueDirectory.of(loadColleagues(), loadPlacements()),
                loadColleagueClientEmails());
        log.infof("Calendar filters loaded: deliveryPairs=%d colleagues=%d placements=%d knownColleagueEmails=%d",
                filters.delivery().size(), filters.colleagues().size(),
                filters.colleagues().placementCount(), filters.knownColleagueEmailCount());
        return filters;
    }

    /**
     * Every consultant assignment, as (client, person, window).
     *
     * <p>The whole table, unfiltered by status and unfiltered by date. Status is ignored on
     * purpose (decision D1 — see {@link DeliveryContractIndex}), and the date cannot be
     * narrowed because a first-run mailbox is read twelve months back and has to be judged
     * against the assignments that were running then, not the ones running tonight.
     */
    private List<DeliveryContractIndex.DeliveryContractRow> loadDeliveryContracts() {
        Query query = em.createNativeQuery("""
                select ct.clientuuid, cc.useruuid, cc.activefrom, cc.activeto
                  from contract_consultants cc
                  join contracts ct on ct.uuid = cc.contractuuid
                 where cc.useruuid is not null
                """);

        List<DeliveryContractIndex.DeliveryContractRow> rows = new ArrayList<>();
        for (Object[] row : resultsOf(query)) {
            String clientUuid = asString(row[0]);
            String userUuid = asString(row[1]);
            if (clientUuid == null || userUuid == null) {
                continue;
            }
            rows.add(new DeliveryContractIndex.DeliveryContractRow(
                    clientUuid, userUuid, toLocalDate(row[2]), toLocalDate(row[3])));
        }
        return rows;
    }

    /**
     * Every employee's name with every one of their status changes.
     *
     * <p>Terminated people are read too, and that is the point: the directory has to be able
     * to answer "was this person one of ours on the day of THAT meeting", and a former
     * colleague now working at the client must be kept as a client contact rather than
     * filtered as one of us.
     */
    private List<ColleagueDirectory.ColleagueRow> loadColleagues() {
        Query query = em.createNativeQuery("""
                select u.uuid, u.firstname, u.lastname, s.status, s.statusdate
                  from user u
                  join userstatus s on s.useruuid = u.uuid
                 where s.status is not null
                   and s.statusdate is not null
                """);

        List<ColleagueDirectory.ColleagueRow> rows = new ArrayList<>();
        for (Object[] row : resultsOf(query)) {
            String userUuid = asString(row[0]);
            StatusType status = toStatusType(asString(row[3]));
            LocalDate statusDate = toLocalDate(row[4]);
            if (userUuid == null || status == null || statusDate == null) {
                continue;
            }
            rows.add(new ColleagueDirectory.ColleagueRow(
                    userUuid, asString(row[1]), asString(row[2]), status, statusDate));
        }
        return rows;
    }

    /**
     * Every client each employee has ever been placed at — the gate on the widened name
     * rule (spec §4.1 rule b).
     *
     * <p>Two sources, because there are two ways we know somebody sits at a client. The
     * first is the assignment itself, every {@code contract_consultants} row the person ever
     * had. The second is an address we have already learned to be theirs: a mailbox issued
     * by the client's own domain is the strongest possible evidence that they were there,
     * and it covers the consultant whose assignment was never registered as a contract line.
     *
     * <p><b>Deliberately read separately from {@link #loadDeliveryContracts()}</b>, even
     * though the first query is that one without its dates. The delivery index drops rows
     * whose window is inverted and cares about exactly when somebody sat there; this asks
     * only whether they ever did, and a row that is useless as a window is still perfectly
     * good evidence of a placement. Deriving one from the other would quietly couple the
     * name rule to the delivery rule's row hygiene, and a {@code DISTINCT} over two narrow
     * columns is cheap — production has ~300 clients and ~200 employees.
     *
     * <p><b>No date predicate</b>, for the same reason the delivery load has none and then
     * some: this index answers "could our Sara Vest plausibly be the Sara on a Banedanmark
     * invitation at all", and an assignment that finished in 2023 answers it. The
     * employment-on-the-meeting-date test in {@link ColleagueDirectory} is what decides
     * whether the attendee is dropped.
     */
    private List<ColleagueDirectory.PlacementRow> loadPlacements() {
        List<ColleagueDirectory.PlacementRow> rows = new ArrayList<>();

        // The client hangs off the contract, not off the assignment line — same join the
        // delivery load uses, and the only place contract_consultants carries a client.
        Query assignments = em.createNativeQuery("""
                select distinct cc.useruuid, ct.clientuuid
                  from contract_consultants cc
                  join contracts ct on ct.uuid = cc.contractuuid
                 where cc.useruuid is not null
                   and ct.clientuuid is not null
                """);
        collectPlacements(assignments, rows);

        // An address the sync has already proved is a colleague's, on a domain a client
        // claims. substring_index(email, '@', -1) is the domain; both columns are written
        // lower-cased and both tables are utf8mb4_general_ci, so the join is a plain match.
        Query learned = em.createNativeQuery("""
                select distinct cce.user_uuid, cd.client_uuid
                  from crm_colleague_client_email cce
                  join client_domain cd on cd.domain = substring_index(cce.email, '@', -1)
                 where cce.user_uuid is not null
                """);
        collectPlacements(learned, rows);

        return rows;
    }

    /** Both placement queries answer with (user, client) in that order. */
    private static void collectPlacements(Query query, List<ColleagueDirectory.PlacementRow> rows) {
        for (Object[] row : resultsOf(query)) {
            String userUuid = asString(row[0]);
            String clientUuid = asString(row[1]);
            if (userUuid == null || clientUuid == null) {
                continue;
            }
            rows.add(new ColleagueDirectory.PlacementRow(userUuid.trim(), clientUuid.trim()));
        }
    }

    /**
     * Every address already known to belong to a colleague at a client, lower-cased, mapped
     * to WHOSE it is.
     *
     * <p>The uuid travels with the address because the address rule has to ask the same
     * employment question the name rule asks — see
     * {@link CalendarFilters#isColleagueEmailOn}. Reading only the addresses is what made
     * that rule date-blind.
     */
    private Map<String, String> loadColleagueClientEmails() {
        Query query = em.createNativeQuery("select email, user_uuid from crm_colleague_client_email");

        Map<String, String> emails = new LinkedHashMap<>();
        for (Object[] row : resultsOf(query)) {
            String email = asString(row[0]);
            String userUuid = asString(row[1]);
            if (email != null && !email.isBlank() && userUuid != null && !userUuid.isBlank()) {
                emails.put(email.trim().toLowerCase(Locale.ROOT), userUuid.trim());
            }
        }
        return emails;
    }

    /**
     * Writes down the colleague addresses this mailbox's pass identified by name.
     *
     * <p>An upsert rather than an insert: an address the sync sees on every run should
     * refresh {@code last_seen_at} — that column is how anybody later asking "is this row
     * still true" can tell a live mapping from one left over from a consultant who
     * finished at the client in 2024 — while {@code first_seen_at} must stay the day it was
     * discovered.
     *
     * <p><b>A MANUAL row is never overwritten by a LEARNED one.</b> If a human ever has to
     * correct a mapping, the correction has to survive the next nightly run, or it would be
     * undone within a day by the very inference it was correcting. Its timestamp is still
     * refreshed — the row was seen, whoever decided it.
     *
     * <p>Must be called inside a transaction, and never with a Graph call in flight.
     */
    void rememberColleagueEmails(Collection<LearnedColleagueEmail> learned, LocalDateTime now) {
        if (learned == null || learned.isEmpty()) {
            return;
        }
        for (LearnedColleagueEmail item : learned) {
            if (item == null || item.email() == null || item.userUuid() == null) {
                continue;
            }
            Query query = em.createNativeQuery("""
                    insert into crm_colleague_client_email
                        (email, user_uuid, display_name, source, first_seen_at, last_seen_at)
                    values (:email, :userUuid, :displayName, 'LEARNED', :now, :now)
                    on duplicate key update
                        user_uuid    = if(source = 'MANUAL', user_uuid, values(user_uuid)),
                        display_name = if(source = 'MANUAL', display_name,
                                          coalesce(values(display_name), display_name)),
                        last_seen_at = values(last_seen_at)
                    """);
            query.setParameter("email", item.email());
            query.setParameter("userUuid", item.userUuid());
            query.setParameter("displayName", item.displayName());
            query.setParameter("now", now);
            query.executeUpdate();
        }
    }

    // ------------------------------------------------------------------------
    // Driver quirks
    // ------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Object[]> resultsOf(Query query) {
        return query.getResultList();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * An unknown status value is not a status. New enum constants land in the database
     * before the code that knows them, and a {@code valueOf} blowing up here would take
     * the whole nightly run with it.
     */
    private static StatusType toStatusType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return StatusType.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            log.warnf("Calendar filters: unknown user status '%s' ignored", value);
            return null;
        }
    }

    /** MariaDB hands a DATE back as {@code java.sql.Date}, a DATETIME as a Timestamp. */
    static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        return null;
    }
}
