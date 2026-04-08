package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.ConsultantAbsenceDayDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamAbsenceOverviewDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCareerDistributionDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamSalaryBandDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamSalaryBandDTO.MemberSalaryPosition;
import dk.trustworks.intranet.aggregates.finance.dto.TeamSickLeaveTrackingDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamSickLeaveTrackingDTO.MonthlySickDays;
import dk.trustworks.intranet.aggregates.finance.dto.TeamSickLeaveTrackingDTO.SickPeriod;
import dk.trustworks.intranet.aggregates.finance.dto.TeamTenureDistributionDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamTimeToFirstContractDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamTimeToFirstContractDTO.MemberTimeToContract;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Service for the People and Sick Leave tab of the Team Lead Dashboard.
 * Provides career distribution, tenure distribution, absence overview,
 * time-to-first-contract, sick leave tracking, and salary band positioning.
 *
 * <p>This is a read-model service (CQRS query side). No write operations.
 * Delegates team member resolution and access validation to {@link TeamDashboardService}.
 */
@JBossLog
@ApplicationScoped
public class TeamPeopleService {

    @Inject
    EntityManager em;

    @Inject
    TeamDashboardService teamDashboardService;

    // -----------------------------------------------------------------------
    // 1. Career Distribution
    // -----------------------------------------------------------------------

    /**
     * Returns the career level distribution for the team, grouped by career track and level.
     * Uses each member's current (most recent) career level assignment.
     */
    public List<TeamCareerDistributionDTO> getCareerDistribution(String teamId) {
        Set<String> memberUuids = teamDashboardService.getAllTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT ucl.career_track, ucl.career_level, ucl.useruuid
                FROM user_career_level ucl
                WHERE ucl.useruuid IN (:memberUuids)
                  AND ucl.active_from = (
                      SELECT MAX(ucl2.active_from)
                      FROM user_career_level ucl2
                      WHERE ucl2.useruuid = ucl.useruuid
                        AND ucl2.active_from <= CURDATE()
                  )
                ORDER BY ucl.career_track, ucl.career_level
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .getResultList();

        // Group by track+level
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        Map<String, String> trackByKey = new LinkedHashMap<>();

        for (Tuple row : rows) {
            String track = row.get("career_track") != null ? (String) row.get("career_track") : "NONE";
            String level = (String) row.get("career_level");
            String key = track + "|" + level;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add((String) row.get("useruuid"));
            trackByKey.putIfAbsent(key, track);
        }

        return grouped.entrySet().stream()
                .map(entry -> {
                    String[] parts = entry.getKey().split("\\|");
                    return new TeamCareerDistributionDTO(
                            parts[0],
                            parts[1],
                            entry.getValue().size(),
                            entry.getValue()
                    );
                })
                .toList();
    }

    // -----------------------------------------------------------------------
    // 2. Tenure Distribution
    // -----------------------------------------------------------------------

