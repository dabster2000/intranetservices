package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.calculator.TeamleadBonusMath;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.AllTeamsBonusRankingDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.PoolBasisBreakdown;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamBonusProjectionDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamBonusProjectionDTO.AdjustmentsSummary;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamBonusProjectionDTO.MonthlyUtilization;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamBonusProjectionDTO.PoolBonusDetail;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamBonusProjectionDTO.PoolInfo;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamBonusProjectionDTO.ProductionBonusDetail;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadBonusConfigDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadMonthlyDetailDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadMonthlyDetailDTO.MemberDetail;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadMonthlyDetailDTO.MonthDetail;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadAdjustmentType;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusAdjustment;
import dk.trustworks.intranet.domain.user.entity.Team;
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
import java.util.*;

/**
 * Read-only query service (CQRS query side) for team-lead bonus projections. All constants are now
 * config-driven ({@link TeamleadBonusConfigService}); the pool basis comes from
 * {@link TeamleadOverskudService}; admin util-overrides and split/prepaid adjustments are applied
 * consistently across the per-team calculation, the Σpoints denominator and the team-dashboard tab.
 *
 * <p>The pure formulas live in {@link TeamleadBonusMath}; this class only assembles inputs (SQL,
 * temporal leader resolution) and rounds outputs for display.</p>
 *
 * @see TeamBonusProjectionDTO
 */
@JBossLog
@ApplicationScoped
public class TeamBonusProjectionService {

    @Inject
    EntityManager em;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    TeamleadBonusConfigService configService;

    @Inject
    TeamleadOverskudService overskudService;

    @Inject
    PartnerBonusPayoutService partnerBonusPayoutService;

    // =====================================================================
    // Access control
    // =====================================================================

    /**
     * Validates that the requesting user is a LEADER or SPONSOR of the specified team.
     *
     * @param teamId the team UUID to check
     * @throws ForbiddenException if the requester is not a leader or sponsor of the team
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
        long accessCount = TeamRole.count(
                "teamuuid = ?1 AND useruuid = ?2 AND teammembertype IN (?3, ?4) " +
                        "AND startdate <= ?5 AND (enddate > ?5 OR enddate IS NULL)",
                teamId, requestedBy, TeamMemberType.LEADER, TeamMemberType.SPONSOR, today
        );
        if (accessCount == 0) {
            throw new ForbiddenException("User is not a leader or sponsor of team " + teamId);
        }
    }

    // =====================================================================
    // Public API
    // =====================================================================

    /**
     * Calculates the full bonus projection for a specific team leader in a fiscal year, including
     * the admin adjustments summary and pool-basis provenance.
     *
     * @param teamId     the team UUID
     * @param fiscalYear the fiscal year start year (e.g., 2025 for FY 2025-07-01 to 2026-06-30)
     */
    public TeamBonusProjectionDTO getBonusProjection(String teamId, int fiscalYear) {
        Team team = Team.findById(teamId);
        if (team == null) {
            throw new NotFoundException("Team not found: " + teamId);
        }

        TeamleadContext ctx = buildContext(fiscalYear);
        LeaderBonusRow row = computeLeaderRow(team, ctx);

        PoolBonusDetail poolBonus = new PoolBonusDetail(
                row.teamAvgUtilization(),
                row.utilAboveMin(),
                row.avgTeamSize(),
                row.teamFactor(),
                row.rawPoints(),
                round(row.pricePerPoint(), 2),
                row.poolShare(),
                row.monthsAsLeader(),
                row.adjustedPoolBonus(),
                row.monthlyUtilization());

        ProductionBonusDetail productionBonus = new ProductionBonusDetail(
                row.ownRevenue(),
                row.proratedThreshold(),
                row.productionBonus(),
                row.annualizedRevenue());

        double combinedBonus = round(row.adjustedPoolBonus() + row.productionBonus(), 2);

        AdjustmentsSummary adjustments = new AdjustmentsSummary(
                row.splitBonus(),
                row.prepaidAuto(),
                row.prepaidManual(),
                row.utilOverridden(),
                row.utilOverrideNote(),
                row.totalBonus());

        PoolInfo poolInfo = new PoolInfo(
                ctx.poolBasis().poolBasis(),
                round(ctx.poolAmount(), 2),
                ctx.poolBasis().basisSource());

        return new TeamBonusProjectionDTO(
                fiscalYear,
                teamId,
                team.getName(),
                row.leaderUuid(),
                row.leaderName(),
                poolBonus,
                productionBonus,
                combinedBonus,
                null,
                adjustments,
                poolInfo);
    }

