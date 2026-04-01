package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.AllTeamsUtilizationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamBenchConsultantDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamBillingRateDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamBudgetFulfillmentDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamClientConcentrationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamContractTimelineDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamContractTimelineDTO.ConsultantContracts;
import dk.trustworks.intranet.aggregates.finance.dto.TeamContractTimelineDTO.ContractEntry;
import dk.trustworks.intranet.aggregates.finance.dto.TeamContractTimelineDTO.LeadEntry;
import dk.trustworks.intranet.aggregates.finance.dto.TeamContributionMarginDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamExpiringContractDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamForwardAllocationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamForwardAllocationDTO.MemberAllocation;
import dk.trustworks.intranet.aggregates.finance.dto.TeamOverviewDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamOverviewDTO.RosterContract;
import dk.trustworks.intranet.aggregates.finance.dto.TeamOverviewDTO.TeamAttentionItemDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamOverviewDTO.TeamRosterMemberDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamRevenueCostTrendDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamRevenuePerMemberDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamUtilizationHeatmapDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamUtilizationHeatmapDTO.MemberUtilizationRow;
import dk.trustworks.intranet.aggregates.finance.dto.TeamUtilizationTrendDTO;
import dk.trustworks.intranet.aggregates.finance.dto.UnprofitableConsultantDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for the Team Lead Dashboard.
 * Provides team member resolution, access validation, and all read-model queries
 * for the finance/staffing endpoints.
 *
 * <p>This is a read-model service (CQRS query side) aggregating data from multiple
 * existing fact tables filtered by team membership. No write operations.
 *
 * <p><strong>Utilization rule:</strong> Always SUM hours first, then divide.
 * Never average pre-computed percentages.
 */
@JBossLog
@ApplicationScoped
public class TeamDashboardService {

    @Inject
    EntityManager em;

    @Inject
    ConsultantInsightsService consultantInsightsService;

    @Inject
    DistributionAwareOpexProvider opexProvider;

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /**
     * Returns UUIDs of active MEMBER-type team members for the given team at the given date.
     */
    public Set<String> getTeamMemberUuids(String teamId, LocalDate date) {
        @SuppressWarnings("unchecked")
        List<String> uuids = em.createNativeQuery("""
                SELECT tr.useruuid
                FROM teamroles tr
                JOIN userstatus us ON us.useruuid = tr.useruuid
                     AND us.statusdate = (
                         SELECT MAX(us2.statusdate) FROM userstatus us2
                         WHERE us2.useruuid = tr.useruuid AND us2.statusdate <= :date
                     )
                     AND us.status NOT IN ('TERMINATED', 'PREBOARDING')
                     AND us.type = 'CONSULTANT'
                WHERE tr.teamuuid = :teamId
                  AND tr.membertype = 'MEMBER'
                  AND tr.startdate <= :date
                  AND (tr.enddate IS NULL OR tr.enddate > :date)
                """)
                .setParameter("teamId", teamId)
                .setParameter("date", date)
                .getResultList();
        return Set.copyOf(uuids);
    }

    /**
     * Validates that the requesting user is a LEADER or SPONSOR of the specified team.
     * Throws 403 if not.
     */
    public void validateTeamAccess(String teamId, String requestedByUuid) {
        Long count = (Long) em.createNativeQuery("""
                SELECT COUNT(*) FROM teamroles tr
                WHERE tr.teamuuid = :teamId
                  AND tr.useruuid = :userId
                  AND tr.membertype IN ('LEADER', 'SPONSOR')
                  AND tr.startdate <= CURDATE()
                  AND (tr.enddate IS NULL OR tr.enddate > CURDATE())
                """)
                .setParameter("teamId", teamId)
                .setParameter("userId", requestedByUuid)
                .getSingleResult();
        if (count == 0) {
            throw new WebApplicationException(
                    "User is not a leader or sponsor of the requested team",
                    Response.Status.FORBIDDEN);
        }
    }

    /**
     * Computes fiscal year start/end dates. FY runs July 1 to June 30.
     * E.g., fiscalYear=2025 means 2025-07-01 to 2026-06-30.
     */
    public FiscalYearBounds getFiscalYearBounds(int fiscalYear) {
        LocalDate start = LocalDate.of(fiscalYear, Month.JULY, 1);
        LocalDate end = LocalDate.of(fiscalYear + 1, Month.JUNE, 30);
        return new FiscalYearBounds(fiscalYear, start, end);
    }

    /**
     * Returns the fiscal year that contains the given date.
     */
    public int currentFiscalYear() {
        LocalDate now = LocalDate.now();
        return now.getMonthValue() >= 7 ? now.getYear() : now.getYear() - 1;
    }

    public record FiscalYearBounds(int fiscalYear, LocalDate start, LocalDate end) {}

    // -----------------------------------------------------------------------
    // 1. Overview
    // -----------------------------------------------------------------------

    public TeamOverviewDTO getOverview(String teamId) {
        LocalDate now = LocalDate.now();
        Set<String> memberUuids = getTeamMemberUuids(teamId, now);
        if (memberUuids.isEmpty()) {
            return emptyOverview(teamId);
        }

        var fy = getFiscalYearBounds(currentFiscalYear());
        // Cap end date to today so we don't include future months
        LocalDate effectiveEnd = now.isBefore(fy.end()) ? now : fy.end();

        // Team name
        String teamName = getTeamName(teamId);

        // KPI: utilization
        var utilRow = querySingleRow("""
                SELECT COALESCE(SUM(fum.billable_hours), 0) AS billable,
                       COALESCE(SUM(fum.net_available_hours), 0) AS net_available
                FROM fact_user_utilization_mat fum
                WHERE fum.user_id IN (:memberUuids)
                  AND fum.month_key >= :fromKey AND fum.month_key <= :toKey
                """,
                Map.of("memberUuids", memberUuids,
                        "fromKey", toMonthKey(fy.start()),
                        "toKey", toMonthKey(effectiveEnd)));

        double billable = numVal(utilRow, "billable");
        double netAvail = numVal(utilRow, "net_available");
        Double utilPct = netAvail > 0 ? (billable / netAvail) * 100.0 : null;

        // KPI: revenue
        var revRow = querySingleRow("""
                SELECT COALESCE(SUM(fud.registered_amount), 0) AS revenue
                FROM fact_user_day fud
                WHERE fud.useruuid IN (:memberUuids)
                  AND fud.document_date >= :fromDate AND fud.document_date <= :toDate
                  AND fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type = 'ACTIVE'
                """,
                Map.of("memberUuids", memberUuids,
                        "fromDate", fy.start(),
                        "toDate", effectiveEnd));
        double revenue = numVal(revRow, "revenue");

        // KPI: salary cost
        var salaryRow = querySingleRow("""
                SELECT COALESCE(SUM(fsmt.salary_sum), 0) AS salary_cost
                FROM fact_salary_monthly_teamroles fsmt
                WHERE fsmt.teamuuid = :teamId
                  AND fsmt.month_key >= :fromKey AND fsmt.month_key <= :toKey
                """,
                Map.of("teamId", teamId,
                        "fromKey", toMonthKey(fy.start()),
                        "toKey", toMonthKey(effectiveEnd)));
        double salaryCost = numVal(salaryRow, "salary_cost");

        // Roster
        List<TeamRosterMemberDTO> roster = buildRoster(memberUuids, fy, effectiveEnd);

        // Bench / attention items
        List<TeamBenchConsultantDTO> bench = getBenchConsultants(teamId, memberUuids);
        Double avgBenchDays = bench.isEmpty() ? null :
                bench.stream()
                        .mapToInt(TeamBenchConsultantDTO::daysSinceContract)
                        .filter(d -> d >= 0)
                        .average()
                        .orElse(0.0);

        List<TeamAttentionItemDTO> attentionItems = buildAttentionItems(bench);

        return new TeamOverviewDTO(
                teamId, teamName, memberUuids.size(),
                utilPct, revenue, salaryCost, avgBenchDays,
                roster, attentionItems);
    }