    /**
     * Returns tenure distribution for team members, bucketed into:
     * {@code <1y}, {@code 1-2y}, {@code 2-3y}, {@code 3-5y}, {@code 5y+}.
     *
     * <p>Tenure is computed from the earliest non-TERMINATED, non-PREBOARDING status date.
     */
    public List<TeamTenureDistributionDTO> getTenureDistribution(String teamId) {
        Set<String> memberUuids = teamDashboardService.getAllTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT us.useruuid, MIN(us.statusdate) AS hire_date
                FROM userstatus us
                WHERE us.useruuid IN (:memberUuids)
                  AND us.status NOT IN ('TERMINATED', 'PREBOARDING')
                GROUP BY us.useruuid
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .getResultList();

        LocalDate now = LocalDate.now();

        // Initialize buckets in order
        Map<String, List<String>> buckets = new LinkedHashMap<>();
        buckets.put("<1y", new ArrayList<>());
        buckets.put("1-2y", new ArrayList<>());
        buckets.put("2-3y", new ArrayList<>());
        buckets.put("3-5y", new ArrayList<>());
        buckets.put("5y+", new ArrayList<>());

        for (Tuple row : rows) {
            LocalDate hireDate = toLocalDate(row.get("hire_date"));
            if (hireDate == null) continue;
            String userId = (String) row.get("useruuid");
            long years = ChronoUnit.YEARS.between(hireDate, now);

            String bucket;
            if (years < 1) bucket = "<1y";
            else if (years < 2) bucket = "1-2y";
            else if (years < 3) bucket = "2-3y";
            else if (years < 5) bucket = "3-5y";
            else bucket = "5y+";

            buckets.get(bucket).add(userId);
        }

        int sortOrder = 0;
        List<TeamTenureDistributionDTO> result = new ArrayList<>();
        for (var entry : buckets.entrySet()) {
            result.add(new TeamTenureDistributionDTO(
                    entry.getKey(),
                    sortOrder++,
                    entry.getValue().size(),
                    entry.getValue()
            ));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // 3. Absence Overview (trailing 6 months)
    // -----------------------------------------------------------------------

    /**
     * Returns monthly absence breakdown for the team over the trailing 6 months.
     * Categories: vacation, sick, maternity, other leave.
     *
     * <p>Data sourced from {@code fact_user_day} columns:
     * vacation_hours, sick_hours, maternity_leave_hours, non_payd_leave_hours + paid_leave_hours.
     */
    public List<TeamAbsenceOverviewDTO> getAbsenceOverview(String teamId) {
        Set<String> memberUuids = teamDashboardService.getAllTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        LocalDate now = LocalDate.now();
        YearMonth currentMonth = YearMonth.from(now);
        YearMonth startMonth = currentMonth.minusMonths(5);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT fud.year, fud.month,
                       SUM(COALESCE(fud.vacation_hours, 0))         AS vacation_hours,
                       SUM(COALESCE(fud.sick_hours, 0))             AS sick_hours,
                       SUM(COALESCE(fud.maternity_leave_hours, 0))  AS maternity_hours,
                       SUM(COALESCE(fud.non_payd_leave_hours, 0))
                         + SUM(COALESCE(fud.paid_leave_hours, 0))   AS other_leave_hours
                FROM fact_user_day fud
                WHERE fud.useruuid IN (:memberUuids)
                  AND ((fud.year = :startYear AND fud.month >= :startMonth)
                       OR (fud.year = :endYear AND fud.month <= :endMonth)
                       OR (fud.year > :startYear AND fud.year < :endYear))
                GROUP BY fud.year, fud.month
                ORDER BY fud.year, fud.month
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("startYear", startMonth.getYear())
                .setParameter("startMonth", startMonth.getMonthValue())
                .setParameter("endYear", currentMonth.getYear())
                .setParameter("endMonth", currentMonth.getMonthValue())
                .getResultList();

        List<TeamAbsenceOverviewDTO> result = new ArrayList<>();
        for (Tuple row : rows) {
            int year = ((Number) row.get("year")).intValue();
            int month = ((Number) row.get("month")).intValue();
            YearMonth ym = YearMonth.of(year, month);
            String monthLabel = ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + year;
            String monthKey = String.format("%d-%02d", year, month);

            result.add(new TeamAbsenceOverviewDTO(
                    monthLabel,
                    monthKey,
                    year,
                    month,
                    toDouble(row.get("vacation_hours")),
                    toDouble(row.get("sick_hours")),
                    toDouble(row.get("maternity_hours")),
                    toDouble(row.get("other_leave_hours"))
            ));
        }

        return result;
    }

    /**
     * Returns daily absence records for a single consultant over a 15-month window
     * (12 months back from today to 3 months forward). Only days with at least one
     * non-zero absence column are returned.
     *
     * <p>Used by the KPC tab's Leave Timeline.
     */
    public List<ConsultantAbsenceDayDTO> getConsultantAbsenceOverview(String teamId, String consultantUuid) {
        // Validate the member belongs to the team (any type: CONSULTANT, STAFF, etc.)
        Set<String> memberUuids = teamDashboardService.getAllTeamMemberUuids(teamId, LocalDate.now());
        if (!memberUuids.contains(consultantUuid)) {
            return List.of();
        }

        LocalDate now = LocalDate.now();
        LocalDate startDate = now.minusMonths(12);
        LocalDate endDate = now.plusMonths(3);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT fud.document_date,
                       COALESCE(fud.vacation_hours, 0)         AS vacation_hours,
                       COALESCE(fud.sick_hours, 0)             AS sick_hours,
                       COALESCE(fud.maternity_leave_hours, 0)  AS maternity_hours,
                       COALESCE(fud.paid_leave_hours, 0)       AS paid_leave_hours,
                       COALESCE(fud.non_payd_leave_hours, 0)   AS non_paid_leave_hours
                FROM fact_user_day fud
                WHERE fud.useruuid = :consultantUuid
                  AND fud.document_date >= :startDate
                  AND fud.document_date <= :endDate
                  AND (fud.vacation_hours > 0 OR fud.sick_hours > 0
                       OR fud.maternity_leave_hours > 0 OR fud.paid_leave_hours > 0
                       OR fud.non_payd_leave_hours > 0)
                ORDER BY fud.document_date
                """, Tuple.class)
                .setParameter("consultantUuid", consultantUuid)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .getResultList();

        List<ConsultantAbsenceDayDTO> result = new ArrayList<>();
        for (Tuple row : rows) {
            result.add(new ConsultantAbsenceDayDTO(
                    toLocalDate(row.get("document_date")),
                    toDouble(row.get("vacation_hours")),
                    toDouble(row.get("sick_hours")),
                    toDouble(row.get("maternity_hours")),
                    toDouble(row.get("paid_leave_hours")),
                    toDouble(row.get("non_paid_leave_hours"))
            ));
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // 4. Time to First Contract
    // -----------------------------------------------------------------------

    /**
     * Returns time-to-first-contract data for team members, plus a company-wide average.
     *
     * <p>For each team member, computes the days between their earliest active status date
     * and their first contract_consultants entry. The company average is computed across
     * all consultants who have at least one contract.
     */
    public TeamTimeToFirstContractDTO getTimeToFirstContract(String teamId) {
        Set<String> memberUuids = teamDashboardService.getTeamMemberUuids(teamId, LocalDate.now());

        // Team members
        List<MemberTimeToContract> teamMembers;
        if (memberUuids.isEmpty()) {
            teamMembers = List.of();
        } else {
            @SuppressWarnings("unchecked")
            List<Tuple> rows = em.createNativeQuery("""
                    SELECT sub.user_id, sub.firstname, sub.lastname, sub.hire_date,
                           MIN(cc.activefrom) AS first_contract_date
                    FROM (
                        SELECT u.uuid AS user_id, u.firstname, u.lastname,
                               MIN(us.statusdate) AS hire_date
                        FROM user u
                        JOIN userstatus us ON us.useruuid = u.uuid
                             AND us.status NOT IN ('TERMINATED', 'PREBOARDING')
                             AND us.type = 'CONSULTANT'
                        WHERE u.uuid IN (:memberUuids)
                        GROUP BY u.uuid, u.firstname, u.lastname
                    ) sub
                    LEFT JOIN contract_consultants cc ON cc.useruuid = sub.user_id
                        AND cc.activefrom >= sub.hire_date
                    GROUP BY sub.user_id, sub.firstname, sub.lastname, sub.hire_date
                    ORDER BY sub.hire_date ASC
                    """, Tuple.class)
                    .setParameter("memberUuids", memberUuids)
                    .getResultList();

            teamMembers = rows.stream()
                    .map(row -> {
                        LocalDate hireDate = toLocalDate(row.get("hire_date"));
                        LocalDate firstContractDate = toLocalDate(row.get("first_contract_date"));
                        Integer daysToContract = null;
                        if (hireDate != null && firstContractDate != null) {
                            daysToContract = (int) ChronoUnit.DAYS.between(hireDate, firstContractDate);
                        }
                        return new MemberTimeToContract(
                                (String) row.get("user_id"),
                                (String) row.get("firstname"),
                                (String) row.get("lastname"),
                                hireDate,
                                firstContractDate,
                                daysToContract
                        );
                    })
                    .toList();
        }

        // Company-wide average
        Double companyAverageDays = computeCompanyAverageTimeToFirstContract();

        return new TeamTimeToFirstContractDTO(teamMembers, companyAverageDays);
    }

    private Double computeCompanyAverageTimeToFirstContract() {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT AVG(DATEDIFF(first_contract, hire_date)) AS avg_days
                FROM (
                    SELECT sub.user_id,
                           sub.hire_date,
                           MIN(cc.activefrom) AS first_contract
                    FROM (
                        SELECT u.uuid AS user_id,
                               MIN(us.statusdate) AS hire_date
                        FROM user u
                        JOIN userstatus us ON us.useruuid = u.uuid
                             AND us.status NOT IN ('TERMINATED', 'PREBOARDING')
                             AND us.type = 'CONSULTANT'
                        GROUP BY u.uuid
                    ) sub
                    JOIN contract_consultants cc ON cc.useruuid = sub.user_id
                        AND cc.activefrom >= sub.hire_date
                    GROUP BY sub.user_id, sub.hire_date
                ) agg
                """, Tuple.class)
                .getResultList();

        if (rows.isEmpty() || rows.get(0).get("avg_days") == null) {
            return null;
        }
        return toDouble(rows.get(0).get("avg_days"));
    }

    // -----------------------------------------------------------------------
    // 5. Sick Leave Tracking
    // -----------------------------------------------------------------------

    /**
     * Returns sick leave tracking data for each team member.
     * Reads from {@code fact_user_day.sick_hours} to provide:
     * <ul>
     *   <li>Current rolling 365-day total</li>
     *   <li>Threshold status (OK/WARNING/CRITICAL)</li>
     *   <li>Monthly trend (trailing 12 months)</li>
     *   <li>Consecutive sick periods</li>
     * </ul>
     */
    public List<TeamSickLeaveTrackingDTO> getSickLeaveTracking(String teamId) {
        Set<String> memberUuids = teamDashboardService.getAllTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        LocalDate now = LocalDate.now();
        LocalDate lookbackStart = now.minusDays(365);

        // Get user names
        @SuppressWarnings("unchecked")
        List<Tuple> userRows = em.createNativeQuery("""
                SELECT u.uuid, u.firstname, u.lastname
                FROM user u
                WHERE u.uuid IN (:memberUuids)
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .getResultList();

        Map<String, String[]> namesByUuid = new LinkedHashMap<>();
        for (Tuple row : userRows) {
            namesByUuid.put(
                    (String) row.get("uuid"),
                    new String[]{(String) row.get("firstname"), (String) row.get("lastname")}
            );
        }

        // Get daily sick hours for period detection (rolling totals and trends
        // are computed from detected periods to include bridged weekends/holidays
        // per Danish Funktionærloven 120-day rule).
        // We also select sick_hours raw to determine full-day (7.4h) bridging eligibility.
        @SuppressWarnings("unchecked")
        List<Tuple> dailyRows = em.createNativeQuery("""
                SELECT fud.useruuid, fud.document_date,
                       fud.sick_hours / 7.4 AS effective_sick_day,
                       fud.sick_hours
                FROM fact_user_day fud
                WHERE fud.useruuid IN (:memberUuids)
                  AND fud.document_date >= :lookbackStart
                  AND fud.document_date <= :today
                  AND fud.sick_hours > 0
                ORDER BY fud.useruuid, fud.document_date
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("lookbackStart", lookbackStart)
                .setParameter("today", now)
                .getResultList();

        // Group daily rows by user, then compute sick day counts with bridging rules.
        // Each working day with sick_hours > 0 counts as 1 sick day.
        // Non-working days (weekends) between two sick entries count as sick days
        // ONLY IF both adjacent working sick days have full sick_hours (7.4h).
        var sickDayResult = computeSickDays(dailyRows);
        Map<String, List<SickPeriod>> periodsByUser = sickDayResult.periods();
        Map<String, Double> rollingTotalByUser = sickDayResult.rollingTotals();
        Map<String, List<MonthlySickDays>> trendByUser = sickDayResult.monthlyTrends();

        // Assemble results
        List<TeamSickLeaveTrackingDTO> result = new ArrayList<>();
        for (String userId : memberUuids) {
            String[] names = namesByUuid.getOrDefault(userId, new String[]{"", ""});
            double rollingTotal = rollingTotalByUser.getOrDefault(userId, 0.0);
            String thresholdStatus = computeThresholdStatus(rollingTotal);
            List<MonthlySickDays> trend = trendByUser.getOrDefault(userId, List.of());
            List<SickPeriod> periods = periodsByUser.getOrDefault(userId, List.of());

            result.add(new TeamSickLeaveTrackingDTO(
                    userId,
                    names[0],
                    names[1],
                    rollingTotal,
                    thresholdStatus,
                    trend,
                    periods
            ));
        }

        // Sort by rolling total descending (most critical first)
        result.sort(Comparator.comparingDouble(TeamSickLeaveTrackingDTO::currentRollingTotal).reversed());

        return result;
    }

    private String computeThresholdStatus(double rollingTotal) {
        if (rollingTotal >= 100) return "CRITICAL";
        if (rollingTotal >= 80) return "WARNING";
        return "OK";
    }

    /**
     * Result of the sick day computation: periods, rolling totals, and monthly trends.
     */
    private record SickDayResult(
            Map<String, List<SickPeriod>> periods,
            Map<String, Double> rollingTotals,
            Map<String, List<MonthlySickDays>> monthlyTrends
    ) {}

    /**
     * Computes sick day counts per the Danish Funktionærloven 120-day rule with
     * corrected weekend bridging.
     *
     * <p>Rules:
     * <ol>
     *   <li>Each working day with {@code sick_hours > 0} counts as 1 sick day.</li>
     *   <li>Non-working days (weekends/holidays) between two consecutive sick working days
     *       count as sick days ONLY IF both adjacent working days have full sick hours (7.4h).</li>
     *   <li>Partial sick days ({@code < 7.4h}) do NOT bridge weekends.</li>
     * </ol>
     *
     * <p>The rolling total = working sick days + bridged non-working days.
     * Monthly trend distributes working sick days to their actual month,
     * and bridged gap days to the month they fall in.
     */
    private SickDayResult computeSickDays(List<Tuple> dailyRows) {
        Map<String, List<SickPeriod>> periodsByUser = new LinkedHashMap<>();
        Map<String, Double> rollingTotalByUser = new LinkedHashMap<>();
        Map<String, List<MonthlySickDays>> trendByUser = new LinkedHashMap<>();

        // Group by user
        Map<String, List<Tuple>> byUser = new LinkedHashMap<>();
        for (Tuple row : dailyRows) {
            String userId = (String) row.get("useruuid");
            byUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(row);
        }

        for (var entry : byUser.entrySet()) {
            List<Tuple> days = entry.getValue();
            List<SickPeriod> periods = new ArrayList<>();
            Map<String, Double> monthlyDays = new LinkedHashMap<>();
            double totalSickDays = 0;

            // Track current period
            LocalDate periodStart = null;
            LocalDate periodEnd = null;
            double periodDays = 0;

            for (int i = 0; i < days.size(); i++) {
                Tuple day = days.get(i);
                LocalDate date = toLocalDate(day.get("document_date"));
                double sickHours = toDouble(day.get("sick_hours"));
                if (date == null) continue;

                if (periodStart == null) {
                    // Start new period
                    periodStart = date;
                    periodEnd = date;
                    periodDays = 1;
                    String monthKey = String.format("%d-%02d", date.getYear(), date.getMonthValue());
                    monthlyDays.merge(monthKey, 1.0, Double::sum);
                    totalSickDays++;
                    continue;
                }

                long gapDays = ChronoUnit.DAYS.between(periodEnd, date) - 1;

                if (gapDays <= 0) {
                    // Consecutive working day (no gap) — extend period
                    periodEnd = date;
                    periodDays++;
                    String monthKey = String.format("%d-%02d", date.getYear(), date.getMonthValue());
                    monthlyDays.merge(monthKey, 1.0, Double::sum);
                    totalSickDays++;
                } else if (gapDays <= 3) {
                    // There are gap days (weekends/holidays) between the previous sick day
                    // and this one. Bridge ONLY if both boundary days are full (7.4h).
                    Tuple prevDay = days.get(i - 1);
                    double prevSickHours = toDouble(prevDay.get("sick_hours"));
                    boolean bothFull = Math.abs(prevSickHours - 7.4) < 0.01
                            && Math.abs(sickHours - 7.4) < 0.01;

                    if (bothFull) {
                        // Bridge: count the gap days as sick days
                        for (long g = 1; g <= gapDays; g++) {
                            LocalDate gapDate = periodEnd.plusDays(g);
                            String monthKey = String.format("%d-%02d", gapDate.getYear(), gapDate.getMonthValue());
                            monthlyDays.merge(monthKey, 1.0, Double::sum);
                        }
                        totalSickDays += gapDays;
                        periodDays += gapDays;
                    } else {
                        // No bridging — close previous period and start a new one
                        periods.add(new SickPeriod(periodStart, periodEnd, periodDays));
                        periodStart = date;
                        periodDays = 0;
                    }

                    // Either way, this working day itself counts
                    periodEnd = date;
                    periodDays++;
                    String monthKey = String.format("%d-%02d", date.getYear(), date.getMonthValue());
                    monthlyDays.merge(monthKey, 1.0, Double::sum);
                    totalSickDays++;
                } else {
                    // Gap too large — close current period and start new one
                    periods.add(new SickPeriod(periodStart, periodEnd, periodDays));
                    periodStart = date;
                    periodEnd = date;
                    periodDays = 1;
                    String monthKey = String.format("%d-%02d", date.getYear(), date.getMonthValue());
                    monthlyDays.merge(monthKey, 1.0, Double::sum);
                    totalSickDays++;
                }
            }

            // Close last period
            if (periodStart != null) {
                periods.add(new SickPeriod(periodStart, periodEnd, periodDays));
            }

            periodsByUser.put(entry.getKey(), periods);
            rollingTotalByUser.put(entry.getKey(), totalSickDays);

            List<MonthlySickDays> trend = monthlyDays.entrySet().stream()
                    .map(e -> new MonthlySickDays(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparing(MonthlySickDays::month))
                    .toList();
            trendByUser.put(entry.getKey(), trend);
        }

        return new SickDayResult(periodsByUser, rollingTotalByUser, trendByUser);
    }

    // -----------------------------------------------------------------------
    // 6. Salary Band Positioning
    // -----------------------------------------------------------------------

    /**
     * Returns salary band positioning per career level, showing company-wide percentile bands
     * and where each team member falls.
     *
     * <p>Only includes career levels that have at least one team member.
     * Percentile bands are computed across all active consultants company-wide at the same level.
     */
    public List<TeamSalaryBandDTO> getSalaryBandPositioning(String teamId) {
        Set<String> memberUuids = teamDashboardService.getTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        // Step 1: Get team members' current career levels and current salaries
        @SuppressWarnings("unchecked")
        List<Tuple> teamRows = em.createNativeQuery("""
                SELECT u.uuid AS user_id, u.firstname, u.lastname,
                       ucl.career_level, ucl.career_track,
                       s.salary
                FROM user u
                JOIN user_career_level ucl ON ucl.useruuid = u.uuid
                     AND ucl.active_from = (
                         SELECT MAX(ucl2.active_from) FROM user_career_level ucl2
                         WHERE ucl2.useruuid = u.uuid AND ucl2.active_from <= CURDATE()
                     )
                JOIN salary s ON s.useruuid = u.uuid
                     AND s.activefrom = (
                         SELECT MAX(s2.activefrom) FROM salary s2
                         WHERE s2.useruuid = u.uuid AND s2.activefrom <= CURDATE()
                     )
                WHERE u.uuid IN (:memberUuids)
                ORDER BY ucl.career_level, s.salary
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .getResultList();

        if (teamRows.isEmpty()) {
            return List.of();
        }

        // Collect the career levels we need percentiles for
        Set<String> careerLevels = teamRows.stream()
                .map(r -> (String) r.get("career_level"))
                .collect(Collectors.toSet());

        // Step 2: Get company-wide salary distribution per career level
        @SuppressWarnings("unchecked")
        List<Tuple> companyRows = em.createNativeQuery("""
                SELECT ucl.career_level, ucl.career_track, s.salary
                FROM user u
                JOIN userstatus us ON us.useruuid = u.uuid
                     AND us.statusdate = (
                         SELECT MAX(us2.statusdate) FROM userstatus us2
                         WHERE us2.useruuid = u.uuid AND us2.statusdate <= CURDATE()
                     )
                     AND us.status NOT IN ('TERMINATED', 'PREBOARDING')
                     AND us.type = 'CONSULTANT'
                JOIN user_career_level ucl ON ucl.useruuid = u.uuid
                     AND ucl.active_from = (
                         SELECT MAX(ucl2.active_from) FROM user_career_level ucl2
                         WHERE ucl2.useruuid = u.uuid AND ucl2.active_from <= CURDATE()
                     )
                JOIN salary s ON s.useruuid = u.uuid
                     AND s.activefrom = (
                         SELECT MAX(s2.activefrom) FROM salary s2
                         WHERE s2.useruuid = u.uuid AND s2.activefrom <= CURDATE()
                     )
                WHERE ucl.career_level IN (:careerLevels)
                ORDER BY ucl.career_level, s.salary
                """, Tuple.class)
                .setParameter("careerLevels", careerLevels)
                .getResultList();

        // Group company-wide salaries by career level
        Map<String, List<Integer>> companySalariesByLevel = new TreeMap<>();
        Map<String, String> trackByLevel = new LinkedHashMap<>();
        for (Tuple row : companyRows) {
            String level = (String) row.get("career_level");
            int salary = ((Number) row.get("salary")).intValue();
            companySalariesByLevel.computeIfAbsent(level, k -> new ArrayList<>()).add(salary);
            trackByLevel.putIfAbsent(level, row.get("career_track") != null ? (String) row.get("career_track") : "NONE");
        }

        // Group team members by career level
        Map<String, List<Tuple>> teamByLevel = new LinkedHashMap<>();
        for (Tuple row : teamRows) {
            teamByLevel.computeIfAbsent((String) row.get("career_level"), k -> new ArrayList<>()).add(row);
        }

        // Step 3: Build DTOs
        List<TeamSalaryBandDTO> result = new ArrayList<>();
        for (String level : careerLevels) {
            List<Integer> companySalaries = companySalariesByLevel.getOrDefault(level, List.of());
            if (companySalaries.isEmpty()) continue;

            List<Integer> sorted = companySalaries.stream().sorted().toList();
            int count = sorted.size();
            int p25 = percentile(sorted, 25);
            int p50 = percentile(sorted, 50);
            int p75 = percentile(sorted, 75);
            int minSalary = sorted.get(0);
            int maxSalary = sorted.get(count - 1);

            List<MemberSalaryPosition> members = new ArrayList<>();
            for (Tuple teamRow : teamByLevel.getOrDefault(level, List.of())) {
                int memberSalary = ((Number) teamRow.get("salary")).intValue();
                double percentileRank = computePercentileRank(sorted, memberSalary);
                members.add(new MemberSalaryPosition(
                        (String) teamRow.get("user_id"),
                        (String) teamRow.get("firstname"),
                        (String) teamRow.get("lastname"),
                        memberSalary,
                        percentileRank
                ));
            }

            result.add(new TeamSalaryBandDTO(
                    level,
                    trackByLevel.getOrDefault(level, "NONE"),
                    count,
                    p25, p50, p75,
                    minSalary, maxSalary,
                    members
            ));
        }

        return result;
    }

    /**
     * Computes the k-th percentile from a sorted list of integers using linear interpolation.
     */
    private int percentile(List<Integer> sorted, int k) {
        if (sorted.isEmpty()) return 0;
        if (sorted.size() == 1) return sorted.get(0);
        double index = (k / 100.0) * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = Math.min(lower + 1, sorted.size() - 1);
        double fraction = index - lower;
        return (int) Math.round(sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower)));
    }

    /**
     * Computes the percentile rank of a value within a sorted distribution (0-100).
     */
    private double computePercentileRank(List<Integer> sorted, int value) {
        if (sorted.isEmpty()) return 0;
        if (sorted.size() == 1) return 50.0;

        long below = sorted.stream().filter(s -> s < value).count();
        long equal = sorted.stream().filter(s -> s == value).count();

        // Percentile rank = (below + 0.5 * equal) / total * 100
        return Math.round(((below + 0.5 * equal) / sorted.size()) * 1000.0) / 10.0;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private LocalDate toLocalDate(Object obj) {
        if (obj == null) return null;
        if (obj instanceof LocalDate ld) return ld;
        if (obj instanceof java.sql.Date sd) return sd.toLocalDate();
        if (obj instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        return LocalDate.parse(obj.toString());
    }

    private double toDouble(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Number n) return n.doubleValue();
        return Double.parseDouble(obj.toString());
    }
}
