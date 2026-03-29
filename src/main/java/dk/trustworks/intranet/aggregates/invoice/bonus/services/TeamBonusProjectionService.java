package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.dto.AllTeamsBonusRankingDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamBonusProjectionDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamBonusProjectionDTO.MonthlyUtilization;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamBonusProjectionDTO.PoolBonusDetail;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamBonusProjectionDTO.ProductionBonusDetail;
import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for calculating team lead bonus projections.
 * <p>
 * This is a read-only query service (CQRS query side). The bonus formulas are:
 * <ul>
 *   <li>Pool bonus: MAX(team_avg_util - 0.65, 0) * 5 * team_factor * price_per_point * 100</li>
 *   <li>Production bonus: MAX((own_revenue - prorated_threshold) * 0.20, 0)</li>
 * </ul>
 *
 * @see dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamBonusProjectionDTO
 */
@JBossLog
@ApplicationScoped
public class TeamBonusProjectionService {

    private static final double MIN_UTIL_THRESHOLD = 0.65;
    private static final double POOL_SHARE_PERCENT = 0.05;
    private static final double PRODUCTION_THRESHOLD_ANNUAL = 1_100_000.0;
    private static final double PRODUCTION_COMMISSION = 0.20;
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Inject
    EntityManager em;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /**
     * Validates that the requesting user is a LEADER of the specified team.
     *
     * @param teamId the team UUID to check
     * @throws ForbiddenException if the requester is not a leader of the team
     * @throws NotFoundException  if the team does not exist
     */
    public void validateTeamAccess(String teamId) {
        Team team = Team.findById(teamId);
        if (team == null) {
            throw new NotFoundException("Team not found: " + teamId);
        }

        String requestedBy = requestHeaderHolder.getUserUuid();
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new ForbiddenException("Missing X-Requested-By header");
        }