    /**
     * Returns bonus ranking data for all bonus-eligible teams, sorted by rawPoints descending. Uses
     * the effective config and admin util-overrides.
     */
    public List<AllTeamsBonusRankingDTO> getAllTeamsBonusRanking(String currentTeamId, int fiscalYear) {
        List<Team> bonusTeams = Team.list("teamleadbonus = true");

        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd = LocalDate.of(fiscalYear + 1, 6, 30);
        List<YearMonth> consideredMonths = getConsideredMonths(fyStart, fyEnd);
        TeamleadBonusConfigDTO config = configService.getEffectiveConfig(fiscalYear);
        Map<String, AdjustmentAggregate> adjustments = loadAdjustments(fiscalYear);

        if (consideredMonths.isEmpty()) {
            return bonusTeams.stream()
                    .map(t -> new AllTeamsBonusRankingDTO(
                            t.getUuid(), t.getName(), findLeaderNameForTeam(t.getUuid(), fyStart, fyEnd),
                            0.0, config.teamFactorTier1(), 0.0, 0, t.getUuid().equals(currentTeamId)))
                    .sorted(Comparator.comparing(AllTeamsBonusRankingDTO::teamName))
                    .toList();
        }

        List<AllTeamsBonusRankingDTO> rankings = new ArrayList<>();
        for (Team team : bonusTeams) {
            TeamPoints tp = computeTeamPoints(team, config, consideredMonths, fyStart, fyEnd, adjustments);
            rankings.add(new AllTeamsBonusRankingDTO(
                    team.getUuid(),
                    team.getName(),
                    tp.leader().name(),
                    round(tp.rawPoints(), 6),
                    tp.teamFactor(),
                    round(tp.teamUtil(), 4),
                    (int) Math.round(tp.avgTeamSize()),
                    team.getUuid().equals(currentTeamId)));
        }

        rankings.sort(Comparator.comparingDouble(AllTeamsBonusRankingDTO::rawPoints).reversed());
        return rankings;
    }

    /**
     * Read-only month-by-month drill-down of the utilization inputs for one team, for admins to
     * validate the collapsed dashboard figures. For every considered fiscal-year month it returns the
     * per-member utilization rows using EXACTLY the same predicates as {@link #calculateMonthlyUtilization}
     * (dated MEMBER teamroles, {@code CONSULTANT}/{@code ACTIVE}, leader-excluded), so the members sum
     * 1:1 to the team totals the bonus math consumes. Each month also carries the leader holding the
     * LEADER role for most of that month (null if none) and that leader's own registered revenue.
     *
     * @param teamId     the team UUID
     * @param fiscalYear the fiscal year start year (e.g., 2025 for FY 2025-07-01 to 2026-06-30)
     * @throws NotFoundException if the team does not exist
     */
    public TeamleadMonthlyDetailDTO getMonthlyDetail(String teamId, int fiscalYear) {
        Team team = Team.findById(teamId);
        if (team == null) {
            throw new NotFoundException("Team not found: " + teamId);
        }

        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd = LocalDate.of(fiscalYear + 1, 6, 30);
        List<YearMonth> months = getConsideredMonths(fyStart, fyEnd);
        if (months.isEmpty()) {
            return new TeamleadMonthlyDetailDTO(team.getUuid(), team.getName(), fiscalYear, List.of());
        }

        List<String> monthKeys = months.stream().map(TeamBonusProjectionService::monthKey).toList();

        Map<String, List<MemberDetail>> membersByMonth = loadMemberDetails(teamId, monthKeys);
        List<LeaderPeriod> leaderPeriods = loadLeaderPeriods(teamId, fyStart, fyEnd);

        Map<String, LeaderPeriod> leaderByMonth = new LinkedHashMap<>();
        for (YearMonth ym : months) {
            LeaderPeriod leader = resolveLeaderOfMonth(ym, leaderPeriods);
            if (leader != null) {
                leaderByMonth.put(monthKey(ym), leader);
            }
        }

        Set<String> leaderUuids = new HashSet<>();
        for (LeaderPeriod leader : leaderByMonth.values()) {
            leaderUuids.add(leader.uuid());
        }
        Map<String, Double> leaderRevenueByMonthUser = loadLeaderMonthlyRevenue(leaderUuids, monthKeys);

        List<MonthDetail> monthDetails = new ArrayList<>();
        for (YearMonth ym : months) {
            String key = monthKey(ym);
            List<MemberDetail> members = membersByMonth.getOrDefault(key, List.of());

            double teamBillable = round(members.stream().mapToDouble(MemberDetail::billableHours).sum(), 2);
            double teamAvailable = round(members.stream().mapToDouble(MemberDetail::availableHours).sum(), 2);
            Double teamUtil = utilizationOrNull(teamBillable, teamAvailable);

            LeaderPeriod leader = leaderByMonth.get(key);
            String leaderUuid = leader != null ? leader.uuid() : null;
            String leaderName = leader != null ? leader.name() : null;
            double leaderRevenue = leader != null
                    ? leaderRevenueByMonthUser.getOrDefault(key + "|" + leader.uuid(), 0.0)
                    : 0.0;

            monthDetails.add(new MonthDetail(
                    key, members.size(), teamBillable, teamAvailable, teamUtil,
                    leaderUuid, leaderName, round(leaderRevenue, 2), members));
        }

        return new TeamleadMonthlyDetailDTO(team.getUuid(), team.getName(), fiscalYear, monthDetails);
    }

