package dk.trustworks.intranet.aggregates.crm.calendar.services;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who was <b>delivering</b> at which client on which day — the index behind the delivery
 * filter (decision D1, 2026-09-14).
 *
 * <h2>The problem this exists to solve</h2>
 * A consultant placed at a client has standups, sprint reviews, refinements and delivery
 * meetings with that client all day, every day, for months. Every one of them arrives from
 * Graph looking exactly like a sales meeting: a Trustworks mailbox, attendees on the
 * client's e-mail domain, a date and a duration. Counted as client contact they are worse
 * than noise — they are a landslide. Measured on production on 2026-09-14, <b>630 of 960
 * meetings</b> in {@code account_meeting} were delivery, and on Novo Nordisk <b>158 of
 * 206</b>. The handful of meetings that really were somebody selling something were buried
 * under them, which made "who last saw them" answer with a standup and made the meeting
 * count on the account page meaningless.
 *
 * <h2>The rule, and why it ignores contract status</h2>
 * The mailbox owner was delivering at a client on date D when a {@code contract_consultants}
 * row exists for them, joined to a {@code contracts} row for that client, whose
 * {@code [activefrom, activeto]} window contains D. <b>The contract's status is deliberately
 * not consulted.</b> A contract that was BUDGET, SIGNED, CLOSED or anything else still has
 * a consultant assignment with a date window on it, and that window is the record of when
 * somebody actually sat at the client. Filtering on status instead would let every meeting
 * from a contract whose paperwork state is unusual back in, which is precisely the set of
 * contracts nobody maintains carefully.
 *
 * <h2>Open windows</h2>
 * A null {@code activefrom} is open at the start ({@link LocalDate#MIN}) and a null
 * {@code activeto} is still running ({@link LocalDate#MAX}). Both are common: assignment
 * rows are frequently created with only one end filled in, and treating a null end as
 * "ended" would let a currently-placed consultant's whole standup calendar through.
 *
 * <h2>Pure by construction</h2>
 * No {@code EntityManager}, no Panache, no Graph. {@link CalendarFilterService} reads the
 * rows once per run and hands them here; everything after that is a map lookup, so the
 * fast-tier test can hold the whole rule without booting Quarkus or a database.
 */
final class DeliveryContractIndex {

    /**
     * One consultant assignment as the database holds it.
     *
     * @param clientUuid  {@code contracts.clientuuid}
     * @param userUuid    {@code contract_consultants.useruuid}
     * @param activeFrom  {@code contract_consultants.activefrom}; null = open at the start
     * @param activeTo    {@code contract_consultants.activeto}; null = still running
     */
    record DeliveryContractRow(String clientUuid, String userUuid, LocalDate activeFrom, LocalDate activeTo) { }

    /** A resolved window, with the nulls already turned into MIN/MAX. */
    private record Window(LocalDate from, LocalDate to) {
        boolean contains(LocalDate date) {
            return !date.isBefore(from) && !date.isAfter(to);
        }
    }

    /**
     * Keyed by {@code clientUuid + '|' + userUuid}. A consultant can be on several
     * assignments at the same client — extensions are new rows, not edits — so the value
     * is a list and any one of the windows matching is enough.
     */
    private final Map<String, List<Window>> windows;

    private DeliveryContractIndex(Map<String, List<Window>> windows) {
        this.windows = windows;
    }

    static DeliveryContractIndex of(Collection<DeliveryContractRow> rows) {
        Map<String, List<Window>> windows = new LinkedHashMap<>();
        if (rows != null) {
            for (DeliveryContractRow row : rows) {
                if (row == null || row.clientUuid() == null || row.userUuid() == null) {
                    continue;
                }
                LocalDate from = row.activeFrom() == null ? LocalDate.MIN : row.activeFrom();
                LocalDate to = row.activeTo() == null ? LocalDate.MAX : row.activeTo();
                if (to.isBefore(from)) {
                    // A row whose end precedes its start describes no time at all. Keeping
                    // it would be harmless but claiming it covers anything would not, and
                    // silently swapping the ends would invent an assignment nobody made.
                    continue;
                }
                windows.computeIfAbsent(key(row.clientUuid(), row.userUuid()), k -> new ArrayList<>())
                        .add(new Window(from, to));
            }
        }
        return new DeliveryContractIndex(windows);
    }

    /** No assignments at all — nothing is delivery, so nothing is filtered. */
    static DeliveryContractIndex empty() {
        return new DeliveryContractIndex(Map.of());
    }

    /**
     * Was this person on a contract with this client on this date?
     *
     * <p>Answering true means the meeting is delivery and must not be written. The date is
     * the meeting's own date, not today — a meeting from eighteen months ago is judged
     * against the assignment that was running eighteen months ago, which is the whole
     * reason the windows are kept rather than a flat "is placed there now" flag.
     */
    boolean isDelivering(String clientUuid, String userUuid, LocalDate date) {
        if (clientUuid == null || userUuid == null || date == null) {
            return false;
        }
        List<Window> candidates = windows.get(key(clientUuid, userUuid));
        if (candidates == null) {
            return false;
        }
        for (Window window : candidates) {
            if (window.contains(date)) {
                return true;
            }
        }
        return false;
    }

    /** How many (client, person) pairs the index holds. Logged once per run. */
    int size() {
        return windows.size();
    }

    private static String key(String clientUuid, String userUuid) {
        return clientUuid + "|" + userUuid;
    }
}