    // -----------------------------------------------------------------------
    // 2. Utilization Trend
    // -----------------------------------------------------------------------

    public List<TeamUtilizationTrendDTO> getUtilizationTrend(String teamId, int fiscalYear) {
        var fy = getFiscalYearBounds(fiscalYear);
        // Extend 3 months before FY start for 15-month view
        LocalDate extendedStart = fy.start().minusMonths(3);
        LocalDate effectiveEnd = capToToday(fy.end());
        Set<String> memberUuids = getTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        String fromKey = toMonthKey(extendedStart);
        String toKey = toMonthKey(effectiveEnd);

        // Team data — temporal join: only include consultant's data for months they were on this team
        @SuppressWarnings("unchecked")
        List<Tuple> teamRows = em.createNativeQuery("""
                SELECT fum.month_key,
                       COALESCE(SUM(fum.billable_hours), 0) AS billable,
                       COALESCE(SUM(fum.net_available_hours), 0) AS net_available,
                       COALESCE(SUM(fum.gross_available_hours), 0) AS gross_available
                FROM fact_user_utilization_mat fum
                JOIN teamroles tr ON tr.useruuid = fum.user_id
                    AND tr.teamuuid = :teamId
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= CONCAT(fum.year, '-', LPAD(fum.month_number, 2, '0'), '-01')
                    AND (tr.enddate > CONCAT(fum.year, '-', LPAD(fum.month_number, 2, '0'), '-01') OR tr.enddate IS NULL)
                WHERE fum.month_key >= :fromKey AND fum.month_key <= :toKey
                GROUP BY fum.month_key
                ORDER BY fum.month_key
                """, Tuple.class)
                .setParameter("teamId", teamId)
                .setParameter("fromKey", fromKey)
                .setParameter("toKey", toKey)
                .getResultList();

        // Company-wide data for same period
        @SuppressWarnings("unchecked")
        List<Tuple> companyRows = em.createNativeQuery("""
                SELECT fum.month_key,
                       COALESCE(SUM(fum.billable_hours), 0) AS billable,
                       COALESCE(SUM(fum.net_available_hours), 0) AS net_available
                FROM fact_user_utilization_mat fum
                WHERE fum.month_key >= :fromKey AND fum.month_key <= :toKey
                GROUP BY fum.month_key
                ORDER BY fum.month_key
                """, Tuple.class)
                .setParameter("fromKey", fromKey)
                .setParameter("toKey", toKey)
                .getResultList();

        // Budget hours per month from fact_budget_day for team members
        LocalDate budgetFromDate = extendedStart.withDayOfMonth(1);
        LocalDate budgetToDate = YearMonth.from(effectiveEnd).atEndOfMonth();
        @SuppressWarnings("unchecked")
        List<Tuple> budgetRows = em.createNativeQuery("""
                SELECT CONCAT(LPAD(YEAR(bd.document_date), 4, '0'),
                              LPAD(MONTH(bd.document_date), 2, '0')) AS month_key,
                       SUM(bd.budgetHours) AS budget_hours
                FROM fact_budget_day bd
                WHERE bd.useruuid IN (:memberUuids)
                  AND bd.document_date >= :fromDate AND bd.document_date <= :toDate
                GROUP BY YEAR(bd.document_date), MONTH(bd.document_date)
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("fromDate", budgetFromDate)
                .setParameter("toDate", budgetToDate)
                .getResultList();

        Map<String, Double> budgetMap = new LinkedHashMap<>();
        for (Tuple row : budgetRows) {
            String mk = (String) row.get("month_key");
            double budgetHrs = numVal(row, "budget_hours");
            budgetMap.put(mk, budgetHrs);
        }

        Map<String, Double> companyUtilMap = new LinkedHashMap<>();
        for (Tuple row : companyRows) {
            String mk = (String) row.get("month_key");
            double b = numVal(row, "billable");
            double n = numVal(row, "net_available");
            companyUtilMap.put(mk, n > 0 ? (b / n) * 100.0 : null);
        }

        List<TeamUtilizationTrendDTO> result = new ArrayList<>();
        for (Tuple row : teamRows) {
            String mk = (String) row.get("month_key");
            double b = numVal(row, "billable");
            double n = numVal(row, "net_available");
            double g = numVal(row, "gross_available");
            Double grossPct = g > 0 ? (b / g) * 100.0 : null;
            Double teamPct = n > 0 ? (b / n) * 100.0 : null;

            Double budgetHrs = budgetMap.get(mk);
            Double budgetPct = null;
            Double fulfillmentPct = null;
            if (budgetHrs != null && budgetHrs > 0) {
                budgetPct = n > 0 ? (budgetHrs / n) * 100.0 : null;
                fulfillmentPct = (b / budgetHrs) * 100.0;
            }

            int year = Integer.parseInt(mk.substring(0, 4));
            int month = Integer.parseInt(mk.substring(4, 6));
            String label = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + year;

            result.add(new TeamUtilizationTrendDTO(
                    mk, year, month, label,
                    b, n, grossPct, teamPct,
                    budgetPct, fulfillmentPct,
                    companyUtilMap.get(mk)));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // 3. Utilization Heatmap
    // -----------------------------------------------------------------------

    public TeamUtilizationHeatmapDTO getUtilizationHeatmap(String teamId, int fiscalYear) {
        var fy = getFiscalYearBounds(fiscalYear);
        // Show trailing 6 months up to now or FY end
        LocalDate effectiveEnd = capToToday(fy.end());
        LocalDate sixMonthsBack = effectiveEnd.minusMonths(5).withDayOfMonth(1);
        Set<String> memberUuids = getTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return new TeamUtilizationHeatmapDTO(List.of(), List.of());
        }

        // Temporal join: only include data for months the consultant was on this team
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT fum.user_id, u.firstname, u.lastname, fum.month_key,
                       COALESCE(SUM(fum.billable_hours), 0) AS billable,
                       COALESCE(SUM(fum.net_available_hours), 0) AS net_available
                FROM fact_user_utilization_mat fum
                JOIN user u ON u.uuid = fum.user_id
                JOIN teamroles tr ON tr.useruuid = fum.user_id
                    AND tr.teamuuid = :teamId
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= CONCAT(fum.year, '-', LPAD(fum.month_number, 2, '0'), '-01')
                    AND (tr.enddate > CONCAT(fum.year, '-', LPAD(fum.month_number, 2, '0'), '-01') OR tr.enddate IS NULL)
                WHERE fum.month_key >= :fromKey AND fum.month_key <= :toKey
                GROUP BY fum.user_id, u.firstname, u.lastname, fum.month_key
                ORDER BY u.lastname, u.firstname, fum.month_key
                """, Tuple.class)
                .setParameter("teamId", teamId)
                .setParameter("fromKey", toMonthKey(sixMonthsBack))
                .setParameter("toKey", toMonthKey(effectiveEnd))
                .getResultList();

        // Build month list
        List<String> months = new ArrayList<>();
        YearMonth ym = YearMonth.from(sixMonthsBack);
        YearMonth endYm = YearMonth.from(effectiveEnd);
        while (!ym.isAfter(endYm)) {
            months.add(String.format("%04d%02d", ym.getYear(), ym.getMonthValue()));
            ym = ym.plusMonths(1);
        }

        // Group by user
        Map<String, Map<String, Object>> userData = new LinkedHashMap<>();
        for (Tuple row : rows) {
            String uid = (String) row.get("user_id");
            userData.computeIfAbsent(uid, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("firstname", row.get("firstname"));
                m.put("lastname", row.get("lastname"));
                m.put("data", new LinkedHashMap<String, Double>());
                return m;
            });
            String mk = (String) row.get("month_key");
            double b = numVal(row, "billable");
            double n = numVal(row, "net_available");
            @SuppressWarnings("unchecked")
            Map<String, Double> dataMap = (Map<String, Double>) userData.get(uid).get("data");
            dataMap.put(mk, n > 0 ? (b / n) * 100.0 : null);
        }