    // =====================================================================
    // Shared computation (used by both the tab and the admin dashboard)
    // =====================================================================

    /**
     * Builds the fiscal-year-wide context shared by every leader row: effective config, considered
     * months, pool basis (Overskud), total pool amount, Σpoints across all teamleadbonus teams, the
     * derived price-per-point, and the admin adjustments keyed by leader UUID.
     */
    public TeamleadContext buildContext(int fiscalYear) {
        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd = LocalDate.of(fiscalYear + 1, 6, 30);
        List<YearMonth> consideredMonths = getConsideredMonths(fyStart, fyEnd);
        TeamleadBonusConfigDTO config = configService.getEffectiveConfig(fiscalYear);
        Map<String, AdjustmentAggregate> adjustments = loadAdjustments(fiscalYear);

        LocalDate consideredEnd = consideredMonths.isEmpty()
                ? fyStart.minusDays(1)
                : consideredMonths.getLast().atEndOfMonth();
        double teamRevenue = consideredMonths.isEmpty() ? 0.0 : calculateCompanyRevenue(fyStart, consideredEnd);

        PoolBasisBreakdown poolBasis = overskudService.computePoolBasis(fiscalYear, teamRevenue, config, consideredMonths);
        double poolAmount = TeamleadBonusMath.poolAmount(poolBasis.poolBasis(), config.poolSharePercent());
        double sumRawPoints = calculateSumRawPoints(consideredMonths, config, fyStart, fyEnd, adjustments);
        double pricePerPoint = TeamleadBonusMath.pricePerPoint(poolAmount, sumRawPoints);

        return new TeamleadContext(fiscalYear, fyStart, fyEnd, consideredMonths, config,
                poolBasis, poolAmount, sumRawPoints, pricePerPoint, adjustments);
    }

    /**
     * Computes the full per-team leader bonus row (pool + production + adjustments) using the shared
     * {@code ctx}. All monetary/ratio fields are rounded for display.
     */
    public LeaderBonusRow computeLeaderRow(Team team, TeamleadContext ctx) {
        TeamPoints tp = computeTeamPoints(team, ctx.config(), ctx.consideredMonths(), ctx.fyStart(), ctx.fyEnd(),
                ctx.adjustmentsByLeader());
        LeaderInfo leader = tp.leader();
        AdjustmentAggregate adj = ctx.adjustmentsByLeader().get(leader.uuid());

        double utilAboveMin = Math.max(tp.teamUtil() - ctx.config().minUtilThreshold(), 0.0);
        double poolShare = TeamleadBonusMath.poolShare(tp.rawPoints(), ctx.pricePerPoint());
        double adjustedPoolBonus = TeamleadBonusMath.adjustedPoolBonus(poolShare, leader.monthsAsLeader());

        double ownRevenue = calculateLeaderOwnRevenue(leader.uuid(), ctx.fyStart(), ctx.fyEnd());
        double proratedThreshold = TeamleadBonusMath.proratedThreshold(
                ctx.config().productionThresholdAnnual(), leader.monthsAsLeader());
        double productionBonus = TeamleadBonusMath.productionBonus(
                ownRevenue, proratedThreshold, ctx.config().productionCommissionPercent());
        double annualizedRevenue = ctx.consideredMonths().isEmpty()
                ? 0.0
                : (ownRevenue / ctx.consideredMonths().size()) * 12.0;

        double splitBonus = adj != null ? adj.splitBonus() : 0.0;
        double prepaidManual = adj != null ? adj.prepaidManual() : 0.0;
        double prepaidAuto = isUnknownLeader(leader)
                ? 0.0
                : partnerBonusPayoutService.calculatePrepaidBonuses(leader.uuid(), ctx.fiscalYear());

        double rPool = round(adjustedPoolBonus, 2);
        double rProd = round(productionBonus, 2);
        double rSplit = round(splitBonus, 2);
        double rPrepaidAuto = round(prepaidAuto, 2);
        double rPrepaidManual = round(prepaidManual, 2);
        double total = round(TeamleadBonusMath.totalBonus(rPool, rProd, rSplit, rPrepaidAuto + rPrepaidManual), 2);

        return new LeaderBonusRow(
                team.getUuid(),
                team.getName(),
                leader.uuid(),
                leader.name(),
                leader.monthsAsLeader(),
                round(tp.teamUtil(), 4),
                round(utilAboveMin, 4),
                tp.overridden(),
                adj != null ? adj.utilOverrideNote() : null,
                round(tp.avgTeamSize(), 1),
                tp.teamFactor(),
                round(tp.rawPoints(), 6),
                ctx.pricePerPoint(),
                round(poolShare, 2),
                rPool,
                round(ownRevenue, 2),
                round(proratedThreshold, 2),
                rProd,
                round(annualizedRevenue, 2),
                rSplit,
                rPrepaidAuto,
                rPrepaidManual,
                total,
                tp.monthlyUtilization());
    }

