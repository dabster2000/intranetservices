package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.TeamCalendarDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCalendarDTO.CalendarMember;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCalendarDTO.CalendarSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Calendar tab (JK Team 2.0 WP6 §4.6.5), both kinds of team. Contracts and internal
 * assignments are spans already; ferie and orlov are contiguous runs of leave hours in
 * fact_user_day, aggregated here — never in the browser — and bridged across non-working
 * days so a two-week holiday is one bar, not two.
 */
@ApplicationScoped
public class TeamCalendarService {

    public static final int DEFAULT_MONTHS = 12;
    public static final int MAX_MONTHS = 24;
    /** A full week for the allocation percentage on a contract bar. */
    static final double FULL_WEEK_HOURS = 37.0;

    @Inject
    EntityManager em;

    public TeamCalendarDTO getCalendar(Set<String> memberUuids, YearMonth fromMonth, int months) {
        int span = Math.min(Math.max(months, 1), MAX_MONTHS);
        LocalDate from = fromMonth.atDay(1);
        LocalDate to = fromMonth.plusMonths(span - 1L).atEndOfMonth();
        if (memberUuids == null || memberUuids.isEmpty()) {
            return new TeamCalendarDTO(from, to, List.of());
        }

        Map<String, List<CalendarSpan>> spansByUser = new LinkedHashMap<>();
        for (String u : memberUuids) spansByUser.put(u, new ArrayList<>());

        collectContracts(memberUuids, from, to, spansByUser);
        collectInternalAssignments(memberUuids, from, to, spansByUser);
        collectLeave(memberUuids, from, to, spansByUser);

        @SuppressWarnings("unchecked")
        List<Tuple> people = em.createNativeQuery("""
                SELECT u.uuid, u.firstname, u.lastname,
                       (SELECT us.type FROM userstatus us
                         WHERE us.useruuid = u.uuid AND us.statusdate <= CURDATE()
                         ORDER BY us.statusdate DESC LIMIT 1) AS consultant_type
                FROM user u
                WHERE u.uuid IN (:memberUuids)
                ORDER BY u.lastname, u.firstname
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .getResultList();

        List<CalendarMember> members = new ArrayList<>();
        for (Tuple p : people) {
            String uid = (String) p.get("uuid");
            List<CalendarSpan> spans = spansByUser.getOrDefault(uid, List.of());
            spans.sort((a, b) -> a.from().compareTo(b.from()));
            members.add(new CalendarMember(uid, (String) p.get("firstname"), (String) p.get("lastname"),
                    (String) p.get("consultant_type"), spans));
        }
        return new TeamCalendarDTO(from, to, members);
    }

    private void collectContracts(Set<String> memberUuids, LocalDate from, LocalDate to,
                                  Map<String, List<CalendarSpan>> spansByUser) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT cc.useruuid, cc.activefrom, cc.activeto, cc.rate, cc.hours, cc.pricing_model_code,
                       c.name AS contract_name, cl.name AS client_name,
                       CONCAT(am.firstname, ' ', am.lastname) AS am_name
                FROM contract_consultants cc
                JOIN contracts c ON c.uuid = cc.contractuuid
                LEFT JOIN client cl ON cl.uuid = c.clientuuid
                LEFT JOIN user am ON am.uuid = c.leaduuid
                WHERE cc.useruuid IN (:memberUuids)
                  AND cc.activefrom <= :to AND cc.activeto >= :from
                  AND c.status IN ('SIGNED', 'TIME')
                ORDER BY cc.activefrom
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        for (Tuple r : rows) {
            double hours = num(r.get("hours"));
            Integer allocation = hours > 0 ? (int) Math.round(hours / FULL_WEEK_HOURS * 100.0) : null;
            spansByUser.computeIfAbsent((String) r.get("useruuid"), k -> new ArrayList<>()).add(
                    CalendarSpan.contract(
                            clip(toLocalDate(r.get("activefrom")), from, to),
                            clip(toLocalDate(r.get("activeto")), from, to),
                            (String) r.get("client_name"),
                            (String) r.get("contract_name"),
                            (String) r.get("am_name"),
                            (String) r.get("pricing_model_code"),
                            num(r.get("rate")),
                            hours,
                            allocation));
        }
    }

    private void collectInternalAssignments(Set<String> memberUuids, LocalDate from, LocalDate to,
                                            Map<String, List<CalendarSpan>> spansByUser) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT ia.useruuid, ia.title, ia.active_from, ia.active_to, ia.hours_per_week, ia.strategic,
                       CONCAT(sp.firstname, ' ', sp.lastname) AS sponsor_name
                FROM internal_assignment ia
                LEFT JOIN user sp ON sp.uuid = ia.sponsor_useruuid
                WHERE ia.useruuid IN (:memberUuids)
                  AND ia.status = 'APPROVED'
                  AND ia.active_from <= :to AND ia.active_to >= :from
                ORDER BY ia.active_from
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        for (Tuple r : rows) {
            Object strategic = r.get("strategic");
            boolean isStrategic = strategic instanceof Boolean b ? b : strategic instanceof Number n && n.intValue() != 0;
            spansByUser.computeIfAbsent((String) r.get("useruuid"), k -> new ArrayList<>()).add(
                    CalendarSpan.internal(
                            clip(toLocalDate(r.get("active_from")), from, to),
                            clip(toLocalDate(r.get("active_to")), from, to),
                            (String) r.get("title"),
                            (String) r.get("sponsor_name"),
                            num(r.get("hours_per_week")),
                            isStrategic));
        }
    }

    private void collectLeave(Set<String> memberUuids, LocalDate from, LocalDate to,
                              Map<String, List<CalendarSpan>> spansByUser) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT fud.useruuid, fud.document_date,
                       COALESCE(fud.vacation_hours, 0)        AS vacation,
                       COALESCE(fud.maternity_leave_hours, 0) AS maternity,
                       COALESCE(fud.non_payd_leave_hours, 0)  AS non_paid,
                       COALESCE(fud.paid_leave_hours, 0)      AS paid,
                       COALESCE(fud.gross_available_hours, 0) AS gross
                FROM fact_user_day fud
                WHERE fud.useruuid IN (:memberUuids)
                  AND fud.document_date >= :from AND fud.document_date <= :to
                  AND (fud.vacation_hours > 0 OR fud.maternity_leave_hours > 0
                       OR fud.non_payd_leave_hours > 0 OR fud.paid_leave_hours > 0
                       OR COALESCE(fud.gross_available_hours, 0) = 0)
                ORDER BY fud.useruuid, fud.document_date
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        Map<String, Set<LocalDate>> vacationDays = new LinkedHashMap<>();
        Map<String, Map<String, Set<LocalDate>>> leaveDays = new LinkedHashMap<>();
        Map<String, Set<LocalDate>> bridgeDays = new LinkedHashMap<>();
        for (Tuple r : rows) {
            String uid = (String) r.get("useruuid");
            LocalDate day = toLocalDate(r.get("document_date"));
            if (num(r.get("vacation")) > 0) vacationDays.computeIfAbsent(uid, k -> new TreeSet<>()).add(day);
            if (num(r.get("maternity")) > 0) leaveDays.computeIfAbsent(uid, k -> new LinkedHashMap<>())
                    .computeIfAbsent("MATERNITY", k -> new TreeSet<>()).add(day);
            if (num(r.get("non_paid")) > 0) leaveDays.computeIfAbsent(uid, k -> new LinkedHashMap<>())
                    .computeIfAbsent("NON_PAID", k -> new TreeSet<>()).add(day);
            if (num(r.get("paid")) > 0) leaveDays.computeIfAbsent(uid, k -> new LinkedHashMap<>())
                    .computeIfAbsent("PAID", k -> new TreeSet<>()).add(day);
            if (num(r.get("gross")) == 0) bridgeDays.computeIfAbsent(uid, k -> new HashSet<>()).add(day);
        }
        for (var e : vacationDays.entrySet()) {
            for (LocalDate[] run : toSpans(e.getValue(), bridgeDays.getOrDefault(e.getKey(), Set.of()))) {
                spansByUser.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(CalendarSpan.vacation(run[0], run[1]));
            }
        }
        for (var e : leaveDays.entrySet()) {
            for (var byType : e.getValue().entrySet()) {
                for (LocalDate[] run : toSpans(byType.getValue(), bridgeDays.getOrDefault(e.getKey(), Set.of()))) {
                    spansByUser.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                            .add(CalendarSpan.leave(run[0], run[1], byType.getKey()));
                }
            }
        }
    }

    /**
     * Contiguous runs of flagged days. Two flagged days belong to the same run when every day
     * between them is a weekend day or is in {@code bridgeDays} (a day with no gross
     * availability — holidays and weekends as the fact table knows them). Returns
     * {@code [first, last]} pairs, oldest first.
     */
    public static List<LocalDate[]> toSpans(Collection<LocalDate> flaggedDays, Set<LocalDate> bridgeDays) {
        List<LocalDate[]> runs = new ArrayList<>();
        if (flaggedDays == null || flaggedDays.isEmpty()) return runs;
        TreeSet<LocalDate> sorted = new TreeSet<>(flaggedDays);
        LocalDate start = null;
        LocalDate end = null;
        for (LocalDate day : sorted) {
            if (start == null) {
                start = day;
                end = day;
                continue;
            }
            if (bridged(end, day, bridgeDays)) {
                end = day;
            } else {
                runs.add(new LocalDate[]{start, end});
                start = day;
                end = day;
            }
        }
        runs.add(new LocalDate[]{start, end});
        return runs;
    }

    private static boolean bridged(LocalDate end, LocalDate next, Set<LocalDate> bridgeDays) {
        LocalDate cursor = end.plusDays(1);
        while (cursor.isBefore(next)) {
            DayOfWeek dow = cursor.getDayOfWeek();
            boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            if (!weekend && !bridgeDays.contains(cursor)) return false;
            cursor = cursor.plusDays(1);
        }
        return true;
    }

    private static LocalDate clip(LocalDate value, LocalDate from, LocalDate to) {
        if (value == null) return from;
        if (value.isBefore(from)) return from;
        if (value.isAfter(to)) return to;
        return value;
    }

    private static double num(Object value) {
        return value == null ? 0.0 : ((Number) value).doubleValue();
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime().toLocalDate();
        return LocalDate.parse(value.toString().substring(0, 10));
    }
}