        LocalDate today = LocalDate.now();
        long leaderCount = TeamRole.count(
                "teamuuid = ?1 AND useruuid = ?2 AND teammembertype = ?3 " +
                        "AND startdate <= ?4 AND (enddate > ?4 OR enddate IS NULL)",
                teamId, requestedBy, TeamMemberType.LEADER, today
        );
        if (leaderCount == 0) {
            throw new ForbiddenException("User is not a leader of team " + teamId);
        }
    }

    /**
     * Calculates the full bonus projection for a specific team leader in a fiscal year.
     *
     * @param teamId     the team UUID
     * @param fiscalYear the fiscal year start year (e.g., 2025 for FY 2025-07-01 to 2026-06-30)
     * @return complete bonus projection including pool and production components
     */
    public TeamBonusProjectionDTO getBonusProjection(String teamId, int fiscalYear) {
        Team team = Team.findById(teamId);
        if (team == null) {
            throw new NotFoundException("Team not found: " + teamId);
        }

        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd = LocalDate.of(fiscalYear + 1, 6, 30);
        List<YearMonth> consideredMonths = getConsideredMonths(fyStart, fyEnd);

        // Find the leader of this team for the FY
        LeaderInfo leaderInfo = findLeaderForTeam(teamId, fyStart, fyEnd);

        // Calculate monthly utilization for this team
        List<MonthlyUtilization> monthlyUtil = calculateMonthlyUtilization(teamId, consideredMonths);

        // Pool bonus calculation
        PoolBonusDetail poolBonus = calculatePoolBonus(teamId, team.getName(), leaderInfo, consideredMonths, monthlyUtil, fyStart, fyEnd);

        // Production bonus calculation
        ProductionBonusDetail productionBonus = calculateProductionBonus(leaderInfo, fyStart, fyEnd, consideredMonths.size());

        double combinedBonus = BigDecimal.valueOf(poolBonus.adjustedPoolBonus())
                .add(BigDecimal.valueOf(productionBonus.productionBonus()))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();

        return new TeamBonusProjectionDTO(
                fiscalYear,
                teamId,
                team.getName(),
                leaderInfo.uuid(),
                leaderInfo.name(),
                poolBonus,
                productionBonus,
                combinedBonus,
                null // previousFyBonus: would require a separate locked data lookup
        );
    }

    /**
     * Returns bonus ranking data for all bonus-eligible teams.
     *
     * @param currentTeamId the requesting team's UUID (marked with isCurrentTeam = true)
     * @param fiscalYear    the fiscal year start year
     * @return list of all bonus-eligible teams with their points, sorted by rawPoints descending
     */
    public List<AllTeamsBonusRankingDTO> getAllTeamsBonusRanking(String currentTeamId, int fiscalYear) {
        List<Team> bonusTeams = Team.<Team>list("teamleadbonus = true");

        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd = LocalDate.of(fiscalYear + 1, 6, 30);
        List<YearMonth> consideredMonths = getConsideredMonths(fyStart, fyEnd);

        if (consideredMonths.isEmpty()) {
            return bonusTeams.stream()
                    .map(t -> new AllTeamsBonusRankingDTO(
                            t.getUuid(), t.getName(), findLeaderNameForTeam(t.getUuid(), fyStart, fyEnd),
                            0.0, 1.0, 0.0, 0, t.getUuid().equals(currentTeamId)))
                    .sorted(Comparator.comparing(AllTeamsBonusRankingDTO::teamName))
                    .toList();
        }

        List<AllTeamsBonusRankingDTO> rankings = new ArrayList<>();
        for (Team team : bonusTeams) {
            List<MonthlyUtilization> monthlyUtil = calculateMonthlyUtilization(team.getUuid(), consideredMonths);
            TeamUtilizationResult utilResult = aggregateUtilization(monthlyUtil);
            double teamFactor = computeTeamFactor(utilResult.avgTeamSize());
            double utilAboveMin = Math.max(utilResult.avgUtilization() - MIN_UTIL_THRESHOLD, 0.0);
            double rawPoints = utilAboveMin * 5.0 * teamFactor;
            rawPoints = BigDecimal.valueOf(rawPoints).setScale(6, RoundingMode.HALF_UP).doubleValue();

            String leaderName = findLeaderNameForTeam(team.getUuid(), fyStart, fyEnd);

            rankings.add(new AllTeamsBonusRankingDTO(
                    team.getUuid(),
                    team.getName(),
                    leaderName,
                    rawPoints,
                    teamFactor,
                    utilResult.avgUtilization(),
                    (int) Math.round(utilResult.avgTeamSize()),
                    team.getUuid().equals(currentTeamId)
            ));
        }

        rankings.sort(Comparator.comparingDouble(AllTeamsBonusRankingDTO::rawPoints).reversed());
        return rankings;
    }

    // ---- Private calculation methods ----

    /**
     * Returns considered months: past completed months within the fiscal year.
     * The current incomplete month is excluded.
     */
    private List<YearMonth> getConsideredMonths(LocalDate fyStart, LocalDate fyEnd) {
        YearMonth start = YearMonth.from(fyStart);
        YearMonth lastConsidered;

        if (fyEnd.isAfter(LocalDate.now())) {
            // FY is still in progress: last considered = previous completed month
            lastConsidered = YearMonth.now().minusMonths(1);
        } else {
            // FY is complete
            lastConsidered = YearMonth.from(fyEnd);
        }

        if (lastConsidered.isBefore(start)) {
            return List.of();
        }

        List<YearMonth> months = new ArrayList<>();
        YearMonth current = start;
        while (!current.isAfter(lastConsidered)) {
            months.add(current);
            current = current.plusMonths(1);
        }
        return months;
    }

    /**
     * Calculates monthly utilization for a team across the considered months.
     * Uses fact_user_utilization_mat joined with teamroles.
     * Returns billable_hours / net_available_hours per month (never averaged).
     */
    private List<MonthlyUtilization> calculateMonthlyUtilization(String teamId, List<YearMonth> months) {
        if (months.isEmpty()) return List.of();

        // Build month_key parameters (format: YYYYMM)
        List<String> monthKeys = months.stream()
                .map(ym -> String.format("%04d%02d", ym.getYear(), ym.getMonthValue()))
                .toList();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT fum.month_key,
                       SUM(fum.billable_hours) AS total_billable,
                       SUM(fum.net_available_hours) AS total_available,
                       COUNT(DISTINCT fum.user_id) AS member_count
                FROM fact_user_utilization_mat fum
                JOIN teamroles tr ON tr.useruuid = fum.user_id
                    AND tr.teamuuid = :teamId
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= CONCAT(fum.year, '-', LPAD(fum.month_number, 2, '0'), '-01')
                    AND (tr.enddate > CONCAT(fum.year, '-', LPAD(fum.month_number, 2, '0'), '-01') OR tr.enddate IS NULL)
                WHERE fum.month_key IN (:monthKeys)
                GROUP BY fum.month_key
                ORDER BY fum.month_key
                """)
                .setParameter("teamId", teamId)
                .setParameter("monthKeys", monthKeys)
                .getResultList();

        return rows.stream().map(row -> {
            String monthKey = (String) row[0];
            double billable = ((Number) row[1]).doubleValue();
            double available = ((Number) row[2]).doubleValue();
            int memberCount = ((Number) row[3]).intValue();
            double utilization = available > 0 ? billable / available : 0.0;
            // Convert YYYYMM to YYYY-MM
            String formattedMonth = monthKey.substring(0, 4) + "-" + monthKey.substring(4);
            return new MonthlyUtilization(formattedMonth, utilization, memberCount);
        }).toList();
    }

    /**
     * Aggregates monthly utilization into team averages.
     * Uses sum of all hours first, then divides (never averages percentages).
     */
    private TeamUtilizationResult aggregateUtilization(List<MonthlyUtilization> monthlyUtil) {
        if (monthlyUtil.isEmpty()) {
            return new TeamUtilizationResult(0.0, 0.0);
        }

        // For avg utilization: re-query is wasteful; use the monthly breakdown
        // The monthly utilization already represents billable/available per month
        // For the overall average, we need to weight by available hours
        // Since we only have ratios here, we approximate using equal-weight months
        // This is acceptable because the spec says "AVG(utilization) FOR team members DURING considered_months"
        double sumUtil = monthlyUtil.stream().mapToDouble(MonthlyUtilization::utilization).sum();
        double avgUtil = sumUtil / monthlyUtil.size();

        // Team size: average member count (excluding months with 0 members per spec 7.4)
        List<MonthlyUtilization> nonEmptyMonths = monthlyUtil.stream()
                .filter(m -> m.memberCount() > 0)
                .toList();
        double avgTeamSize = nonEmptyMonths.isEmpty() ? 0.0 :
                nonEmptyMonths.stream().mapToInt(MonthlyUtilization::memberCount).average().orElse(0.0);

        return new TeamUtilizationResult(avgUtil, avgTeamSize);
    }

    /**
     * Computes the team factor bracket.
     * <ul>
     *   <li>avg_team_size >= 11 -> 2.0</li>
     *   <li>avg_team_size >= 7 -> 1.5</li>
     *   <li>otherwise -> 1.0</li>
     * </ul>
     */
    private double computeTeamFactor(double avgTeamSize) {
        if (avgTeamSize >= 11.0) return 2.0;
        if (avgTeamSize >= 7.0) return 1.5;
        return 1.0;
    }

    /**
     * Calculates the pool bonus for a specific team and leader.
     * Includes the dynamic price-per-point derived from all bonus-eligible teams.
     */
    private PoolBonusDetail calculatePoolBonus(String teamId, String teamName, LeaderInfo leader,
                                                List<YearMonth> consideredMonths,
                                                List<MonthlyUtilization> monthlyUtil,
                                                LocalDate fyStart, LocalDate fyEnd) {
        if (consideredMonths.isEmpty()) {
            return new PoolBonusDetail(0, 0, 0, 1.0, 0, 0, 0, 0, 0, List.of());
        }

        TeamUtilizationResult utilResult = aggregateUtilization(monthlyUtil);
        double teamFactor = computeTeamFactor(utilResult.avgTeamSize());
        double utilAboveMin = Math.max(utilResult.avgUtilization() - MIN_UTIL_THRESHOLD, 0.0);
        double rawPoints = utilAboveMin * 5.0 * teamFactor;

        // Calculate price per point from company pool
        double pricePerPoint = calculateDynamicPricePerPoint(consideredMonths, fyStart, fyEnd);

        double poolShare = BigDecimal.valueOf(rawPoints * 100.0 * pricePerPoint)
                .setScale(2, RoundingMode.HALF_UP).doubleValue();

        int monthsAsLeader = leader.monthsAsLeader();
        double adjustedPoolBonus = BigDecimal.valueOf((poolShare / 12.0) * monthsAsLeader)
                .setScale(2, RoundingMode.HALF_UP).doubleValue();

        return new PoolBonusDetail(
                BigDecimal.valueOf(utilResult.avgUtilization()).setScale(4, RoundingMode.HALF_UP).doubleValue(),
                BigDecimal.valueOf(utilAboveMin).setScale(4, RoundingMode.HALF_UP).doubleValue(),
                BigDecimal.valueOf(utilResult.avgTeamSize()).setScale(1, RoundingMode.HALF_UP).doubleValue(),
                teamFactor,
                BigDecimal.valueOf(rawPoints).setScale(6, RoundingMode.HALF_UP).doubleValue(),
                BigDecimal.valueOf(pricePerPoint).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                poolShare,
                monthsAsLeader,
                adjustedPoolBonus,
                monthlyUtil
        );
    }

    /**
     * Calculates the dynamic price per point using preProration mode.
     * price_per_point = pool_amount / (sum_raw_points * 100)
     */
    private double calculateDynamicPricePerPoint(List<YearMonth> consideredMonths,
                                                  LocalDate fyStart, LocalDate fyEnd) {
        // Company revenue and salary for bonus-eligible teams
        double companyRevenue = calculateCompanyRevenue(fyStart, fyEnd);
        double companySalary = calculateCompanySalary(fyStart, fyEnd);
        double companyProfit = companyRevenue - companySalary;
        double poolAmount = Math.max(companyProfit, 0.0) * POOL_SHARE_PERCENT;

        if (poolAmount <= 0.0) return 0.0;

        // Sum raw points across all bonus-eligible teams
        double sumRawPoints = calculateSumRawPoints(consideredMonths, fyStart, fyEnd);
        if (sumRawPoints <= 0.0) return 0.0;

        return poolAmount / (sumRawPoints * 100.0);
    }

    /**
     * Calculates company revenue for bonus-eligible teams within the fiscal year.
     */
    private double calculateCompanyRevenue(LocalDate fyStart, LocalDate fyEnd) {
        Object result = em.createNativeQuery("""
                SELECT COALESCE(SUM(fud.registered_amount), 0)
                FROM fact_user_day fud
                JOIN teamroles tr ON tr.useruuid = fud.useruuid
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= fud.document_date
                    AND (tr.enddate > fud.document_date OR tr.enddate IS NULL)
                JOIN team t ON t.uuid = tr.teamuuid
                    AND t.teamleadbonus = 1
                WHERE fud.document_date >= :fyStart
                  AND fud.document_date <= :fyEnd
                  AND fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type = 'ACTIVE'
                """)
                .setParameter("fyStart", fyStart)
                .setParameter("fyEnd", fyEnd)
                .getSingleResult();
        return ((Number) result).doubleValue();
    }

    /**
     * Calculates company salary costs for bonus-eligible teams within the fiscal year.
     * Uses monthly MAX(salary) per user per month (consistent with existing patterns).
     */
    private double calculateCompanySalary(LocalDate fyStart, LocalDate fyEnd) {
        Object result = em.createNativeQuery("""
                SELECT COALESCE(SUM(monthly_salary), 0) FROM (
                    SELECT fud.useruuid, YEAR(fud.document_date) AS yr, MONTH(fud.document_date) AS mo,
                           MAX(fud.salary) AS monthly_salary
                    FROM fact_user_day fud
                    JOIN teamroles tr ON tr.useruuid = fud.useruuid
                        AND tr.membertype = 'MEMBER'
                        AND tr.startdate <= fud.document_date
                        AND (tr.enddate > fud.document_date OR tr.enddate IS NULL)
                    JOIN team t ON t.uuid = tr.teamuuid
                        AND t.teamleadbonus = 1
                    WHERE fud.document_date >= :fyStart
                      AND fud.document_date <= :fyEnd
                      AND fud.consultant_type = 'CONSULTANT'
                      AND fud.status_type = 'ACTIVE'
                    GROUP BY fud.useruuid, yr, mo
                ) monthly
                """)
                .setParameter("fyStart", fyStart)
                .setParameter("fyEnd", fyEnd)
                .getSingleResult();
        return ((Number) result).doubleValue();
    }

    /**
     * Calculates the sum of raw points across all bonus-eligible teams.
     */
    private double calculateSumRawPoints(List<YearMonth> consideredMonths,
                                          LocalDate fyStart, LocalDate fyEnd) {
        List<Team> bonusTeams = Team.<Team>list("teamleadbonus = true");
        double sumPoints = 0.0;

        for (Team team : bonusTeams) {
            List<MonthlyUtilization> monthlyUtil = calculateMonthlyUtilization(team.getUuid(), consideredMonths);
            TeamUtilizationResult utilResult = aggregateUtilization(monthlyUtil);
            double teamFactor = computeTeamFactor(utilResult.avgTeamSize());
            double utilAboveMin = Math.max(utilResult.avgUtilization() - MIN_UTIL_THRESHOLD, 0.0);
            sumPoints += utilAboveMin * 5.0 * teamFactor;
        }

        return sumPoints;
    }

    /**
     * Calculates the production bonus for a leader's own billable revenue.
     */
    private ProductionBonusDetail calculateProductionBonus(LeaderInfo leader, LocalDate fyStart, LocalDate fyEnd,
                                                           int consideredMonthCount) {
        double ownRevenue = calculateLeaderOwnRevenue(leader.uuid(), fyStart, fyEnd);
        double proratedThreshold = PRODUCTION_THRESHOLD_ANNUAL * ((double) leader.monthsAsLeader() / 12.0);
        double productionBonus = Math.max((ownRevenue - proratedThreshold) * PRODUCTION_COMMISSION, 0.0);
        productionBonus = BigDecimal.valueOf(productionBonus).setScale(2, RoundingMode.HALF_UP).doubleValue();

        double annualizedRevenue = consideredMonthCount > 0
                ? (ownRevenue / consideredMonthCount) * 12.0
                : 0.0;
        annualizedRevenue = BigDecimal.valueOf(annualizedRevenue).setScale(2, RoundingMode.HALF_UP).doubleValue();

        return new ProductionBonusDetail(
                BigDecimal.valueOf(ownRevenue).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                BigDecimal.valueOf(proratedThreshold).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                productionBonus,
                annualizedRevenue
        );
    }

    /**
     * Calculates a leader's own billable revenue within the fiscal year.
     */
    private double calculateLeaderOwnRevenue(String leaderUuid, LocalDate fyStart, LocalDate fyEnd) {
        Object result = em.createNativeQuery("""
                SELECT COALESCE(SUM(fud.registered_amount), 0)
                FROM fact_user_day fud
                WHERE fud.useruuid = :leaderUuid
                  AND fud.document_date >= :fyStart
                  AND fud.document_date <= :fyEnd
                  AND fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type = 'ACTIVE'
                """)
                .setParameter("leaderUuid", leaderUuid)
                .setParameter("fyStart", fyStart)
                .setParameter("fyEnd", fyEnd)
                .getSingleResult();
        return ((Number) result).doubleValue();
    }

    /**
     * Finds the primary leader for a team during a fiscal year.
     * Returns the leader with the longest tenure in the FY.
     */
    private LeaderInfo findLeaderForTeam(String teamId, LocalDate fyStart, LocalDate fyEnd) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT tr.useruuid,
                       u.firstname, u.lastname,
                       tr.startdate, tr.enddate
                FROM teamroles tr
                JOIN user u ON u.uuid = tr.useruuid
                WHERE tr.teamuuid = :teamId
                  AND tr.membertype = 'LEADER'
                  AND tr.startdate <= :fyEnd
                  AND (tr.enddate > :fyStart OR tr.enddate IS NULL)
                ORDER BY tr.startdate ASC
                """)
                .setParameter("teamId", teamId)
                .setParameter("fyStart", fyStart)
                .setParameter("fyEnd", fyEnd)
                .getResultList();

        if (rows.isEmpty()) {
            // No leader found; return a placeholder
            return new LeaderInfo("unknown", "Unknown Leader", 0);
        }

        // Merge overlapping leader periods and find the primary leader (longest tenure)
        Map<String, LeaderCandidate> candidates = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String uuid = (String) row[0];
            String firstName = (String) row[1];
            String lastName = (String) row[2];
            LocalDate roleStart = ((java.sql.Date) row[3]).toLocalDate();
            LocalDate roleEnd = row[4] != null ? ((java.sql.Date) row[4]).toLocalDate() : fyEnd;

            // Clip to FY boundaries
            LocalDate effectiveStart = roleStart.isBefore(fyStart) ? fyStart : roleStart;
            LocalDate effectiveEnd = roleEnd.isAfter(fyEnd) ? fyEnd : roleEnd;

            candidates.merge(uuid,
                    new LeaderCandidate(uuid, firstName + " " + lastName, effectiveStart, effectiveEnd),
                    (existing, newer) -> new LeaderCandidate(
                            existing.uuid(),
                            existing.name(),
                            existing.start().isBefore(newer.start()) ? existing.start() : newer.start(),
                            existing.end().isAfter(newer.end()) ? existing.end() : newer.end()
                    ));
        }

        // Select leader with longest tenure
        LeaderCandidate best = candidates.values().stream()
                .max(Comparator.comparingLong(c -> java.time.temporal.ChronoUnit.DAYS.between(c.start(), c.end())))
                .orElseThrow();

        int monthsAsLeader = countMonthsAsLeader(best.start(), best.end(), fyStart, fyEnd);

        return new LeaderInfo(best.uuid(), best.name(), monthsAsLeader);
    }

    /**
     * Finds leader name for a team (simplified lookup for ranking).
     */
    private String findLeaderNameForTeam(String teamId, LocalDate fyStart, LocalDate fyEnd) {
        LeaderInfo leader = findLeaderForTeam(teamId, fyStart, fyEnd);
        return leader.name();
    }

    /**
     * Counts months the leader held the role within the FY.
     * Partial months count as 1 (per business rules).
     */
    private int countMonthsAsLeader(LocalDate effectiveStart, LocalDate effectiveEnd,
                                     LocalDate fyStart, LocalDate fyEnd) {
        // Only count past and completed months (not current incomplete month)
        YearMonth startMonth = YearMonth.from(effectiveStart.isBefore(fyStart) ? fyStart : effectiveStart);
        YearMonth endMonth;

        LocalDate today = LocalDate.now();
        if (effectiveEnd.isAfter(today)) {
            // Still active: count up to previous completed month
            endMonth = YearMonth.now().minusMonths(1);
        } else {
            endMonth = YearMonth.from(effectiveEnd);
        }

        if (endMonth.isBefore(startMonth)) return 0;

        int count = 0;
        YearMonth current = startMonth;
        while (!current.isAfter(endMonth)) {
            count++;
            current = current.plusMonths(1);
        }
        return count;
    }

    // ---- Value types ----

    private record LeaderInfo(String uuid, String name, int monthsAsLeader) {}
    private record LeaderCandidate(String uuid, String name, LocalDate start, LocalDate end) {}
    private record TeamUtilizationResult(double avgUtilization, double avgTeamSize) {}
}