    // ---- adjustments ----

    /** Loads and aggregates admin adjustments for the FY, keyed by leader (user) UUID. */
    private Map<String, AdjustmentAggregate> loadAdjustments(int fiscalYear) {
        Map<String, Double> splitByUser = new HashMap<>();
        Map<String, Double> prepaidByUser = new HashMap<>();
        Map<String, Double> utilOverrideByUser = new HashMap<>();
        Map<String, String> utilNoteByUser = new HashMap<>();

        for (TeamleadBonusAdjustment a : TeamleadBonusAdjustment.listByFiscalYear(fiscalYear)) {
            if (a.adjustmentType == TeamleadAdjustmentType.SPLIT_BONUS) {
                splitByUser.merge(a.useruuid, a.amount != null ? a.amount : 0.0, Double::sum);
            } else if (a.adjustmentType == TeamleadAdjustmentType.PREPAID_DEDUCTION) {
                prepaidByUser.merge(a.useruuid, a.amount != null ? a.amount : 0.0, Double::sum);
            } else if (a.adjustmentType == TeamleadAdjustmentType.UTIL_OVERRIDE && a.utilOverride != null) {
                utilOverrideByUser.put(a.useruuid, a.utilOverride);
                utilNoteByUser.put(a.useruuid, a.note);
            }
        }

        Set<String> users = new HashSet<>();
        users.addAll(splitByUser.keySet());
        users.addAll(prepaidByUser.keySet());
        users.addAll(utilOverrideByUser.keySet());

        Map<String, AdjustmentAggregate> result = new HashMap<>();
        for (String user : users) {
            result.put(user, new AdjustmentAggregate(
                    utilOverrideByUser.get(user),
                    utilNoteByUser.get(user),
                    splitByUser.getOrDefault(user, 0.0),
                    prepaidByUser.getOrDefault(user, 0.0)));
        }
        return result;
    }

    // =====================================================================
    // Private calculation
    // =====================================================================

