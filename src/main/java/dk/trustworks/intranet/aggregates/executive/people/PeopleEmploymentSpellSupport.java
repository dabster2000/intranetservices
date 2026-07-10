package dk.trustworks.intranet.aggregates.executive.people;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical continuous internal-employment spell semantics. */
public final class PeopleEmploymentSpellSupport {

    private PeopleEmploymentSpellSupport() {
    }

    /**
     * SQL CTEs that first resolve one canonical status row per user/day. A
     * cross-company TERMINATED + employed Danløn pair selects the destination
     * row, while a same-company exit follows created_at ordering.
     */
    public static String sqlCtes() {
        return PeoplePopulationSqlSupport.canonicalStatusDayCtes("spell", ":asOfDate") +
                ", status_day_flags AS (" +
                " SELECT ssd.useruuid,ssd.statusdate,ssd.same_company_rehire," +
                " CASE WHEN " + PeoplePopulationSqlSupport.internalEmployedPredicate("ssd") +
                " THEN 1 ELSE 0 END employed" +
                " FROM spell_status_day ssd" +
                "), status_flags AS (" +
                " SELECT sdf.*,LAG(sdf.employed) OVER (PARTITION BY sdf.useruuid ORDER BY sdf.statusdate) previous_employed" +
                " FROM status_day_flags sdf" +
                "), status_numbered AS (" +
                " SELECT sf.*,SUM(CASE WHEN employed=1" +
                " AND (COALESCE(previous_employed,0)=0 OR same_company_rehire=1) THEN 1 ELSE 0 END)" +
                " OVER (PARTITION BY useruuid ORDER BY statusdate) spell_number" +
                " FROM status_flags sf" +
                "), spell_bounds AS (" +
                " SELECT useruuid,spell_number," +
                " MIN(CASE WHEN employed=1 THEN statusdate END) start_date," +
                " MIN(CASE WHEN employed=0 THEN statusdate END) raw_end_date" +
                " FROM status_numbered WHERE spell_number>0 GROUP BY useruuid,spell_number" +
                "), spell_bounds_with_next AS (" +
                " SELECT sb.*,LEAD(sb.start_date) OVER (PARTITION BY sb.useruuid ORDER BY sb.spell_number) next_start_date" +
                " FROM spell_bounds sb" +
                "), employment_spells AS (" +
                " SELECT useruuid,spell_number,start_date," +
                " CASE WHEN next_start_date IS NULL THEN raw_end_date" +
                " WHEN raw_end_date IS NULL OR next_start_date<raw_end_date THEN next_start_date" +
                " ELSE raw_end_date END end_date" +
                " FROM spell_bounds_with_next" +
                ")";
    }

    /** Small deterministic mirror used by fixtures for transfer/exit semantics. */
    public static List<Spell> calculate(List<StatusPoint> points) {
        Map<LocalDate, List<StatusPoint>> pointsByDay = new LinkedHashMap<>();
        points.stream().sorted(Comparator.comparing(StatusPoint::date))
                .forEach(point -> pointsByDay.computeIfAbsent(point.date(), ignored -> new ArrayList<>()).add(point));
        List<Spell> spells = new ArrayList<>();
        LocalDate start = null;
        for (Map.Entry<LocalDate, List<StatusPoint>> day : pointsByDay.entrySet()) {
            StatusPoint canonical = canonicalPoint(day.getValue());
            boolean employed = canonical.kind() == StatusKind.EMPLOYED;
            boolean sameCompanyRehire = isSameCompanyRehireDestination(canonical, day.getValue());
            if (employed && sameCompanyRehire && start != null) {
                spells.add(new Spell(start, day.getKey()));
                start = day.getKey();
                continue;
            }
            if (employed && start == null) start = day.getKey();
            if (!employed && start != null) {
                spells.add(new Spell(start, day.getKey()));
                start = null;
            }
        }
        if (start != null) spells.add(new Spell(start, null));
        return List.copyOf(spells);
    }

    static StatusPoint canonicalPoint(List<StatusPoint> sameDay) {
        return sameDay.stream().max(Comparator
                .comparing((StatusPoint point) -> isTransferDestination(point, sameDay))
                .thenComparing(point -> isSameCompanyRehireDestination(point, sameDay))
                .thenComparing(StatusPoint::createdAt)
                .thenComparing(StatusPoint::uuid))
                .orElseThrow();
    }

    private static boolean isTransferDestination(StatusPoint point, List<StatusPoint> sameDay) {
        return point.kind() == StatusKind.EMPLOYED && sameDay.stream().anyMatch(other ->
                other.kind() == StatusKind.TERMINATED && !java.util.Objects.equals(other.companyId(), point.companyId()));
    }

    private static boolean isSameCompanyRehireDestination(StatusPoint point, List<StatusPoint> sameDay) {
        return point.kind() == StatusKind.EMPLOYED && sameDay.stream().anyMatch(other ->
                other.kind() == StatusKind.TERMINATED
                        && java.util.Objects.equals(other.companyId(), point.companyId())
                        && !other.createdAt().isAfter(point.createdAt()));
    }

    public enum StatusKind {
        EMPLOYED,
        TERMINATED,
        OTHER
    }

    public record StatusPoint(
            LocalDate date,
            StatusKind kind,
            String companyId,
            LocalDateTime createdAt,
            String uuid) {

        public StatusPoint {
            java.util.Objects.requireNonNull(date, "date");
            java.util.Objects.requireNonNull(kind, "kind");
            java.util.Objects.requireNonNull(createdAt, "createdAt");
            java.util.Objects.requireNonNull(uuid, "uuid");
        }
    }

    public record Spell(LocalDate startDate, LocalDate endDate) {
    }
}
