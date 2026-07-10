package dk.trustworks.intranet.aggregates.executive.people;

import dk.trustworks.intranet.userservice.model.enums.StatusType;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Canonical SQL fragments and bindings for Executive people analytics.
 *
 * <p>The status and temporal dimension CTEs rank all rows at or before the
 * snapshot before company, type, status, or other business filters are
 * applied. This prevents a historical row from a selected company from being
 * treated as current after a later transfer.</p>
 */
public final class PeoplePopulationSqlSupport {

    public static final List<String> EMPLOYED_STATUSES = List.of(
            StatusType.ACTIVE.name(),
            StatusType.PAID_LEAVE.name(),
            StatusType.MATERNITY_LEAVE.name(),
            StatusType.NON_PAY_LEAVE.name());

    public static final List<String> LEAVE_STATUSES = List.of(
            StatusType.PAID_LEAVE.name(),
            StatusType.MATERNITY_LEAVE.name(),
            StatusType.NON_PAY_LEAVE.name());

    public static final List<String> SENIOR_LEADERSHIP_TRACKS = List.of(
            "LEADERSHIP", "PARTNER", "C_LEVEL");

    private PeoplePopulationSqlSupport() {
    }

    /** CTEs for a single snapshot; the final CTE is named {@code filtered_population}. */
    public static String snapshotPopulationCtes(PeopleFilterParams filters, String snapshotParameter) {
        String snapshot = ":" + snapshotParameter;
        return "latest_status_candidates AS (" +
                " SELECT us.*," + transferDestinationCase("us", "paired_status") + " transfer_destination," +
                sameCompanyRehireCase("us", "same_company_exit") + " same_company_rehire" +
                " FROM userstatus us WHERE us.statusdate <= " + snapshot +
                "), latest_status_ranked AS (" +
                " SELECT lsc.*, ROW_NUMBER() OVER (PARTITION BY lsc.useruuid" +
                " ORDER BY lsc.statusdate DESC,lsc.transfer_destination DESC,lsc.same_company_rehire DESC," +
                "lsc.created_at DESC,lsc.uuid DESC) rn" +
                " FROM latest_status_candidates lsc" +
                "), latest_status AS (" +
                " SELECT * FROM latest_status_ranked WHERE rn = 1" +
                "), latest_career_ranked AS (" +
                " SELECT ucl.*, ROW_NUMBER() OVER (PARTITION BY ucl.useruuid" +
                " ORDER BY ucl.active_from DESC,ucl.created_at DESC,ucl.uuid DESC) rn" +
                " FROM user_career_level ucl WHERE ucl.active_from <= " + snapshot +
                "), latest_career AS (" +
                " SELECT * FROM latest_career_ranked WHERE rn = 1" +
                "), filtered_population AS (" +
                " SELECT ls.useruuid, ls.status, ls.`type`, ls.allocation, ls.companyuuid, ls.statusdate," +
                " u.firstname, u.lastname, u.gender, u.birthday, u.practice," +
                " lc.career_track, lc.career_level" +
                " FROM latest_status ls" +
                " JOIN `user` u ON u.uuid = ls.useruuid" +
                " LEFT JOIN latest_career lc ON lc.useruuid = ls.useruuid" +
                " WHERE " + snapshotFilters(filters, "ls", "u", "lc", snapshot) +
                ")";
    }

    /** Month-end spine with the live month capped at asOfDate. */
    public static String monthSpineCte() {
        return "month_spine AS (" +
                " SELECT CASE" +
                "   WHEN dd.`year` = YEAR(:asOfDate) AND dd.`month` = MONTH(:asOfDate) THEN :asOfDate" +
                "   ELSE LAST_DAY(MIN(dd.date_key)) END AS snapshot_date" +
                " FROM dim_date dd" +
                " WHERE dd.date_key BETWEEN :periodStart AND :asOfDate" +
                " GROUP BY dd.`year`, dd.`month`" +
                ")";
    }