    /**
     * Considered months: completed months within the fiscal year (the current incomplete month is
     * excluded). A still-running FY caps at the previous completed month.
     */
    private List<YearMonth> getConsideredMonths(LocalDate fyStart, LocalDate fyEnd) {
        YearMonth start = YearMonth.from(fyStart);
        YearMonth lastConsidered = fyEnd.isAfter(LocalDate.now())
                ? YearMonth.now().minusMonths(1)
                : YearMonth.from(fyEnd);

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
     * Computes the raw points and intermediate values for one team, applying the leader's admin
     * util-override when present.
     */
    private TeamPoints computeTeamPoints(Team team, TeamleadBonusConfigDTO config, List<YearMonth> consideredMonths,
                                         LocalDate fyStart, LocalDate fyEnd,
                                         Map<String, AdjustmentAggregate> adjustments) {
        LeaderInfo leader = findLeaderForTeam(team.getUuid(), fyStart, fyEnd);
        List<MonthlyUtilization> monthlyUtil = calculateMonthlyUtilization(team.getUuid(), consideredMonths);
        TeamUtilizationResult utilResult = aggregateUtilization(monthlyUtil);

        AdjustmentAggregate adj = adjustments.get(leader.uuid());
        boolean overridden = adj != null && adj.utilOverride() != null;
        double teamUtil = overridden ? adj.utilOverride() : utilResult.avgUtilization();

        double teamFactor = computeTeamFactor(utilResult.avgTeamSize(), config);
        double rawPoints = TeamleadBonusMath.rawPoints(teamUtil, config.minUtilThreshold(), teamFactor);

        return new TeamPoints(leader, monthlyUtil, utilResult.avgTeamSize(), teamUtil, overridden, teamFactor, rawPoints);
    }

    /**
     * Monthly utilization for a team over the considered months, from {@code fact_user_day} joined
     * to teamroles MEMBER. Rows of users who additionally hold an active LEADER role on the SAME
     * team that day are excluded (guards against dual MEMBER+LEADER rows).
     */
    private List<MonthlyUtilization> calculateMonthlyUtilization(String teamId, List<YearMonth> months) {
        if (months.isEmpty()) return List.of();

        List<String> monthKeys = months.stream()
                .map(ym -> String.format("%04d%02d", ym.getYear(), ym.getMonthValue()))
                .toList();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT CONCAT(LPAD(fud.year, 4, '0'), LPAD(fud.month, 2, '0')) AS month_key,
                       SUM(fud.registered_billable_hours) AS total_billable,
                       SUM(fud.net_available_hours) AS total_available,
                       COUNT(DISTINCT fud.useruuid) AS member_count
                FROM fact_user_day fud
                JOIN teamroles tr ON tr.useruuid = fud.useruuid
                    AND tr.teamuuid = :teamId
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= fud.document_date
                    AND (tr.enddate IS NULL OR tr.enddate > fud.document_date)
                WHERE fud.consultant_type = 'CONSULTANT' AND fud.status_type = 'ACTIVE'
                  AND CONCAT(LPAD(fud.year, 4, '0'), LPAD(fud.month, 2, '0')) IN (:monthKeys)
                  AND NOT EXISTS (
                        SELECT 1 FROM teamroles ldr
                        WHERE ldr.useruuid = fud.useruuid
                          AND ldr.teamuuid = :teamId
                          AND ldr.membertype = 'LEADER'
                          AND ldr.startdate <= fud.document_date
                          AND (ldr.enddate IS NULL OR ldr.enddate > fud.document_date)
                  )
                GROUP BY fud.year, fud.month
                ORDER BY fud.year, fud.month
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
            String formattedMonth = monthKey.substring(0, 4) + "-" + monthKey.substring(4);
            return new MonthlyUtilization(formattedMonth, utilization, memberCount);
        }).toList();
    }

    /**
     * Aggregates monthly utilization into equal-weight team averages (KEEP equal-weight per spec).
     * Team size is the average active member count over non-empty months.
     */
    private TeamUtilizationResult aggregateUtilization(List<MonthlyUtilization> monthlyUtil) {
        if (monthlyUtil.isEmpty()) {
            return new TeamUtilizationResult(0.0, 0.0);
        }

        double sumUtil = monthlyUtil.stream().mapToDouble(MonthlyUtilization::utilization).sum();
        double avgUtil = sumUtil / monthlyUtil.size();

        List<MonthlyUtilization> nonEmptyMonths = monthlyUtil.stream()
                .filter(m -> m.memberCount() > 0)
                .toList();
        double avgTeamSize = nonEmptyMonths.isEmpty() ? 0.0 :
                nonEmptyMonths.stream().mapToInt(MonthlyUtilization::memberCount).average().orElse(0.0);

        return new TeamUtilizationResult(avgUtil, avgTeamSize);
    }

    /** Config-driven team factor bracket. */
    private double computeTeamFactor(double avgTeamSize, TeamleadBonusConfigDTO config) {
        return TeamleadBonusMath.teamFactor(avgTeamSize, config);
    }

    /** Σ raw points across all teamleadbonus teams (config-driven, util-overrides applied). */
    private double calculateSumRawPoints(List<YearMonth> consideredMonths, TeamleadBonusConfigDTO config,
                                         LocalDate fyStart, LocalDate fyEnd,
                                         Map<String, AdjustmentAggregate> adjustments) {
        List<Team> bonusTeams = Team.list("teamleadbonus = true");
        double sumPoints = 0.0;
        for (Team team : bonusTeams) {
            sumPoints += computeTeamPoints(team, config, consideredMonths, fyStart, fyEnd, adjustments).rawPoints();
        }
        return sumPoints;
    }

    /**
     * Registered revenue of teamleadbonus-team MEMBERs over the considered window, EXCLUDING rows of
     * users who hold an active LEADER role on ANY teamleadbonus team that day (the pool excludes
     * teamleads' own production). Feeds the pool-basis estimate as {@code teamRevenue}.
     */
    private double calculateCompanyRevenue(LocalDate from, LocalDate to) {
        Object result = em.createNativeQuery("""
                SELECT COALESCE(SUM(fud.registered_amount), 0)
                FROM fact_user_day fud
                JOIN teamroles tr ON tr.useruuid = fud.useruuid
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= fud.document_date
                    AND (tr.enddate > fud.document_date OR tr.enddate IS NULL)
                JOIN team t ON t.uuid = tr.teamuuid
                    AND t.teamleadbonus = 1
                WHERE fud.document_date >= :from
                  AND fud.document_date <= :to
                  AND fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type = 'ACTIVE'
                  AND NOT EXISTS (
                        SELECT 1 FROM teamroles ldr
                        JOIN team lt ON lt.uuid = ldr.teamuuid AND lt.teamleadbonus = 1
                        WHERE ldr.useruuid = fud.useruuid
                          AND ldr.membertype = 'LEADER'
                          AND ldr.startdate <= fud.document_date
                          AND (ldr.enddate IS NULL OR ldr.enddate > fud.document_date)
                  )
                """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return ((Number) result).doubleValue();
    }

    /** A leader's own billable revenue within the fiscal year. */
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

    // =====================================================================
    // Monthly drill-down queries (validate-inputs view)
    // =====================================================================

    /**
     * Per-member utilization rows for a team over the given months, keyed by month. Uses EXACTLY the
     * predicates of {@link #calculateMonthlyUtilization} (dated MEMBER teamroles, {@code CONSULTANT}/
     * {@code ACTIVE}, leader-excluded via {@code NOT EXISTS}) but additionally grouped per user, so the
     * per-user rows sum back to the collapsed team totals. Hours are display-rounded to 2 decimals.
     */
    private Map<String, List<MemberDetail>> loadMemberDetails(String teamId, List<String> monthKeys) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT CONCAT(LPAD(fud.year, 4, '0'), LPAD(fud.month, 2, '0')) AS month_key,
                       fud.useruuid AS useruuid,
                       u.firstname AS firstname,
                       u.lastname AS lastname,
                       SUM(fud.registered_billable_hours) AS billable,
                       SUM(fud.net_available_hours) AS available
                FROM fact_user_day fud
                JOIN teamroles tr ON tr.useruuid = fud.useruuid
                    AND tr.teamuuid = :teamId
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= fud.document_date
                    AND (tr.enddate IS NULL OR tr.enddate > fud.document_date)
                JOIN user u ON u.uuid = fud.useruuid
                WHERE fud.consultant_type = 'CONSULTANT' AND fud.status_type = 'ACTIVE'
                  AND CONCAT(LPAD(fud.year, 4, '0'), LPAD(fud.month, 2, '0')) IN (:monthKeys)
                  AND NOT EXISTS (
                        SELECT 1 FROM teamroles ldr
                        WHERE ldr.useruuid = fud.useruuid
                          AND ldr.teamuuid = :teamId
                          AND ldr.membertype = 'LEADER'
                          AND ldr.startdate <= fud.document_date
                          AND (ldr.enddate IS NULL OR ldr.enddate > fud.document_date)
                  )
                GROUP BY fud.year, fud.month, fud.useruuid, u.firstname, u.lastname
                ORDER BY fud.year, fud.month, u.lastname, u.firstname
                """)
                .setParameter("teamId", teamId)
                .setParameter("monthKeys", monthKeys)
                .getResultList();

        Map<String, List<MemberDetail>> byMonth = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String monthKey = (String) row[0];
            String useruuid = (String) row[1];
            String firstName = (String) row[2];
            String lastName = (String) row[3];
            double billable = round(((Number) row[4]).doubleValue(), 2);
            double available = round(((Number) row[5]).doubleValue(), 2);
            MemberDetail member = new MemberDetail(
                    useruuid, fullName(firstName, lastName),
                    billable, available, utilizationOrNull(billable, available));
            byMonth.computeIfAbsent(monthKey, k -> new ArrayList<>()).add(member);
        }
        return byMonth;
    }

    /**
     * All LEADER teamroles for a team overlapping the fiscal-year window, as pure {@link LeaderPeriod}
     * value objects (end is exclusive, {@code null} for open-ended). The per-month winner is resolved
     * in-memory by {@link #resolveLeaderOfMonth}.
     */
    private List<LeaderPeriod> loadLeaderPeriods(String teamId, LocalDate fyStart, LocalDate fyEnd) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT tr.useruuid, u.firstname, u.lastname, tr.startdate, tr.enddate
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

        List<LeaderPeriod> periods = new ArrayList<>();
        for (Object[] row : rows) {
            String uuid = (String) row[0];
            String name = fullName((String) row[1], (String) row[2]);
            LocalDate start = toLocalDate(row[3]);
            LocalDate endExclusive = row[4] != null ? toLocalDate(row[4]) : null;
            periods.add(new LeaderPeriod(uuid, name, start, endExclusive));
        }
        return periods;
    }

    /**
     * A leader's own registered revenue per month (same {@code CONSULTANT}/{@code ACTIVE} filter as
     * {@link #calculateLeaderOwnRevenue}), keyed by {@code "<monthKey>|<useruuid>"}. Returns an empty
     * map when no month has a resolved leader.
     */
    private Map<String, Double> loadLeaderMonthlyRevenue(Set<String> leaderUuids, List<String> monthKeys) {
        if (leaderUuids.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT CONCAT(LPAD(fud.year, 4, '0'), LPAD(fud.month, 2, '0')) AS month_key,
                       fud.useruuid AS useruuid,
                       COALESCE(SUM(fud.registered_amount), 0) AS revenue
                FROM fact_user_day fud
                WHERE fud.useruuid IN (:leaderUuids)
                  AND CONCAT(LPAD(fud.year, 4, '0'), LPAD(fud.month, 2, '0')) IN (:monthKeys)
                  AND fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type = 'ACTIVE'
                GROUP BY fud.year, fud.month, fud.useruuid
                """)
                .setParameter("leaderUuids", leaderUuids)
                .setParameter("monthKeys", monthKeys)
                .getResultList();

        Map<String, Double> byMonthUser = new HashMap<>();
        for (Object[] row : rows) {
            String monthKey = (String) row[0];
            String useruuid = (String) row[1];
            double revenue = ((Number) row[2]).doubleValue();
            byMonthUser.put(monthKey + "|" + useruuid, revenue);
        }
        return byMonthUser;
    }

    /**
     * Finds the primary leader for a team during a fiscal year (longest tenure), clipping the LEADER
     * period to the FY and counting whole months (partial month = 1, capped at last completed month).
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
            return new LeaderInfo("unknown", "Unknown Leader", 0);
        }

        Map<String, LeaderCandidate> candidates = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String uuid = (String) row[0];
            String firstName = (String) row[1];
            String lastName = (String) row[2];
            LocalDate roleStart = toLocalDate(row[3]);
            LocalDate roleEnd = row[4] != null ? toLocalDate(row[4]) : fyEnd;

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

        LeaderCandidate best = candidates.values().stream()
                .max(Comparator.comparingLong(c -> java.time.temporal.ChronoUnit.DAYS.between(c.start(), c.end())))
                .orElseThrow();

        int monthsAsLeader = countMonthsAsLeader(best.start(), best.end());
        return new LeaderInfo(best.uuid(), best.name(), monthsAsLeader);
    }

    /** Leader name for a team (simplified lookup for ranking). */
    private String findLeaderNameForTeam(String teamId, LocalDate fyStart, LocalDate fyEnd) {
        return findLeaderForTeam(teamId, fyStart, fyEnd).name();
    }

    /** Whole months the leader held the role within the FY (partial month = 1, current month excluded). */
    private int countMonthsAsLeader(LocalDate effectiveStart, LocalDate effectiveEnd) {
        YearMonth startMonth = YearMonth.from(effectiveStart);
        YearMonth endMonth;

        LocalDate today = LocalDate.now();
        if (effectiveEnd.isAfter(today)) {
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

    // =====================================================================
    // Dual-leadership guards
    // =====================================================================
    // The spec assumes one leader per teamleadbonus team, but nothing in the data model prevents a
    // user from holding LEADER roles on TWO such teams in the same FY. Per-user components
    // (production bonus, split bonus, prepaid auto/manual) are keyed by user, so they must only ever
    // count ONCE across that user's team rows — both in the admin dashboard totals and in a payout.

    /**
     * Deterministic primary row among one user's team rows: longest leadership tenure, tie-broken by
     * teamId. The per-user components are attached to this row only.
     */
    public static LeaderBonusRow selectPrimaryRow(List<LeaderBonusRow> rows) {
        return rows.stream()
                .max(Comparator.comparingInt(LeaderBonusRow::monthsAsLeader)
                        .thenComparing(LeaderBonusRow::teamId))
                .orElseThrow();
    }

    /**
     * Copy of {@code row} with the per-user components (production, split, prepaid) zeroed and
     * {@code totalBonus} reduced to the pool component. Used for a dual leader's secondary team rows.
     */
    public static LeaderBonusRow stripPerUserComponents(LeaderBonusRow row) {
        return new LeaderBonusRow(
                row.teamId(), row.teamName(), row.leaderUuid(), row.leaderName(),
                row.monthsAsLeader(), row.teamAvgUtilization(), row.utilAboveMin(),
                row.utilOverridden(), row.utilOverrideNote(), row.avgTeamSize(),
                row.teamFactor(), row.rawPoints(), row.pricePerPoint(), row.poolShare(),
                row.adjustedPoolBonus(), row.ownRevenue(), row.proratedThreshold(),
                0.0,                     // productionBonus — counted once, on the primary row
                row.annualizedRevenue(),
                0.0, 0.0, 0.0,           // splitBonus, prepaidAuto, prepaidManual — idem
                row.adjustedPoolBonus(), // totalBonus = pool component only
                row.monthlyUtilization());
    }

    /**
     * Merges one user's team rows into a single payable row: pool components (points, pool share,
     * adjusted pool bonus) summed across all led teams; per-user components taken once from the
     * primary row. A single-row list is returned unchanged.
     */
    public static LeaderBonusRow combineLeaderRows(List<LeaderBonusRow> rows) {
        if (rows.size() == 1) return rows.getFirst();
        LeaderBonusRow primary = selectPrimaryRow(rows);
        double rawPoints = rows.stream().mapToDouble(LeaderBonusRow::rawPoints).sum();
        double poolShare = rows.stream().mapToDouble(LeaderBonusRow::poolShare).sum();
        double adjustedPoolBonus = round(rows.stream().mapToDouble(LeaderBonusRow::adjustedPoolBonus).sum(), 2);
        String teamName = rows.stream().map(LeaderBonusRow::teamName).sorted()
                .collect(java.util.stream.Collectors.joining(" + "));
        double total = round(TeamleadBonusMath.totalBonus(
                adjustedPoolBonus, primary.productionBonus(), primary.splitBonus(),
                primary.prepaidAuto() + primary.prepaidManual()), 2);
        return new LeaderBonusRow(
                primary.teamId(), teamName, primary.leaderUuid(), primary.leaderName(),
                primary.monthsAsLeader(), primary.teamAvgUtilization(), primary.utilAboveMin(),
                primary.utilOverridden(), primary.utilOverrideNote(), primary.avgTeamSize(),
                primary.teamFactor(), round(rawPoints, 6), primary.pricePerPoint(), round(poolShare, 2),
                adjustedPoolBonus, primary.ownRevenue(), primary.proratedThreshold(),
                primary.productionBonus(), primary.annualizedRevenue(), primary.splitBonus(),
                primary.prepaidAuto(), primary.prepaidManual(), total,
                primary.monthlyUtilization());
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static boolean isUnknownLeader(LeaderInfo leader) {
        return leader == null || "unknown".equals(leader.uuid());
    }

    /** Fiscal-month key in {@code YYYYMM} form, matching the {@code fact_user_day} month keys. */
    static String monthKey(YearMonth ym) {
        return String.format("%04d%02d", ym.getYear(), ym.getMonthValue());
    }

    /**
     * Utilization ratio {@code billable / available} rounded to 4 decimals (dashboard convention),
     * or {@code null} when the available-hours denominator is zero (graceful divide-by-zero guard).
     */
    static Double utilizationOrNull(double billable, double available) {
        if (available <= 0.0) {
            return null;
        }
        return round(billable / available, 4);
    }

    /**
     * Selects the leader whose LEADER role covers the most days of {@code month}, or {@code null} when
     * no role overlaps it. Overlap is computed against the leader period's half-open range
     * {@code [start, endExclusive)} clipped to the month; ties (e.g. two roles splitting a month
     * evenly) are broken deterministically by lowest UUID. Pure logic — unit-tested without a DB.
     */
    static LeaderPeriod resolveLeaderOfMonth(YearMonth month, List<LeaderPeriod> leaders) {
        LocalDate monthFirst = month.atDay(1);
        LocalDate monthEndExclusive = month.atEndOfMonth().plusDays(1);

        LeaderPeriod best = null;
        long bestDays = 0;
        for (LeaderPeriod lp : leaders) {
            LocalDate effStart = lp.start().isAfter(monthFirst) ? lp.start() : monthFirst;
            LocalDate effEndExclusive = lp.endExclusive() == null || lp.endExclusive().isAfter(monthEndExclusive)
                    ? monthEndExclusive
                    : lp.endExclusive();
            long days = java.time.temporal.ChronoUnit.DAYS.between(effStart, effEndExclusive);
            if (days <= 0) {
                continue;
            }
            boolean wins = days > bestDays
                    || (days == bestDays && best != null && lp.uuid().compareTo(best.uuid()) < 0);
            if (wins) {
                best = lp;
                bestDays = days;
            }
        }
        return best;
    }

    private static String fullName(String firstName, String lastName) {
        return ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(value.toString());
    }

    // =====================================================================
    // Value types
    // =====================================================================

    private record LeaderInfo(String uuid, String name, int monthsAsLeader) {}
    private record LeaderCandidate(String uuid, String name, LocalDate start, LocalDate end) {}

    /**
     * A LEADER teamrole as a pure value object for per-month leader resolution. {@code endExclusive} is
     * {@code null} for an open-ended role and otherwise the (exclusive) end date, matching the
     * {@code startdate <= day AND (enddate IS NULL OR enddate > day)} predicate used across this class.
     */
    public record LeaderPeriod(String uuid, String name, LocalDate start, LocalDate endExclusive) {}
    private record TeamUtilizationResult(double avgUtilization, double avgTeamSize) {}

    private record TeamPoints(LeaderInfo leader, List<MonthlyUtilization> monthlyUtilization,
                              double avgTeamSize, double teamUtil, boolean overridden,
                              double teamFactor, double rawPoints) {}

    /** Admin adjustments aggregated for one leader/FY. */
    public record AdjustmentAggregate(Double utilOverride, String utilOverrideNote,
                                      double splitBonus, double prepaidManual) {}

    /** Fiscal-year-wide computation context shared by every leader row. */
    public record TeamleadContext(int fiscalYear, LocalDate fyStart, LocalDate fyEnd,
                                  List<YearMonth> consideredMonths, TeamleadBonusConfigDTO config,
                                  PoolBasisBreakdown poolBasis, double poolAmount, double sumRawPoints,
                                  double pricePerPoint, Map<String, AdjustmentAggregate> adjustmentsByLeader) {}

    /** Fully-computed per-team leader bonus row (display-rounded). */
    public record LeaderBonusRow(String teamId, String teamName, String leaderUuid, String leaderName,
                                 int monthsAsLeader, double teamAvgUtilization, double utilAboveMin,
                                 boolean utilOverridden, String utilOverrideNote, double avgTeamSize,
                                 double teamFactor, double rawPoints, double pricePerPoint, double poolShare,
                                 double adjustedPoolBonus, double ownRevenue, double proratedThreshold,
                                 double productionBonus, double annualizedRevenue, double splitBonus,
                                 double prepaidAuto, double prepaidManual, double totalBonus,
                                 List<MonthlyUtilization> monthlyUtilization) {}
}