        List<MemberUtilizationRow> members = new ArrayList<>();
        for (var entry : userData.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Double> dataMap = (Map<String, Double>) entry.getValue().get("data");
            List<Double> utilByMonth = months.stream()
                    .map(dataMap::get)
                    .collect(Collectors.toList());
            members.add(new MemberUtilizationRow(
                    entry.getKey(),
                    (String) entry.getValue().get("firstname"),
                    (String) entry.getValue().get("lastname"),
                    utilByMonth));
        }
        return new TeamUtilizationHeatmapDTO(months, members);
    }

    // -----------------------------------------------------------------------
    // 4. Budget Fulfillment
    // -----------------------------------------------------------------------

    public List<TeamBudgetFulfillmentDTO> getBudgetFulfillment(String teamId, int fiscalYear) {
        Set<String> memberUuids = getTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        // FY-to-date through previous month (completed months only)
        LocalDate fyStart = getFiscalYearBounds(fiscalYear).start();
        LocalDate prevMonthEnd = YearMonth.now().minusMonths(1).atEndOfMonth();
        // If the FY hasn't started yet, there's nothing to show
        if (fyStart.isAfter(prevMonthEnd)) {
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT u.uuid AS user_id, u.firstname, u.lastname,
                       COALESCE(budget.total_budget, 0) AS budget_hours,
                       COALESCE(actual.total_actual, 0) AS actual_hours
                FROM user u
                LEFT JOIN (
                    SELECT bd.useruuid, SUM(bd.budgetHours) AS total_budget
                    FROM fact_budget_day bd
                    WHERE bd.useruuid IN (:memberUuids)
                      AND bd.document_date >= :fyStart AND bd.document_date <= :prevMonthEnd
                    GROUP BY bd.useruuid
                ) budget ON budget.useruuid = u.uuid
                LEFT JOIN (
                    SELECT fud.useruuid, SUM(fud.registered_billable_hours) AS total_actual
                    FROM fact_user_day fud
                    WHERE fud.useruuid IN (:memberUuids)
                      AND fud.document_date >= :fyStart AND fud.document_date <= :prevMonthEnd
                      AND fud.consultant_type = 'CONSULTANT'
                    GROUP BY fud.useruuid
                ) actual ON actual.useruuid = u.uuid
                WHERE u.uuid IN (:memberUuids)
                ORDER BY u.lastname, u.firstname
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("fyStart", fyStart)
                .setParameter("prevMonthEnd", prevMonthEnd)
                .getResultList();

        List<TeamBudgetFulfillmentDTO> result = new ArrayList<>();
        for (Tuple row : rows) {
            double budget = numVal(row, "budget_hours");
            double actual = numVal(row, "actual_hours");
            double gap = budget - actual;
            Double fulfillmentPct = budget > 0 ? (actual / budget) * 100.0 : null;
            result.add(new TeamBudgetFulfillmentDTO(
                    (String) row.get("user_id"),
                    (String) row.get("firstname"),
                    (String) row.get("lastname"),
                    budget, actual, gap, fulfillmentPct));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // 5. All Teams Utilization (for ranking)
    // -----------------------------------------------------------------------

    public List<AllTeamsUtilizationDTO> getAllTeamsUtilization(String currentTeamId, int fiscalYear) {
        var fy = getFiscalYearBounds(fiscalYear);
        // Cap to last day of previous month (only include completed months)
        LocalDate prevMonthEnd = YearMonth.now().minusMonths(1).atEndOfMonth();
        LocalDate effectiveEnd = fy.end().isBefore(prevMonthEnd) ? fy.end() : prevMonthEnd;

        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT t.uuid AS team_id, t.name AS team_name,
                       COUNT(DISTINCT tr.useruuid) AS member_count,
                       COALESCE(SUM(fum.billable_hours), 0) AS billable,
                       COALESCE(SUM(fum.net_available_hours), 0) AS net_available
                FROM team t
                JOIN teamroles tr ON tr.teamuuid = t.uuid
                     AND tr.membertype = 'MEMBER'
                     AND tr.startdate <= CURDATE()
                     AND (tr.enddate IS NULL OR tr.enddate > CURDATE())
                LEFT JOIN fact_user_utilization_mat fum
                     ON fum.user_id = tr.useruuid
                     AND fum.month_key >= :fromKey AND fum.month_key <= :toKey
                GROUP BY t.uuid, t.name
                HAVING COUNT(DISTINCT tr.useruuid) > 0
                ORDER BY (COALESCE(SUM(fum.billable_hours), 0) /
                          NULLIF(COALESCE(SUM(fum.net_available_hours), 0), 0)) DESC
                """, Tuple.class)
                .setParameter("fromKey", toMonthKey(fy.start()))
                .setParameter("toKey", toMonthKey(effectiveEnd))
                .getResultList();

        List<AllTeamsUtilizationDTO> result = new ArrayList<>();
        for (Tuple row : rows) {
            String tid = (String) row.get("team_id");
            double b = numVal(row, "billable");
            double n = numVal(row, "net_available");
            Double pct = n > 0 ? (b / n) * 100.0 : null;
            result.add(new AllTeamsUtilizationDTO(
                    tid,
                    (String) row.get("team_name"),
                    ((Number) row.get("member_count")).intValue(),
                    b, n, pct,
                    tid.equals(currentTeamId)));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // 6. Contract Timeline
    // -----------------------------------------------------------------------

    public TeamContractTimelineDTO getContractTimeline(String teamId) {
        Set<String> memberUuids = getTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return new TeamContractTimelineDTO(List.of());
        }

        // Active and recent contracts
        @SuppressWarnings("unchecked")
        List<Tuple> contractRows = em.createNativeQuery("""
                SELECT cc.useruuid AS user_id, u.firstname, u.lastname,
                       cc.contractuuid, cl.name AS client_name, c.name AS contract_name,
                       cc.activefrom, cc.activeto, cc.rate, c.status
                FROM contract_consultants cc
                JOIN contracts c ON c.uuid = cc.contractuuid
                JOIN client cl ON cl.uuid = c.clientuuid
                JOIN user u ON u.uuid = cc.useruuid
                WHERE cc.useruuid IN (:memberUuids)
                  AND cc.activeto >= DATE_SUB(CURDATE(), INTERVAL 6 MONTH)
                ORDER BY u.lastname, u.firstname, cc.activefrom
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .getResultList();

        // Sales leads for team members (with extension detection)
        @SuppressWarnings("unchecked")
        List<Tuple> leadRows = em.createNativeQuery("""
                SELECT slc.useruuid AS user_id,
                       sl.uuid AS lead_uuid, cl.name AS client_name,
                       sl.description, sl.status, sl.closedate, sl.allocation, sl.rate, sl.period,
                       EXISTS (
                           SELECT 1 FROM contract_consultants cc2
                           JOIN contracts c2 ON c2.uuid = cc2.contractuuid
                           WHERE cc2.useruuid = slc.useruuid
                             AND c2.clientuuid = sl.clientuuid
                             AND cc2.activefrom <= CURDATE()
                             AND cc2.activeto > CURDATE()
                             AND c2.status IN ('SIGNED', 'TIME')
                       ) AS is_extension
                FROM sales_lead_consultant slc
                JOIN sales_lead sl ON sl.uuid = slc.leaduuid
                JOIN client cl ON cl.uuid = sl.clientuuid
                WHERE slc.useruuid IN (:memberUuids)
                  AND sl.status NOT IN ('LOST', 'ABANDONED')
                ORDER BY sl.closedate
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .getResultList();

        // Group by consultant
        Map<String, ConsultantContracts> byUser = new LinkedHashMap<>();

        for (Tuple row : contractRows) {
            String uid = (String) row.get("user_id");
            byUser.computeIfAbsent(uid, k -> new ConsultantContracts(
                    uid,
                    (String) row.get("firstname"),
                    (String) row.get("lastname"),
                    new ArrayList<>(),
                    new ArrayList<>()));
            byUser.get(uid).contracts().add(new ContractEntry(
                    (String) row.get("contractuuid"),
                    (String) row.get("client_name"),
                    (String) row.get("contract_name"),
                    toLocalDate(row.get("activefrom")),
                    toLocalDate(row.get("activeto")),
                    numVal(row, "rate"),
                    row.get("status") != null ? row.get("status").toString() : null));
        }

        for (Tuple row : leadRows) {
            String uid = (String) row.get("user_id");
            byUser.computeIfAbsent(uid, k -> {
                // Need to look up name — query user table
                var nameRow = querySingleRow(
                        "SELECT firstname, lastname FROM user WHERE uuid = :uid",
                        Map.of("uid", uid));
                return new ConsultantContracts(
                        uid,
                        nameRow != null ? (String) nameRow.get("firstname") : "",
                        nameRow != null ? (String) nameRow.get("lastname") : "",
                        new ArrayList<>(),
                        new ArrayList<>());
            });
            byUser.get(uid).leads().add(new LeadEntry(
                    (String) row.get("lead_uuid"),
                    (String) row.get("client_name"),
                    (String) row.get("description"),
                    row.get("status") != null ? row.get("status").toString() : null,
                    toLocalDate(row.get("closedate")),
                    row.get("allocation") != null ? ((Number) row.get("allocation")).intValue() : 0,
                    numVal(row, "rate"),
                    row.get("period") != null ? ((Number) row.get("period")).intValue() : 3,
                    ((Number) row.get("is_extension")).intValue() > 0));
        }

        return new TeamContractTimelineDTO(new ArrayList<>(byUser.values()));
    }

    // -----------------------------------------------------------------------
    // 7. Forward Allocation
    // -----------------------------------------------------------------------

    public TeamForwardAllocationDTO getForwardAllocation(String teamId) {
        Set<String> memberUuids = getTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return new TeamForwardAllocationDTO(List.of(), List.of());
        }

        // Next 6 months
        YearMonth startYm = YearMonth.now().plusMonths(1);
        List<String> months = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            YearMonth ym = startYm.plusMonths(i);
            months.add(String.format("%04d%02d", ym.getYear(), ym.getMonthValue()));
        }
        LocalDate from = startYm.atDay(1);
        LocalDate to = startYm.plusMonths(5).atEndOfMonth();

        // Budget hours per user per month
        @SuppressWarnings("unchecked")
        List<Tuple> budgetRows = em.createNativeQuery("""
                SELECT bd.useruuid AS user_id,
                       CONCAT(LPAD(YEAR(bd.document_date), 4, '0'),
                              LPAD(MONTH(bd.document_date), 2, '0')) AS month_key,
                       SUM(bd.budgetHours) AS budget_hours
                FROM fact_budget_day bd
                WHERE bd.useruuid IN (:memberUuids)
                  AND bd.document_date >= :fromDate AND bd.document_date <= :toDate
                GROUP BY bd.useruuid, YEAR(bd.document_date), MONTH(bd.document_date)
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("fromDate", from)
                .setParameter("toDate", to)
                .getResultList();

        // Approximate available hours per month: ~22 working days * 7.4 hours = 162.8
        // Use dim_date for accuracy
        @SuppressWarnings("unchecked")
        List<Tuple> availRows = em.createNativeQuery("""
                SELECT CONCAT(LPAD(dd.year, 4, '0'), LPAD(dd.month, 2, '0')) AS month_key,
                       COUNT(*) * 7.4 AS available_hours
                FROM dim_date dd
                WHERE dd.date_key >= :fromDate AND dd.date_key <= :toDate
                  AND dd.is_weekend = 0
                GROUP BY dd.year, dd.month
                """, Tuple.class)
                .setParameter("fromDate", from)
                .setParameter("toDate", to)
                .getResultList();

        Map<String, Double> availByMonth = new LinkedHashMap<>();
        for (Tuple row : availRows) {
            availByMonth.put((String) row.get("month_key"), numVal(row, "available_hours"));
        }

        // Group budget by user+month
        Map<String, Map<String, Double>> budgetByUserMonth = new LinkedHashMap<>();
        for (Tuple row : budgetRows) {
            String uid = (String) row.get("user_id");
            String mk = (String) row.get("month_key");
            budgetByUserMonth.computeIfAbsent(uid, k -> new LinkedHashMap<>())
                    .put(mk, numVal(row, "budget_hours"));
        }

        // Build per-member allocation
        @SuppressWarnings("unchecked")
        List<Tuple> userNames = em.createNativeQuery("""
                SELECT uuid, firstname, lastname FROM user WHERE uuid IN (:memberUuids)
                ORDER BY lastname, firstname
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .getResultList();

        List<MemberAllocation> members = new ArrayList<>();
        for (Tuple u : userNames) {
            String uid = (String) u.get("uuid");
            Map<String, Double> userBudget = budgetByUserMonth.getOrDefault(uid, Map.of());
            List<Double> budgetHrs = new ArrayList<>();
            List<Double> availHrs = new ArrayList<>();
            List<Double> allocPct = new ArrayList<>();
            for (String mk : months) {
                double b = userBudget.getOrDefault(mk, 0.0);
                double a = availByMonth.getOrDefault(mk, 162.8);
                budgetHrs.add(b);
                availHrs.add(a);
                allocPct.add(a > 0 ? (b / a) * 100.0 : 0.0);
            }
            members.add(new MemberAllocation(
                    uid,
                    (String) u.get("firstname"),
                    (String) u.get("lastname"),
                    budgetHrs, availHrs, allocPct));
        }
        return new TeamForwardAllocationDTO(months, members);
    }

    // -----------------------------------------------------------------------
    // 8. Expiring Contracts
    // -----------------------------------------------------------------------

    public List<TeamExpiringContractDTO> getExpiringContracts(String teamId, int days) {
        Set<String> memberUuids = getTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        LocalDate horizon = LocalDate.now().plusDays(days);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT cc.contractuuid, cc.useruuid AS user_id,
                       u.firstname, u.lastname,
                       cl.name AS client_name, c.name AS contract_name,
                       cc.activefrom, cc.activeto, cc.rate, cc.hours,
                       DATEDIFF(cc.activeto, CURDATE()) AS days_until_expiry,
                       EXISTS (
                           SELECT 1 FROM sales_lead sl
                           JOIN sales_lead_consultant slc ON slc.leaduuid = sl.uuid
                           WHERE slc.useruuid = cc.useruuid
                             AND sl.clientuuid = c.clientuuid
                             AND sl.status NOT IN ('LOST', 'ABANDONED')
                       ) AS has_extension_lead
                FROM contract_consultants cc
                JOIN contracts c ON c.uuid = cc.contractuuid
                JOIN client cl ON cl.uuid = c.clientuuid
                JOIN user u ON u.uuid = cc.useruuid
                WHERE cc.useruuid IN (:memberUuids)
                  AND cc.activeto >= CURDATE()
                  AND cc.activeto <= :horizon
                  AND c.status IN ('SIGNED', 'TIME')
                ORDER BY cc.activeto ASC
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("horizon", horizon)
                .getResultList();

        List<TeamExpiringContractDTO> result = new ArrayList<>();
        for (Tuple row : rows) {
            result.add(new TeamExpiringContractDTO(
                    (String) row.get("contractuuid"),
                    (String) row.get("user_id"),
                    (String) row.get("firstname"),
                    (String) row.get("lastname"),
                    (String) row.get("client_name"),
                    (String) row.get("contract_name"),
                    toLocalDate(row.get("activefrom")),
                    toLocalDate(row.get("activeto")),
                    numVal(row, "rate"),
                    numVal(row, "hours"),
                    ((Number) row.get("days_until_expiry")).intValue(),
                    ((Number) row.get("has_extension_lead")).intValue() > 0));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // 9. Bench Consultants
    // -----------------------------------------------------------------------

    public List<TeamBenchConsultantDTO> getBenchConsultants(String teamId) {
        Set<String> memberUuids = getTeamMemberUuids(teamId, LocalDate.now());
        return getBenchConsultants(teamId, memberUuids);
    }

    private List<TeamBenchConsultantDTO> getBenchConsultants(String teamId, Set<String> memberUuids) {
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT u.uuid AS user_id, u.firstname, u.lastname, u.practice,
                       MAX(cc.activeto) AS last_contract_end,
                       (SELECT COUNT(*) FROM sales_lead_consultant slc
                        JOIN sales_lead sl ON sl.uuid = slc.leaduuid
                        WHERE slc.useruuid = u.uuid
                          AND sl.status NOT IN ('LOST', 'ABANDONED', 'WON')) AS active_leads
                FROM user u
                LEFT JOIN contract_consultants cc ON cc.useruuid = u.uuid
                    AND cc.activeto IS NOT NULL
                WHERE u.uuid IN (:memberUuids)
                  AND NOT EXISTS (
                      SELECT 1 FROM contract_consultants cc2
                      JOIN contracts c2 ON c2.uuid = cc2.contractuuid
                      WHERE cc2.useruuid = u.uuid
                        AND cc2.activefrom <= CURDATE()
                        AND cc2.activeto > CURDATE()
                        AND c2.status IN ('SIGNED', 'TIME')
                  )
                GROUP BY u.uuid, u.firstname, u.lastname, u.practice
                ORDER BY last_contract_end ASC
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .getResultList();

        LocalDate today = LocalDate.now();
        List<TeamBenchConsultantDTO> result = new ArrayList<>();
        for (Tuple row : rows) {
            LocalDate lastEnd = toLocalDate(row.get("last_contract_end"));
            int daysSince = lastEnd != null ? (int) ChronoUnit.DAYS.between(lastEnd, today) : -1;
            result.add(new TeamBenchConsultantDTO(
                    (String) row.get("user_id"),
                    (String) row.get("firstname"),
                    (String) row.get("lastname"),
                    (String) row.get("practice"),
                    lastEnd,
                    daysSince,
                    ((Number) row.get("active_leads")).intValue()));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // 10. Revenue vs Cost Trend
    // -----------------------------------------------------------------------

    public List<TeamRevenueCostTrendDTO> getRevenueCostTrend(String teamId, int fiscalYear) {
        var fy = getFiscalYearBounds(fiscalYear);
        LocalDate effectiveEnd = capToToday(fy.end());
        Set<String> memberUuids = getTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        // Revenue by month — temporal: only include revenue for months the consultant was on this team
        @SuppressWarnings("unchecked")
        List<Tuple> revenueRows = em.createNativeQuery("""
                SELECT CONCAT(LPAD(fud.year, 4, '0'), LPAD(fud.month, 2, '0')) AS month_key,
                       fud.year, fud.month AS month_number,
                       COALESCE(SUM(fud.registered_amount), 0) AS revenue
                FROM fact_user_day fud
                JOIN teamroles tr ON tr.useruuid = fud.useruuid
                    AND tr.teamuuid = :teamId
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= fud.document_date
                    AND (tr.enddate > fud.document_date OR tr.enddate IS NULL)
                WHERE fud.document_date >= :fromDate AND fud.document_date <= :toDate
                  AND fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type = 'ACTIVE'
                GROUP BY fud.year, fud.month
                ORDER BY fud.year, fud.month
                """, Tuple.class)
                .setParameter("teamId", teamId)
                .setParameter("fromDate", fy.start())
                .setParameter("toDate", effectiveEnd)
                .getResultList();

        // Salary cost by month from fact_salary_monthly_teamroles
        @SuppressWarnings("unchecked")
        List<Tuple> salaryRows = em.createNativeQuery("""
                SELECT fsmt.month_key,
                       COALESCE(SUM(fsmt.salary_sum), 0) AS salary_cost
                FROM fact_salary_monthly_teamroles fsmt
                WHERE fsmt.teamuuid = :teamId
                  AND fsmt.month_key >= :fromKey AND fsmt.month_key <= :toKey
                GROUP BY fsmt.month_key
                ORDER BY fsmt.month_key
                """, Tuple.class)
                .setParameter("teamId", teamId)
                .setParameter("fromKey", toMonthKey(fy.start()))
                .setParameter("toKey", toMonthKey(effectiveEnd))
                .getResultList();

        Map<String, Double> salaryByMonth = new LinkedHashMap<>();
        for (Tuple row : salaryRows) {
            salaryByMonth.put((String) row.get("month_key"), numVal(row, "salary_cost"));
        }

        List<TeamRevenueCostTrendDTO> result = new ArrayList<>();
        for (Tuple row : revenueRows) {
            String mk = (String) row.get("month_key");
            int year = ((Number) row.get("year")).intValue();
            int month = ((Number) row.get("month_number")).intValue();
            double revenue = numVal(row, "revenue");
            double salary = salaryByMonth.getOrDefault(mk, 0.0);
            String label = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + year;
            result.add(new TeamRevenueCostTrendDTO(mk, year, month, label, revenue, salary, revenue - salary));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // 11. Revenue Per Member
    // -----------------------------------------------------------------------

    public List<TeamRevenuePerMemberDTO> getRevenuePerMember(String teamId, int fiscalYear) {
        var fy = getFiscalYearBounds(fiscalYear);
        LocalDate effectiveEnd = capToToday(fy.end());
        Set<String> memberUuids = getTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT fud.useruuid AS user_id, u.firstname, u.lastname,
                       COALESCE(SUM(fud.registered_amount), 0) AS revenue,
                       COALESCE(SUM(fud.registered_billable_hours), 0) AS billable_hours
                FROM fact_user_day fud
                JOIN user u ON u.uuid = fud.useruuid
                WHERE fud.useruuid IN (:memberUuids)
                  AND fud.document_date >= :fromDate AND fud.document_date <= :toDate
                  AND fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type = 'ACTIVE'
                GROUP BY fud.useruuid, u.firstname, u.lastname
                ORDER BY revenue DESC
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("fromDate", fy.start())
                .setParameter("toDate", effectiveEnd)
                .getResultList();

        List<TeamRevenuePerMemberDTO> result = new ArrayList<>();
        for (Tuple row : rows) {
            double revenue = numVal(row, "revenue");
            double hours = numVal(row, "billable_hours");
            Double effectiveRate = hours > 0 ? revenue / hours : null;
            result.add(new TeamRevenuePerMemberDTO(
                    (String) row.get("user_id"),
                    (String) row.get("firstname"),
                    (String) row.get("lastname"),
                    revenue, hours, effectiveRate));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // 12. Billing Rate Analysis
    // -----------------------------------------------------------------------

    public List<TeamBillingRateDTO> getBillingRateAnalysis(String teamId, int fiscalYear) {
        var fy = getFiscalYearBounds(fiscalYear);
        LocalDate effectiveEnd = capToToday(fy.end());
        Set<String> memberUuids = getTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        // Actual effective rates
        @SuppressWarnings("unchecked")
        List<Tuple> actualRows = em.createNativeQuery("""
                SELECT fud.useruuid AS user_id, u.firstname, u.lastname,
                       COALESCE(SUM(fud.registered_amount), 0) AS revenue,
                       COALESCE(SUM(fud.registered_billable_hours), 0) AS billable_hours
                FROM fact_user_day fud
                JOIN user u ON u.uuid = fud.useruuid
                WHERE fud.useruuid IN (:memberUuids)
                  AND fud.document_date >= :fromDate AND fud.document_date <= :toDate
                  AND fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type = 'ACTIVE'
                GROUP BY fud.useruuid, u.firstname, u.lastname
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("fromDate", fy.start())
                .setParameter("toDate", effectiveEnd)
                .getResultList();

        // Compute per-consultant monthly overhead from company non-salary OPEX
        // Same approach as ConsultantInsightsService.getUnprofitableConsultants()
        String fromKey = toMonthKey(fy.start());
        String toKey = toMonthKey(effectiveEnd);

        @SuppressWarnings("unchecked")
        List<Tuple> opexRows = em.createNativeQuery("""
                SELECT COALESCE(SUM(opex_amount_dkk), 0) AS total_opex
                FROM fact_opex_mat
                WHERE cost_type = 'OPEX'
                  AND month_key >= :fromKey AND month_key < :toKey
                """, Tuple.class)
                .setParameter("fromKey", fromKey)
                .setParameter("toKey", toKey)
                .getResultList();
        double totalOpex = opexRows.isEmpty() ? 0.0
                : ((Number) opexRows.get(0).get("total_opex")).doubleValue();

        @SuppressWarnings("unchecked")
        List<Tuple> hcRows = em.createNativeQuery("""
                SELECT COUNT(DISTINCT fud.useruuid) AS headcount
                FROM fact_user_day fud
                WHERE fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type = 'ACTIVE'
                  AND fud.document_date >= :fromDate AND fud.document_date <= :toDate
                """, Tuple.class)
                .setParameter("fromDate", fy.start())
                .setParameter("toDate", effectiveEnd)
                .getResultList();
        long headcount = hcRows.isEmpty() ? 1L
                : ((Number) hcRows.get(0).get("headcount")).longValue();
        if (headcount == 0) headcount = 1; // avoid division by zero

        long monthsInRange = ChronoUnit.MONTHS.between(
                YearMonth.from(fy.start()), YearMonth.from(effectiveEnd)) + 1;
        if (monthsInRange <= 0) monthsInRange = 1;
        double overheadPerConsultantMonthly = totalOpex / headcount / monthsInRange;

        log.debugf("getBillingRateAnalysis: totalOpex=%.2f, headcount=%d, months=%d, overheadPerMonth=%.2f",
                totalOpex, headcount, monthsInRange, overheadPerConsultantMonthly);

        // Break-even rates: (monthly salary + monthly overhead) / (net available hours * 0.75)
        @SuppressWarnings("unchecked")
        List<Tuple> breakEvenRows = em.createNativeQuery("""
                SELECT sal.useruuid AS user_id,
                       CASE WHEN COALESCE(avail.net_hours, 0) * 0.75 > 0
                            THEN (sal.monthly_salary + :overheadPerMonth) / (avail.net_hours * 0.75)
                            ELSE NULL
                       END AS break_even_rate
                FROM (
                    SELECT useruuid, AVG(max_salary) AS monthly_salary FROM (
                        SELECT useruuid, MAX(salary) AS max_salary
                        FROM fact_user_day
                        WHERE useruuid IN (:memberUuids)
                          AND consultant_type = 'CONSULTANT'
                          AND salary > 0
                          AND document_date >= :fromDate AND document_date <= :toDate
                        GROUP BY useruuid, year, month
                    ) ms GROUP BY useruuid
                ) sal
                LEFT JOIN (
                    SELECT user_id AS useruuid,
                           AVG(net_available_hours) AS net_hours
                    FROM fact_user_utilization_mat
                    WHERE user_id IN (:memberUuids)
                      AND month_key >= :fromKey AND month_key <= :toKey
                    GROUP BY user_id
                ) avail ON avail.useruuid = sal.useruuid
                """, Tuple.class)
                .setParameter("overheadPerMonth", overheadPerConsultantMonthly)
                .setParameter("memberUuids", memberUuids)
                .setParameter("fromDate", fy.start())
                .setParameter("toDate", effectiveEnd)
                .setParameter("fromKey", fromKey)
                .setParameter("toKey", toKey)
                .getResultList();

        Map<String, Double> breakEvenMap = new LinkedHashMap<>();
        for (Tuple row : breakEvenRows) {
            Object beVal = row.get("break_even_rate");
            breakEvenMap.put((String) row.get("user_id"),
                    beVal != null ? ((Number) beVal).doubleValue() : null);
        }

        List<TeamBillingRateDTO> result = new ArrayList<>();
        for (Tuple row : actualRows) {
            String uid = (String) row.get("user_id");
            double revenue = numVal(row, "revenue");
            double hours = numVal(row, "billable_hours");
            Double actualRate = hours > 0 ? revenue / hours : null;
            Double breakEven = breakEvenMap.get(uid);
            Double margin = (actualRate != null && breakEven != null) ? actualRate - breakEven : null;
            result.add(new TeamBillingRateDTO(
                    uid,
                    (String) row.get("firstname"),
                    (String) row.get("lastname"),
                    actualRate, breakEven, margin));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // 13. Contribution Margin
    // -----------------------------------------------------------------------

    public TeamContributionMarginDTO getContributionMargin(String teamId, int fiscalYear) {
        var fy = getFiscalYearBounds(fiscalYear);
        LocalDate effectiveEnd = capToToday(fy.end());
        String teamName = getTeamName(teamId);
        Set<String> memberUuids = getTeamMemberUuids(teamId, LocalDate.now());

        double revenue = 0;
        double salaryCost = 0;

        if (!memberUuids.isEmpty()) {
            // Revenue
            var revRow = querySingleRow("""
                    SELECT COALESCE(SUM(fud.registered_amount), 0) AS revenue
                    FROM fact_user_day fud
                    WHERE fud.useruuid IN (:memberUuids)
                      AND fud.document_date >= :fromDate AND fud.document_date <= :toDate
                      AND fud.consultant_type = 'CONSULTANT'
                      AND fud.status_type = 'ACTIVE'
                    """,
                    Map.of("memberUuids", memberUuids,
                            "fromDate", fy.start(),
                            "toDate", effectiveEnd));
            revenue = numVal(revRow, "revenue");

            // Salary cost
            var salRow = querySingleRow("""
                    SELECT COALESCE(SUM(fsmt.salary_sum), 0) AS salary_cost
                    FROM fact_salary_monthly_teamroles fsmt
                    WHERE fsmt.teamuuid = :teamId
                      AND fsmt.month_key >= :fromKey AND fsmt.month_key <= :toKey
                    """,
                    Map.of("teamId", teamId,
                            "fromKey", toMonthKey(fy.start()),
                            "toKey", toMonthKey(effectiveEnd)));
            salaryCost = numVal(salRow, "salary_cost");
        }

        // Allocated OPEX: team's share based on headcount ratio
        // Total company OPEX and headcount
        var opexRow = querySingleRow("""
                SELECT COALESCE(SUM(fo.opex_amount_dkk), 0) AS total_opex
                FROM fact_opex_mat fo
                WHERE fo.month_key >= :fromKey AND fo.month_key <= :toKey
                  AND fo.cost_type = 'OPEX'
                """,
                Map.of("fromKey", toMonthKey(fy.start()),
                        "toKey", toMonthKey(effectiveEnd)));
        double totalOpex = numVal(opexRow, "total_opex");

        var hcRow = querySingleRow("""
                SELECT COUNT(DISTINCT tr.useruuid) AS total_members
                FROM teamroles tr
                WHERE tr.membertype = 'MEMBER'
                  AND tr.startdate <= CURDATE()
                  AND (tr.enddate IS NULL OR tr.enddate > CURDATE())
                """,
                Map.of());
        long totalMembers = hcRow != null ? ((Number) hcRow.get("total_members")).longValue() : 1;
        if (totalMembers == 0) totalMembers = 1;

        double allocatedOpex = totalOpex * ((double) memberUuids.size() / totalMembers);
        double grossMargin = revenue - salaryCost;
        double contributionMargin = grossMargin - allocatedOpex;
        Double grossMarginPct = revenue > 0 ? (grossMargin / revenue) * 100.0 : null;
        Double contributionMarginPct = revenue > 0 ? (contributionMargin / revenue) * 100.0 : null;

        return new TeamContributionMarginDTO(
                teamId, teamName, fiscalYear,
                revenue, salaryCost, allocatedOpex,
                grossMargin, contributionMargin,
                grossMarginPct, contributionMarginPct);
    }

    // -----------------------------------------------------------------------
    // 14. Client Concentration
    // -----------------------------------------------------------------------

    public List<TeamClientConcentrationDTO> getClientConcentration(String teamId, int fiscalYear) {
        var fy = getFiscalYearBounds(fiscalYear);
        LocalDate effectiveEnd = capToToday(fy.end());
        Set<String> memberUuids = getTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        // fact_user_day has no client column; join through work → contract → client
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT c_client.uuid AS client_uuid, c_client.name AS client_name,
                       COALESCE(SUM(w.workduration * cc.rate), 0) AS revenue
                FROM work w
                JOIN contract_consultants cc ON cc.contractuuid = w.contractuuid
                    AND cc.useruuid = w.useruuid
                    AND w.registered >= cc.activefrom AND w.registered <= cc.activeto
                JOIN contracts c ON c.uuid = w.contractuuid
                JOIN client c_client ON c_client.uuid = c.clientuuid
                WHERE w.useruuid IN (:memberUuids)
                  AND w.registered >= :fromDate AND w.registered <= :toDate
                  AND w.workduration > 0
                GROUP BY c_client.uuid, c_client.name
                ORDER BY revenue DESC
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("fromDate", fy.start())
                .setParameter("toDate", effectiveEnd)
                .getResultList();

        double totalRevenue = rows.stream()
                .mapToDouble(r -> numVal(r, "revenue"))
                .sum();

        List<TeamClientConcentrationDTO> result = new ArrayList<>();
        for (Tuple row : rows) {
            double rev = numVal(row, "revenue");
            double share = totalRevenue > 0 ? (rev / totalRevenue) * 100.0 : 0.0;
            result.add(new TeamClientConcentrationDTO(
                    (String) row.get("client_uuid"),
                    (String) row.get("client_name"),
                    rev, share));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // 15. Consultant Profitability (reuses ConsultantInsightsService)
    // -----------------------------------------------------------------------

    public List<UnprofitableConsultantDTO> getConsultantProfitability(String teamId, int fiscalYear) {
        // Get all unprofitable consultants company-wide, then filter to team members
        Set<String> memberUuids = getTeamMemberUuids(teamId, LocalDate.now());
        if (memberUuids.isEmpty()) {
            return List.of();
        }

        // Reuse existing service method (company-wide), filter by team membership
        List<UnprofitableConsultantDTO> all = consultantInsightsService.getUnprofitableConsultants(
                null, null);

        return all.stream()
                .filter(dto -> memberUuids.contains(dto.getUserId()))
                .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private String getTeamName(String teamId) {
        var row = querySingleRow("SELECT name FROM team WHERE uuid = :teamId",
                Map.of("teamId", teamId));
        return row != null ? (String) row.get("name") : "";
    }

    private List<TeamRosterMemberDTO> buildRoster(Set<String> memberUuids,
                                                   FiscalYearBounds fy,
                                                   LocalDate effectiveEnd) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT u.uuid AS user_id, u.firstname, u.lastname, u.practice,
                       us.status,
                       COALESCE(util.billable, 0) AS billable,
                       COALESCE(util.net_available, 0) AS net_available,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM contract_consultants cc
                           JOIN contracts c ON c.uuid = cc.contractuuid
                           WHERE cc.useruuid = u.uuid
                             AND cc.activefrom <= CURDATE() AND cc.activeto > CURDATE()
                             AND c.status IN ('SIGNED', 'TIME')
                       ) THEN 1 ELSE 0 END AS has_active_contract
                FROM user u
                JOIN userstatus us ON us.useruuid = u.uuid
                     AND us.statusdate = (
                         SELECT MAX(us2.statusdate) FROM userstatus us2
                         WHERE us2.useruuid = u.uuid AND us2.statusdate <= CURDATE()
                     )
                LEFT JOIN (
                    SELECT fum.user_id,
                           SUM(fum.billable_hours) AS billable,
                           SUM(fum.net_available_hours) AS net_available
                    FROM fact_user_utilization_mat fum
                    WHERE fum.user_id IN (:memberUuids)
                      AND fum.month_key >= :fromKey AND fum.month_key <= :toKey
                    GROUP BY fum.user_id
                ) util ON util.user_id = u.uuid
                WHERE u.uuid IN (:memberUuids)
                ORDER BY u.lastname, u.firstname
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("fromKey", toMonthKey(fy.start()))
                .setParameter("toKey", toMonthKey(effectiveEnd))
                .getResultList();

        // Query career levels: latest active career level per member
        Map<String, String[]> careerByUser = queryCareerLevels(memberUuids);

        // Query active contracts with client info
        Map<String, List<RosterContract>> contractsByUser = queryActiveContracts(memberUuids);

        List<TeamRosterMemberDTO> roster = new ArrayList<>();
        for (Tuple row : rows) {
            double b = numVal(row, "billable");
            double n = numVal(row, "net_available");
            Double pct = n > 0 ? (b / n) * 100.0 : null;
            String userId = (String) row.get("user_id");
            String[] career = careerByUser.getOrDefault(userId, new String[]{null, null});
            roster.add(new TeamRosterMemberDTO(
                    userId,
                    (String) row.get("firstname"),
                    (String) row.get("lastname"),
                    (String) row.get("practice"),
                    (String) row.get("status"),
                    pct,
                    ((Number) row.get("has_active_contract")).intValue() > 0,
                    career[0],
                    career[1],
                    contractsByUser.getOrDefault(userId, List.of())));
        }
        return roster;
    }

    /**
     * Returns a map of userId → [careerLevel, careerTrack] for the latest active career level per user.
     */
    private Map<String, String[]> queryCareerLevels(Set<String> memberUuids) {
        @SuppressWarnings("unchecked")
        List<Tuple> careerRows = em.createNativeQuery("""
                SELECT ucl.useruuid, ucl.career_level, ucl.career_track
                FROM user_career_level ucl
                INNER JOIN (
                    SELECT useruuid, MAX(active_from) AS max_from
                    FROM user_career_level
                    WHERE useruuid IN (:memberUuids) AND active_from <= CURDATE()
                    GROUP BY useruuid
                ) latest ON ucl.useruuid = latest.useruuid AND ucl.active_from = latest.max_from
                WHERE ucl.useruuid IN (:memberUuids)
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .getResultList();

        Map<String, String[]> careerByUser = new HashMap<>();
        for (Tuple cr : careerRows) {
            careerByUser.put(
                    (String) cr.get("useruuid"),
                    new String[]{(String) cr.get("career_level"), (String) cr.get("career_track")});
        }
        return careerByUser;
    }

    /**
     * Returns a map of userId → list of active RosterContracts (sorted by activeTo ASC).
     */
    private Map<String, List<RosterContract>> queryActiveContracts(Set<String> memberUuids) {
        @SuppressWarnings("unchecked")
        List<Tuple> contractRows = em.createNativeQuery("""
                SELECT cc.useruuid AS user_id, cl.name AS client_name, c.name AS contract_name,
                       cc.activeto, cc.hours
                FROM contract_consultants cc
                JOIN contracts c ON c.uuid = cc.contractuuid
                JOIN client cl ON cl.uuid = c.clientuuid
                WHERE cc.useruuid IN (:memberUuids)
                  AND cc.activefrom <= CURDATE()
                  AND cc.activeto > CURDATE()
                  AND c.status IN ('SIGNED', 'TIME')
                ORDER BY cc.activeto ASC
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .getResultList();

        Map<String, List<RosterContract>> contractsByUser = new HashMap<>();
        for (Tuple cr : contractRows) {
            String userId = (String) cr.get("user_id");
            contractsByUser
                    .computeIfAbsent(userId, k -> new ArrayList<>())
                    .add(new RosterContract(
                            (String) cr.get("client_name"),
                            (String) cr.get("contract_name"),
                            toLocalDate(cr.get("activeto")),
                            numVal(cr, "hours")));
        }
        return contractsByUser;
    }

    private List<TeamAttentionItemDTO> buildAttentionItems(List<TeamBenchConsultantDTO> bench) {
        List<TeamAttentionItemDTO> items = new ArrayList<>();
        for (TeamBenchConsultantDTO b : bench) {
            String severity;
            if (b.daysSinceContract() >= 365 || b.daysSinceContract() == -1) {
                severity = "CRITICAL";
            } else if (b.daysSinceContract() >= 180) {
                severity = "WARNING";
            } else {
                severity = "INFO";
            }
            items.add(new TeamAttentionItemDTO(
                    "BENCH",
                    severity,
                    b.userId(),
                    b.firstname(),
                    b.lastname(),
                    "On bench for " + (b.daysSinceContract() >= 0 ? b.daysSinceContract() + " days" : "no prior contract")));
        }
        return items;
    }

    private TeamOverviewDTO emptyOverview(String teamId) {
        return new TeamOverviewDTO(
                teamId, getTeamName(teamId), 0,
                null, 0, 0, null,
                List.of(), List.of());
    }

    /**
     * Executes a native query expecting exactly one row and returns it as a Tuple.
     * Returns null if no rows found.
     */
    private Tuple querySingleRow(String sql, Map<String, Object> params) {
        var query = em.createNativeQuery(sql, Tuple.class);
        for (var entry : params.entrySet()) {
            query.setParameter(entry.getKey(), entry.getValue());
        }
        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String toMonthKey(LocalDate date) {
        return String.format("%04d%02d", date.getYear(), date.getMonthValue());
    }

    private static LocalDate capToToday(LocalDate date) {
        LocalDate today = LocalDate.now();
        return date.isAfter(today) ? today : date;
    }

    private static double numVal(Tuple row, String column) {
        Object val = row.get(column);
        return val != null ? ((Number) val).doubleValue() : 0.0;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(value.toString());
    }
}