    /**
     * Month spine plus status and career rows resolved independently at each
     * snapshot. Ranking precedes all business filters.
     */
    public static String monthlyPopulationCtes() {
        return monthSpineCte() +
                ", monthly_status_candidates AS (" +
                " SELECT ms.snapshot_date, us.*," +
                transferDestinationCase("us", "paired_status") + " transfer_destination," +
                sameCompanyRehireCase("us", "same_company_exit") + " same_company_rehire" +
                " FROM month_spine ms" +
                " JOIN userstatus us ON us.statusdate <= ms.snapshot_date" +
                "), monthly_status_ranked AS (" +
                " SELECT msc.*," +
                " ROW_NUMBER() OVER (PARTITION BY msc.snapshot_date,msc.useruuid" +
                " ORDER BY msc.statusdate DESC,msc.transfer_destination DESC,msc.same_company_rehire DESC," +
                "msc.created_at DESC,msc.uuid DESC) rn" +
                " FROM monthly_status_candidates msc" +
                "), monthly_status AS (" +
                " SELECT * FROM monthly_status_ranked WHERE rn = 1" +
                "), monthly_career_ranked AS (" +
                " SELECT ms.snapshot_date, ucl.*," +
                " ROW_NUMBER() OVER (PARTITION BY ms.snapshot_date, ucl.useruuid" +
                " ORDER BY ucl.active_from DESC,ucl.created_at DESC,ucl.uuid DESC) rn" +
                " FROM month_spine ms" +
                " JOIN user_career_level ucl ON ucl.active_from <= ms.snapshot_date" +
                "), monthly_career AS (" +
                " SELECT * FROM monthly_career_ranked WHERE rn = 1" +
                ")";
    }

    /**
     * Resolves one business-effective status row per user and date. Danløn
     * represents a group-company transfer as TERMINATED in the source plus an
     * internal-employed row in the destination on the same date. The
     * destination wins that pair regardless of random UUID ordering. Other
     * same-date edits use audit creation time, then UUID, so a genuine later
     * same-company termination remains an exit.
     */
    public static String canonicalStatusDayCtes(String prefix, String cutoffExpression) {
        if (prefix == null || !prefix.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("prefix must be a safe SQL identifier");
        }
        String candidates = prefix + "_status_day_candidates";
        String ranked = prefix + "_status_day_ranked";
        String day = prefix + "_status_day";
        return candidates + " AS (" +
                " SELECT us.*," + transferDestinationCase("us", "paired_status") + " transfer_destination," +
                sameCompanyRehireCase("us", "same_company_exit") + " same_company_rehire" +
                " FROM userstatus us WHERE us.statusdate<=" + cutoffExpression +
                "), " + ranked + " AS (" +
                " SELECT sdc.*,ROW_NUMBER() OVER (PARTITION BY sdc.useruuid,sdc.statusdate" +
                " ORDER BY sdc.transfer_destination DESC,sdc.same_company_rehire DESC," +
                "sdc.created_at DESC,sdc.uuid DESC) rn" +
                " FROM " + candidates + " sdc" +
                "), " + day + " AS (SELECT * FROM " + ranked + " WHERE rn=1)";
    }

    public static String internalEmployedPredicate(String alias) {
        return alias + ".`type` IN ('CONSULTANT','STAFF','STUDENT')" +
                " AND " + alias + ".status IN ('ACTIVE','PAID_LEAVE','MATERNITY_LEAVE','NON_PAY_LEAVE')";
    }

    public static String transferDestinationCase(String alias, String pairAlias) {
        return " CASE WHEN " + internalEmployedPredicate(alias) +
                " AND EXISTS (SELECT 1 FROM userstatus " + pairAlias +
                " WHERE " + pairAlias + ".useruuid=" + alias + ".useruuid" +
                " AND " + pairAlias + ".statusdate=" + alias + ".statusdate" +
                " AND " + pairAlias + ".status='TERMINATED'" +
                " AND NOT (" + pairAlias + ".companyuuid <=> " + alias + ".companyuuid))" +
                " THEN 1 ELSE 0 END";
    }

    public static String sameCompanyRehireCase(String alias, String pairAlias) {
        return " CASE WHEN " + internalEmployedPredicate(alias) +
                " AND EXISTS (SELECT 1 FROM userstatus " + pairAlias +
                " WHERE " + pairAlias + ".useruuid=" + alias + ".useruuid" +
                " AND " + pairAlias + ".statusdate=" + alias + ".statusdate" +
                " AND " + pairAlias + ".status='TERMINATED'" +
                " AND " + pairAlias + ".companyuuid <=> " + alias + ".companyuuid" +
                " AND " + pairAlias + ".created_at<=" + alias + ".created_at)" +
                " THEN 1 ELSE 0 END";
    }

    public static String externalSnapshotFilters(
            PeopleFilterParams filters, String statusAlias, String userAlias) {
        StringBuilder sql = new StringBuilder();
        sql.append(statusAlias).append(".`type` = 'EXTERNAL'")
                .append(" AND ").append(statusAlias).append(".status = 'ACTIVE'");
        if (filters.companyId() != null) {
            sql.append(" AND ").append(statusAlias).append(".companyuuid = :companyId");
        }
        if (!filters.practices().isEmpty()) {
            sql.append(" AND ").append(userAlias).append(".practice IN (:practices)");
        }
        return sql.toString();
    }

    /** Filters a latest-status row at a supplied snapshot expression. */
    public static String snapshotFilters(
            PeopleFilterParams filters,
            String statusAlias,
            String userAlias,
            String careerAlias,
            String snapshotExpression) {
        StringBuilder sql = new StringBuilder("1=1");
        sql.append(" AND ").append(statusAlias).append(".`type` IN (:employeeTypes)");
        switch (filters.population()) {
            case EMPLOYED -> sql.append(" AND ").append(statusAlias).append(".status IN (:populationStatuses)");
            case ACTIVE -> sql.append(" AND ").append(statusAlias).append(".status = 'ACTIVE'");
            case ON_LEAVE -> sql.append(" AND ").append(statusAlias).append(".status IN (:leaveStatuses)");
        }
        if (filters.companyId() != null) {
            sql.append(" AND ").append(statusAlias).append(".companyuuid = :companyId");
        }
        if (!filters.practices().isEmpty()) {
            sql.append(" AND ").append(userAlias).append(".practice IN (:practices)");
        }
        if (!filters.careerTracks().isEmpty()) {
            sql.append(" AND ").append(careerAlias).append(".career_track IN (:careerTracks)");
        }
        if (!filters.careerLevels().isEmpty()) {
            sql.append(" AND ").append(careerAlias).append(".career_level IN (:careerLevels)");
        }
        switch (filters.managementScope()) {
            case ALL -> {
            }
            case PEOPLE_LEADERS -> sql.append(" AND EXISTS (")
                    .append("SELECT 1 FROM teamroles tr WHERE tr.useruuid = ")
                    .append(statusAlias).append(".useruuid")
                    .append(" AND tr.membertype = 'LEADER'")
                    .append(" AND tr.startdate <= ").append(snapshotExpression)
                    .append(" AND (tr.enddate IS NULL OR tr.enddate >= ").append(snapshotExpression).append(")")
                    .append(")");
            case SENIOR_LEADERSHIP -> sql.append(" AND ")
                    .append(statusAlias).append(".`type` = 'CONSULTANT'")
                    .append(" AND ").append(careerAlias).append(".career_track IN (:seniorLeadershipTracks)");
        }
        return sql.toString();
    }

    public static Map<String, Object> snapshotBindings(
            PeopleFilterParams filters, String snapshotParameter, LocalDate snapshotDate) {
        Map<String, Object> bindings = filterBindings(filters);
        bindings.put(snapshotParameter, Date.valueOf(snapshotDate));
        return bindings;
    }

    public static Map<String, Object> trendBindings(PeopleFilterParams filters) {
        Map<String, Object> bindings = filterBindings(filters);
        bindings.put("asOfDate", Date.valueOf(filters.asOfDate()));
        bindings.put("periodStart", Date.valueOf(periodStart(filters)));
        return bindings;
    }

    public static Map<String, Object> filterBindings(PeopleFilterParams filters) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("employeeTypes", filters.employeeTypes().stream().map(Enum::name).collect(Collectors.toSet()));
        if (filters.population() == PeoplePopulationScope.EMPLOYED) {
            bindings.put("populationStatuses", EMPLOYED_STATUSES);
        } else if (filters.population() == PeoplePopulationScope.ON_LEAVE) {
            bindings.put("leaveStatuses", LEAVE_STATUSES);
        }
        if (filters.companyId() != null) bindings.put("companyId", filters.companyId());
        if (!filters.practices().isEmpty()) {
            bindings.put("practices", filters.practices().stream().map(Enum::name).collect(Collectors.toSet()));
        }
        if (!filters.careerTracks().isEmpty()) {
            bindings.put("careerTracks", filters.careerTracks().stream().map(Enum::name).collect(Collectors.toSet()));
        }
        if (!filters.careerLevels().isEmpty()) {
            bindings.put("careerLevels", filters.careerLevels().stream().map(Enum::name).collect(Collectors.toSet()));
        }
        if (filters.managementScope() == PeopleManagementScope.SENIOR_LEADERSHIP) {
            bindings.put("seniorLeadershipTracks", SENIOR_LEADERSHIP_TRACKS);
        }
        return bindings;
    }

    public static LocalDate periodStart(PeopleFilterParams filters) {
        return filters.asOfDate().withDayOfMonth(1).minusMonths(filters.months() - 1L);
    }
}
