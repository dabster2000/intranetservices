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
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusLeaderExclusion;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusMemberOverride;
import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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
 * {@link TeamleadOverskudService}; admin util-overrides, split/prepaid adjustments and — new in the
 * editable-calculation-sources feature — per-member inclusion overrides and per-leader exclusions
 * are applied consistently across the per-team calculation, the Σpoints denominator and the
 * team-dashboard tab.
 *
 * <p>The pure formulas (raw points, pool share, hybrid split, recomposed utilization) live in
 * {@link TeamleadBonusMath}; this class only assembles inputs (SQL, temporal leader resolution) and
 * rounds outputs for display.</p>
 *
 * <p>One grid row is emitted per (team, leader) — every user who held the LEADER role on a
 * teamleadbonus team during the FY — plus an {@code "unknown"} row for teams with no LEADER role.
 * The team's payable pool is split between its leaders by the hybrid rule in spec §4.</p>
 *
 * @see TeamBonusProjectionDTO
 */
@JBossLog
@ApplicationScoped
public class TeamBonusProjectionService {

    private static final String LEAVE_STATUS_SQL = "'MATERNITY_LEAVE','PAID_LEAVE','NON_PAY_LEAVE'";
    private static final String UNKNOWN_LEADER_UUID = "unknown";

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
     * the admin adjustments summary and pool-basis provenance. Compatibility path for the teamlead's
     * own tab: returns the team's primary (longest-attributed) leader row.
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
     * the effective config, admin util-overrides, member overrides (via the fact predicate) and
     * skips fully-excluded teams; the primary leader is shown as today.
     */
    public List<AllTeamsBonusRankingDTO> getAllTeamsBonusRanking(String currentTeamId, int fiscalYear) {
        List<Team> bonusTeams = Team.list("teamleadbonus = true");

        // Deliberately NOT buildContext(): the ranking consumes only points/factor/util/size and the
        // exclusion set, so skip the pool-basis (Overskud/OPEX/GL), company-revenue and Σpoints
        // machinery — this endpoint backs every teamlead's own tab and must stay cheap.
        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd = LocalDate.of(fiscalYear + 1, 6, 30);
        List<YearMonth> consideredMonths = getConsideredMonths(fyStart, fyEnd);
        TeamleadBonusConfigDTO config = configService.getEffectiveConfig(fiscalYear);
        Map<String, AdjustmentAggregate> adjustments = loadAdjustments(fiscalYear);
        Map<String, Map<String, String>> leaderExclusions = loadLeaderExclusions(fiscalYear);
        Set<String> fullyExcludedTeamIds = computeFullyExcludedTeams(leaderExclusions, fyStart, fyEnd);

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
            if (fullyExcludedTeamIds.contains(team.getUuid())) continue;
            TeamAggregate agg = computeTeamAggregate(team, consideredMonths, config, fyStart, fyEnd, adjustments);
            String leaderName = agg.leaderWindows().isEmpty()
                    ? findLeaderNameForTeam(team.getUuid(), fyStart, fyEnd)
                    : primaryWindow(agg.leaderWindows()).leaderName();
            rankings.add(new AllTeamsBonusRankingDTO(
                    team.getUuid(),
                    team.getName(),
                    leaderName,
                    round(agg.teamRawPoints(), 6),
                    agg.teamFactor(),
                    round(agg.recomposedTeamUtil(), 4),
                    (int) Math.round(agg.teamAvgSize()),
                    team.getUuid().equals(currentTeamId)));
        }

        rankings.sort(Comparator.comparingDouble(AllTeamsBonusRankingDTO::rawPoints).reversed());
        return rankings;
    }

    /**
     * Read-only month-by-month drill-down of the utilization inputs for one team, for admins to
     * validate the collapsed dashboard figures. For every considered fiscal-year month it returns the
     * per-member utilization rows using EXACTLY the same predicates as {@link #calculateMonthlyUtilization}
     * (dated MEMBER teamroles, {@code CONSULTANT}, leader-excluded, member-override applied), so the
     * <em>included</em> members sum 1:1 to the team totals the bonus math consumes. The roster now
     * also surfaces full-leave members (default-excluded, toggleable); members whose only rows are
     * {@code TERMINATED}/{@code PREBOARDING} stay hidden. Each month also carries the leader holding
     * the LEADER role for most of that month (null if none), that leader's own registered revenue and
     * whether that leader is excluded for the FY.
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

        Map<String, MemberOverrideInfo> teamOverrides = loadTeamMemberOverrides(teamId, monthKeys);
        Set<String> excludedLeaders = loadTeamLeaderExclusions(fiscalYear, teamId);

        Map<String, List<MemberDetail>> membersByMonth = loadMemberDetails(teamId, monthKeys, teamOverrides);
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
            List<MemberDetail> included = members.stream().filter(MemberDetail::includedInCalculation).toList();

            double teamBillable = round(included.stream().mapToDouble(MemberDetail::billableHours).sum(), 2);
            double teamAvailable = round(included.stream().mapToDouble(MemberDetail::availableHours).sum(), 2);
            Double teamUtil = utilizationOrNull(teamBillable, teamAvailable);

            LeaderPeriod leader = leaderByMonth.get(key);
            String leaderUuid = leader != null ? leader.uuid() : null;
            String leaderName = leader != null ? leader.name() : null;
            double leaderRevenue = leader != null
                    ? leaderRevenueByMonthUser.getOrDefault(key + "|" + leader.uuid(), 0.0)
                    : 0.0;
            boolean leaderExcluded = leader != null && excludedLeaders.contains(leader.uuid());

            monthDetails.add(new MonthDetail(
                    key, included.size(), teamBillable, teamAvailable, teamUtil,
                    leaderUuid, leaderName, round(leaderRevenue, 2), leaderExcluded, members));
        }

        return new TeamleadMonthlyDetailDTO(team.getUuid(), team.getName(), fiscalYear, monthDetails);
    }

    // =====================================================================
    // Shared computation (used by both the tab and the admin dashboard)
    // =====================================================================

    /**
     * Builds the fiscal-year-wide context shared by every leader row: effective config, considered
     * months, leader exclusions + the derived fully-excluded team set, pool basis (Overskud, with
     * disabled member-months and fully-excluded teams removed from its team-revenue and the excluded
     * teams' member salaries added back on the cost side), total pool amount, Σpoints across all
     * non-fully-excluded teamleadbonus teams, the derived price-per-point, and the admin adjustments
     * keyed by leader UUID.
     */
    public TeamleadContext buildContext(int fiscalYear) {
        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd = LocalDate.of(fiscalYear + 1, 6, 30);
        List<YearMonth> consideredMonths = getConsideredMonths(fyStart, fyEnd);
        TeamleadBonusConfigDTO config = configService.getEffectiveConfig(fiscalYear);
        Map<String, AdjustmentAggregate> adjustments = loadAdjustments(fiscalYear);

        Map<String, Map<String, String>> leaderExclusions = loadLeaderExclusions(fiscalYear);
        Set<String> fullyExcludedTeamIds = computeFullyExcludedTeams(leaderExclusions, fyStart, fyEnd);

        LocalDate consideredEnd = consideredMonths.isEmpty()
                ? fyStart.minusDays(1)
                : consideredMonths.getLast().atEndOfMonth();
        double teamRevenue = consideredMonths.isEmpty()
                ? 0.0
                : calculateCompanyRevenue(fyStart, consideredEnd, fullyExcludedTeamIds);

        PoolBasisBreakdown poolBasis = overskudService.computePoolBasis(fiscalYear, teamRevenue, config, consideredMonths, fullyExcludedTeamIds);
        double poolAmount = TeamleadBonusMath.poolAmount(poolBasis.poolBasis(), config.poolSharePercent());
        double sumRawPoints = calculateSumRawPoints(consideredMonths, config, fyStart, fyEnd, adjustments, fullyExcludedTeamIds);
        double pricePerPoint = TeamleadBonusMath.pricePerPoint(poolAmount, sumRawPoints);

        return new TeamleadContext(fiscalYear, fyStart, fyEnd, consideredMonths, config,
                poolBasis, poolAmount, sumRawPoints, pricePerPoint, adjustments,
                leaderExclusions, fullyExcludedTeamIds);
    }

    /**
     * Computes the per-team leader bonus rows (one per leader that held the LEADER role in the FY,
     * plus an {@code "unknown"} row for teams with none). The team's points stay computed on full-FY
     * performance (recomposed utilization); the payable pool is {@code teamPoolShare × coveredMonths/12}
     * and is split between the leaders by the hybrid rule (spec §4). Excluded leaders keep their
     * informational figures but zero every payable component.
     */
    public List<LeaderBonusRow> computeLeaderRows(Team team, TeamleadContext ctx) {
        TeamAggregate agg = computeTeamAggregate(team, ctx);
        double ppp = ctx.pricePerPoint();
        Map<String, String> exclusionNotes = ctx.leaderExclusionsByTeam().getOrDefault(team.getUuid(), Map.of());
        boolean teamFullyExcluded = ctx.fullyExcludedTeamIds().contains(team.getUuid());

        double teamPoolShare = TeamleadBonusMath.poolShare(agg.teamRawPoints(), ppp);
        double coveredFraction = (double) agg.coveredMonths() / TeamleadBonusMath.MONTHS_IN_YEAR;

        List<LeaderWindow> windows = agg.leaderWindows();
        if (windows.isEmpty()) {
            return List.of(buildUnknownRow(agg, ctx));
        }

        double[] weights = new double[windows.size()];
        int[] months = new int[windows.size()];
        for (int i = 0; i < windows.size(); i++) {
            weights[i] = windows.get(i).ownWindowPoints() * windows.get(i).monthsAsLeader();
            months[i] = windows.get(i).monthsAsLeader();
        }
        double[] slices = TeamleadBonusMath.hybridSlices(weights, months);

        List<LeaderBonusRow> rows = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) {
            LeaderWindow w = windows.get(i);
            boolean excluded = teamFullyExcluded || exclusionNotes.containsKey(w.leaderUuid());
            String excludedNote = excluded ? exclusionNotes.get(w.leaderUuid()) : null;
            rows.add(buildLeaderRow(agg, w, ctx, slices[i], teamPoolShare, coveredFraction,
                    excluded, excludedNote, teamFullyExcluded));
        }
        return rows;
    }

    /**
     * Compatibility helper for the teamlead's own tab: the primary (longest-attributed) leader row of
     * the team, tie-broken by leader UUID.
     */
    public LeaderBonusRow computeLeaderRow(Team team, TeamleadContext ctx) {
        return computeLeaderRows(team, ctx).stream()
                .max(Comparator.comparingInt(LeaderBonusRow::monthsAsLeader)
                        .thenComparing(LeaderBonusRow::leaderUuid))
                .orElseThrow();
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

    // ---- leader exclusions / fully-excluded teams ----

    /** Leader exclusions for the FY, keyed {@code teamuuid → (useruuid → note)}. */
    private Map<String, Map<String, String>> loadLeaderExclusions(int fiscalYear) {
        Map<String, Map<String, String>> result = new HashMap<>();
        for (TeamleadBonusLeaderExclusion e : TeamleadBonusLeaderExclusion.listByFiscalYear(fiscalYear)) {
            result.computeIfAbsent(e.teamuuid, k -> new HashMap<>()).put(e.useruuid, e.note);
        }
        return result;
    }

    /**
     * Teams whose every real leader (a user holding the LEADER role during the FY) is excluded. Teams
     * with no LEADER role at all (only an {@code "unknown"} leader) are never fully excluded.
     */
    private Set<String> computeFullyExcludedTeams(Map<String, Map<String, String>> leaderExclusions,
                                                  LocalDate fyStart, LocalDate fyEnd) {
        if (leaderExclusions.isEmpty()) return Set.of();
        Map<String, Set<String>> leadersByTeam = loadTeamLeaderUuids(fyStart, fyEnd);
        Set<String> fully = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : leadersByTeam.entrySet()) {
            Set<String> excluded = leaderExclusions.getOrDefault(e.getKey(), Map.of()).keySet();
            if (!e.getValue().isEmpty() && excluded.containsAll(e.getValue())) {
                fully.add(e.getKey());
            }
        }
        return fully;
    }

    /** Distinct LEADER user UUIDs per teamleadbonus team overlapping the FY window. */
    private Map<String, Set<String>> loadTeamLeaderUuids(LocalDate fyStart, LocalDate fyEnd) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT tr.teamuuid, tr.useruuid
                FROM teamroles tr
                JOIN team t ON t.uuid = tr.teamuuid AND t.teamleadbonus = 1
                WHERE tr.membertype = 'LEADER'
                  AND tr.startdate <= :fyEnd
                  AND (tr.enddate > :fyStart OR tr.enddate IS NULL)
                """)
                .setParameter("fyEnd", fyEnd)
                .setParameter("fyStart", fyStart)
                .getResultList();
        Map<String, Set<String>> result = new HashMap<>();
        for (Object[] r : rows) {
            result.computeIfAbsent((String) r[0], k -> new HashSet<>()).add((String) r[1]);
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

    private TeamAggregate computeTeamAggregate(Team team, TeamleadContext ctx) {
        return computeTeamAggregate(team, ctx.consideredMonths(), ctx.config(),
                ctx.fyStart(), ctx.fyEnd(), ctx.adjustmentsByLeader());
    }

    /**
     * Computes the team-level aggregate for the hybrid split: the recomposed FY utilization (spec §7)
     * and full-FY {@code teamRawPoints}, plus one leader window per real leader (own-window effective
     * utilization, own-window average size/factor and own-window points). Member overrides are applied
     * inside {@link #calculateMonthlyUtilization} via SQL; util-overrides are applied per leader window.
     */
    private TeamAggregate computeTeamAggregate(Team team, List<YearMonth> consideredMonths,
                                               TeamleadBonusConfigDTO config, LocalDate fyStart, LocalDate fyEnd,
                                               Map<String, AdjustmentAggregate> adjustments) {
        String teamId = team.getUuid();
        List<MonthUtilRow> utilRows = calculateMonthlyUtilization(teamId, consideredMonths);

        // Months carrying member data, keyed by YearMonth (row month is "YYYY-MM", ISO-parseable).
        // A row without ACTIVE data (only force-included leave members) contributes headcount to the
        // size averages but must NEVER contribute a utilization observation (spec §2: enabling a
        // full-leave member leaves utilization untouched) — pre-override such months produced no row.
        Map<YearMonth, MonthUtilRow> dataByMonth = new LinkedHashMap<>();
        for (MonthUtilRow mu : utilRows) {
            dataByMonth.put(YearMonth.parse(mu.month()), mu);
        }

        // Display list for the own-tab DTO: exactly the months with ACTIVE data (old behavior).
        List<MonthlyUtilization> monthlyUtil = utilRows.stream()
                .filter(MonthUtilRow::hasActiveData)
                .map(mu -> new MonthlyUtilization(mu.month(), mu.utilization(), mu.memberCount()))
                .toList();

        List<LeaderPeriod> leaderPeriods = loadLeaderPeriods(teamId, fyStart, fyEnd);

        // Distinct real leaders in first-appearance order (stable output).
        LinkedHashMap<String, String> leaderNames = new LinkedHashMap<>();
        for (LeaderPeriod lp : leaderPeriods) {
            leaderNames.putIfAbsent(lp.uuid(), lp.name());
        }

        // Month attribution (majority rule) over the considered months.
        Map<String, Integer> monthsAsLeader = new LinkedHashMap<>();
        Map<String, List<String>> attributedMonthKeys = new LinkedHashMap<>();
        Map<String, List<YearMonth>> dataMonthsByLeader = new LinkedHashMap<>();
        List<YearMonth> unassignedDataMonths = new ArrayList<>();
        int coveredMonths = 0;

        for (YearMonth ym : consideredMonths) {
            LeaderPeriod leader = resolveLeaderOfMonth(ym, leaderPeriods);
            if (leader != null) {
                String uuid = leader.uuid();
                monthsAsLeader.merge(uuid, 1, Integer::sum);
                attributedMonthKeys.computeIfAbsent(uuid, k -> new ArrayList<>()).add(monthKey(ym));
                coveredMonths++;
                if (dataByMonth.containsKey(ym)) {
                    dataMonthsByLeader.computeIfAbsent(uuid, k -> new ArrayList<>()).add(ym);
                }
            } else if (dataByMonth.containsKey(ym)) {
                unassignedDataMonths.add(ym);
            }
        }

        double minUtil = config.minUtilThreshold();

        // Team-wide average size (over months with members, incl. headcount-only leave months)
        // → team factor.
        List<MonthUtilRow> nonEmpty = utilRows.stream().filter(m -> m.memberCount() > 0).toList();
        double teamAvgSize = nonEmpty.isEmpty() ? 0.0
                : nonEmpty.stream().mapToInt(MonthUtilRow::memberCount).average().orElse(0.0);
        double teamFactor = TeamleadBonusMath.teamFactor(teamAvgSize, config);

        // Per-leader windows. Size averages use every data month; utilization averages and the
        // recomposition weights use only months with ACTIVE data.
        List<LeaderWindow> windows = new ArrayList<>();
        List<Double> effUtils = new ArrayList<>();
        List<Integer> windowDataCounts = new ArrayList<>();
        for (Map.Entry<String, String> e : leaderNames.entrySet()) {
            String uuid = e.getKey();
            List<YearMonth> dm = dataMonthsByLeader.getOrDefault(uuid, List.of());
            List<YearMonth> dmActive = dm.stream()
                    .filter(ym -> dataByMonth.get(ym).hasActiveData()).toList();
            double measuredUtil = dmActive.isEmpty() ? 0.0
                    : dmActive.stream().mapToDouble(ym -> dataByMonth.get(ym).utilization()).average().orElse(0.0);
            AdjustmentAggregate adj = adjustments.get(uuid);
            boolean overridden = adj != null && adj.utilOverride() != null;
            double effUtil = overridden ? adj.utilOverride() : measuredUtil;
            double ownAvgSize = dm.isEmpty() ? 0.0
                    : dm.stream().mapToInt(ym -> dataByMonth.get(ym).memberCount()).average().orElse(0.0);
            double ownFactor = TeamleadBonusMath.teamFactor(ownAvgSize, config);
            double ownPoints = TeamleadBonusMath.rawPoints(effUtil, minUtil, ownFactor);
            windows.add(new LeaderWindow(uuid, e.getValue(), monthsAsLeader.getOrDefault(uuid, 0),
                    effUtil, overridden, overridden ? adj.utilOverrideNote() : null,
                    ownAvgSize, ownFactor, ownPoints, attributedMonthKeys.getOrDefault(uuid, List.of())));
            effUtils.add(effUtil);
            windowDataCounts.add(dmActive.size());
        }

        // Recomposed FY utilization (active-data-month-count-weighted over windows + unassigned months).
        double[] effArr = effUtils.stream().mapToDouble(Double::doubleValue).toArray();
        int[] cntArr = windowDataCounts.stream().mapToInt(Integer::intValue).toArray();
        double[] unassignedArr = unassignedDataMonths.stream()
                .filter(ym -> dataByMonth.get(ym).hasActiveData())
                .mapToDouble(ym -> dataByMonth.get(ym).utilization()).toArray();
        double recomposedTeamUtil = TeamleadBonusMath.recomposedUtilization(effArr, cntArr, unassignedArr);
        double teamRawPoints = TeamleadBonusMath.rawPoints(recomposedTeamUtil, minUtil, teamFactor);

        return new TeamAggregate(teamId, team.getName(), monthlyUtil, recomposedTeamUtil, teamAvgSize,
                teamFactor, teamRawPoints, List.copyOf(windows), coveredMonths);
    }

    /** Builds a single leader's bonus row from the team aggregate and the leader's hybrid slice. */
    private LeaderBonusRow buildLeaderRow(TeamAggregate agg, LeaderWindow w, TeamleadContext ctx,
                                          double slice, double teamPoolShare, double coveredFraction,
                                          boolean excluded, String excludedNote, boolean teamFullyExcluded) {
        double minUtil = ctx.config().minUtilThreshold();
        AdjustmentAggregate adj = ctx.adjustmentsByLeader().get(w.leaderUuid());
        double utilAboveMin = Math.max(w.ownWindowUtil() - minUtil, 0.0);

        double rowRawPoints = agg.teamRawPoints() * coveredFraction * slice;
        double rowPoolShare = teamPoolShare * slice;
        double rowAdjustedPoolBonus = teamPoolShare * coveredFraction * slice;

        double ownRevenue = calculateLeaderOwnRevenueInMonths(w.leaderUuid(), w.attributedMonthKeys());
        double proratedThreshold = TeamleadBonusMath.proratedThreshold(
                ctx.config().productionThresholdAnnual(), w.monthsAsLeader());
        double productionBonus = TeamleadBonusMath.productionBonus(
                ownRevenue, proratedThreshold, ctx.config().productionCommissionPercent());
        double annualizedRevenue = w.monthsAsLeader() > 0 ? (ownRevenue / w.monthsAsLeader()) * 12.0 : 0.0;

        double splitBonus = adj != null ? adj.splitBonus() : 0.0;
        double prepaidManual = adj != null ? adj.prepaidManual() : 0.0;
        double prepaidAuto = partnerBonusPayoutService.calculatePrepaidBonuses(w.leaderUuid(), ctx.fiscalYear());

        if (excluded) {
            // Keep informational fields; zero every payable component so KPI/footer sums stay correct.
            rowRawPoints = 0.0;
            rowPoolShare = 0.0;
            rowAdjustedPoolBonus = 0.0;
            productionBonus = 0.0;
            splitBonus = 0.0;
            prepaidAuto = 0.0;
            prepaidManual = 0.0;
        }

        double rPool = round(rowAdjustedPoolBonus, 2);
        double rProd = round(productionBonus, 2);
        double rSplit = round(splitBonus, 2);
        double rPrepaidAuto = round(prepaidAuto, 2);
        double rPrepaidManual = round(prepaidManual, 2);
        double total = excluded
                ? 0.0
                : round(TeamleadBonusMath.totalBonus(rPool, rProd, rSplit, rPrepaidAuto + rPrepaidManual), 2);

        return new LeaderBonusRow(
                agg.teamId(),
                agg.teamName(),
                w.leaderUuid(),
                w.leaderName(),
                w.monthsAsLeader(),
                round(w.ownWindowUtil(), 4),
                round(utilAboveMin, 4),
                w.overridden(),
                w.overrideNote(),
                round(w.ownWindowAvgSize(), 1),
                w.ownFactor(),
                round(rowRawPoints, 6),
                ctx.pricePerPoint(),
                round(rowPoolShare, 2),
                rPool,
                round(ownRevenue, 2),
                round(proratedThreshold, 2),
                rProd,
                round(annualizedRevenue, 2),
                rSplit,
                rPrepaidAuto,
                rPrepaidManual,
                total,
                agg.monthlyUtilization(),
                excluded,
                excludedNote,
                teamFullyExcluded,
                round(agg.teamRawPoints(), 6),
                agg.coveredMonths(),
                round(slice, 6));
    }

    /** Informational-only row for a team that had no LEADER role in the FY. */
    private LeaderBonusRow buildUnknownRow(TeamAggregate agg, TeamleadContext ctx) {
        double minUtil = ctx.config().minUtilThreshold();
        double utilAboveMin = Math.max(agg.recomposedTeamUtil() - minUtil, 0.0);
        return new LeaderBonusRow(
                agg.teamId(),
                agg.teamName(),
                UNKNOWN_LEADER_UUID,
                "Unknown Leader",
                0,
                round(agg.recomposedTeamUtil(), 4),
                round(utilAboveMin, 4),
                false,
                null,
                round(agg.teamAvgSize(), 1),
                agg.teamFactor(),
                0.0,
                ctx.pricePerPoint(),
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                agg.monthlyUtilization(),
                false,
                null,
                false,
                round(agg.teamRawPoints(), 6),
                0,
                0.0);
    }

    /**
     * Monthly utilization for a team over the considered months, from {@code fact_user_day} joined
     * to teamroles MEMBER. Rows of users who additionally hold an active LEADER role on the SAME team
     * that day are excluded (guards against dual MEMBER+LEADER rows). Member-overrides are applied:
     * force-excluded member-months (included = 0) drop out entirely, and force-included leave months
     * (included = 1, status MATERNITY/PAID/NON_PAY_LEAVE) count toward the headcount only (their fact
     * rows carry 0 billable/available so utilization is untouched).
     */
    private List<MonthUtilRow> calculateMonthlyUtilization(String teamId, List<YearMonth> months) {
        if (months.isEmpty()) return List.of();

        List<String> monthKeys = months.stream()
                .map(ym -> String.format("%04d%02d", ym.getYear(), ym.getMonthValue()))
                .toList();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT CONCAT(LPAD(fud.year, 4, '0'), LPAD(fud.month, 2, '0')) AS month_key,
                       SUM(CASE WHEN fud.status_type = 'ACTIVE' THEN fud.registered_billable_hours ELSE 0 END) AS total_billable,
                       SUM(CASE WHEN fud.status_type = 'ACTIVE' THEN fud.net_available_hours ELSE 0 END) AS total_available,
                       COUNT(DISTINCT fud.useruuid) AS member_count,
                       SUM(fud.status_type = 'ACTIVE') AS active_rows
                FROM fact_user_day fud
                JOIN teamroles tr ON tr.useruuid = fud.useruuid
                    AND tr.teamuuid = :teamId
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= fud.document_date
                    AND (tr.enddate IS NULL OR tr.enddate > fud.document_date)
                LEFT JOIN teamlead_bonus_member_override o
                    ON o.teamuuid = tr.teamuuid
                    AND o.useruuid = fud.useruuid
                    AND o.month = CONCAT(LPAD(fud.year, 4, '0'), LPAD(fud.month, 2, '0'))
                WHERE fud.consultant_type = 'CONSULTANT'
                  AND (o.uuid IS NULL OR o.included = 1)
                  AND (fud.status_type = 'ACTIVE'
                       OR (o.included = 1 AND fud.status_type IN (%s)))
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
                """.formatted(LEAVE_STATUS_SQL))
                .setParameter("teamId", teamId)
                .setParameter("monthKeys", monthKeys)
                .getResultList();

        return rows.stream().map(row -> {
            String monthKey = (String) row[0];
            double billable = ((Number) row[1]).doubleValue();
            double available = ((Number) row[2]).doubleValue();
            int memberCount = ((Number) row[3]).intValue();
            boolean hasActiveData = ((Number) row[4]).intValue() > 0;
            double utilization = available > 0 ? billable / available : 0.0;
            String formattedMonth = monthKey.substring(0, 4) + "-" + monthKey.substring(4);
            return new MonthUtilRow(formattedMonth, utilization, memberCount, hasActiveData);
        }).toList();
    }

    /** Config-driven Σ raw points across all non-fully-excluded teamleadbonus teams. */
    private double calculateSumRawPoints(List<YearMonth> consideredMonths, TeamleadBonusConfigDTO config,
                                         LocalDate fyStart, LocalDate fyEnd,
                                         Map<String, AdjustmentAggregate> adjustments,
                                         Set<String> fullyExcludedTeamIds) {
        List<Team> bonusTeams = Team.list("teamleadbonus = true");
        double sumPoints = 0.0;
        for (Team team : bonusTeams) {
            if (fullyExcludedTeamIds.contains(team.getUuid())) continue;
            sumPoints += computeTeamAggregate(team, consideredMonths, config, fyStart, fyEnd, adjustments).teamRawPoints();
        }
        return sumPoints;
    }

    /**
     * Registered revenue of teamleadbonus-team MEMBERs over the considered window, EXCLUDING rows of
     * users who hold an active LEADER role on ANY teamleadbonus team that day (the pool excludes
     * teamleads' own production), force-excluded member-months and fully-excluded teams' members.
     * Feeds the pool-basis estimate as {@code teamRevenue}.
     */
    private double calculateCompanyRevenue(LocalDate from, LocalDate to, Set<String> excludedTeamIds) {
        boolean hasExclusions = excludedTeamIds != null && !excludedTeamIds.isEmpty();
        String excludeClause = hasExclusions ? "  AND tr.teamuuid NOT IN (:excludedTeamIds)\n" : "";
        String sql = """
                SELECT COALESCE(SUM(fud.registered_amount), 0)
                FROM fact_user_day fud
                JOIN teamroles tr ON tr.useruuid = fud.useruuid
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= fud.document_date
                    AND (tr.enddate > fud.document_date OR tr.enddate IS NULL)
                JOIN team t ON t.uuid = tr.teamuuid
                    AND t.teamleadbonus = 1
                LEFT JOIN teamlead_bonus_member_override o
                    ON o.teamuuid = tr.teamuuid
                    AND o.useruuid = fud.useruuid
                    AND o.month = CONCAT(LPAD(fud.year, 4, '0'), LPAD(fud.month, 2, '0'))
                WHERE fud.document_date >= :from
                  AND fud.document_date <= :to
                  AND fud.consultant_type = 'CONSULTANT'
                  AND (o.uuid IS NULL OR o.included = 1)
                  AND (fud.status_type = 'ACTIVE'
                       OR (o.included = 1 AND fud.status_type IN (%s)))
                """.formatted(LEAVE_STATUS_SQL)
                + excludeClause
                + """
                  AND NOT EXISTS (
                        SELECT 1 FROM teamroles ldr
                        JOIN team lt ON lt.uuid = ldr.teamuuid AND lt.teamleadbonus = 1
                        WHERE ldr.useruuid = fud.useruuid
                          AND ldr.membertype = 'LEADER'
                          AND ldr.startdate <= fud.document_date
                          AND (ldr.enddate IS NULL OR ldr.enddate > fud.document_date)
                  )
                """;

        Query q = em.createNativeQuery(sql)
                .setParameter("from", from)
                .setParameter("to", to);
        if (hasExclusions) {
            q.setParameter("excludedTeamIds", excludedTeamIds);
        }
        return ((Number) q.getSingleResult()).doubleValue();
    }

    /** A leader's own registered revenue within the given (attributed) months only. */
    private double calculateLeaderOwnRevenueInMonths(String leaderUuid, List<String> monthKeys) {
        if (monthKeys == null || monthKeys.isEmpty()) return 0.0;
        Object result = em.createNativeQuery("""
                SELECT COALESCE(SUM(fud.registered_amount), 0)
                FROM fact_user_day fud
                WHERE fud.useruuid = :leaderUuid
                  AND CONCAT(LPAD(fud.year, 4, '0'), LPAD(fud.month, 2, '0')) IN (:monthKeys)
                  AND fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type = 'ACTIVE'
                """)
                .setParameter("leaderUuid", leaderUuid)
                .setParameter("monthKeys", monthKeys)
                .getSingleResult();
        return ((Number) result).doubleValue();
    }

    // =====================================================================
    // Monthly drill-down queries (validate-inputs view)
    // =====================================================================

    /**
     * Per-member rows for a team over the given months, keyed by month. Uses the SAME MEMBER-teamrole,
     * {@code CONSULTANT} and leader-excluded predicates as {@link #calculateMonthlyUtilization}, but
     * additionally surfaces full-leave members and per-member day counts so the admin can toggle them.
     * Hours are ACTIVE-status only (leave rows carry 0 hours), display-rounded to 2 decimals. Effective
     * inclusion (default {@code activeDays > 0}, flipped by an override) matches the collapsed query
     * 1:1, so the included members here sum to the team totals the bonus math consumes.
     */
    private Map<String, List<MemberDetail>> loadMemberDetails(String teamId, List<String> monthKeys,
                                                              Map<String, MemberOverrideInfo> teamOverrides) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT CONCAT(LPAD(fud.year, 4, '0'), LPAD(fud.month, 2, '0')) AS month_key,
                       fud.useruuid AS useruuid,
                       u.firstname AS firstname,
                       u.lastname AS lastname,
                       SUM(CASE WHEN fud.status_type = 'ACTIVE' THEN fud.registered_billable_hours ELSE 0 END) AS billable,
                       SUM(CASE WHEN fud.status_type = 'ACTIVE' THEN fud.net_available_hours ELSE 0 END) AS available,
                       SUM(CASE WHEN fud.status_type = 'ACTIVE' THEN 1 ELSE 0 END) AS active_days,
                       SUM(CASE WHEN fud.status_type IN (%1$s) THEN 1 ELSE 0 END) AS leave_days,
                       SUM(CASE WHEN fud.status_type = 'MATERNITY_LEAVE' THEN 1 ELSE 0 END) AS maternity_days,
                       SUM(CASE WHEN fud.status_type = 'PAID_LEAVE' THEN 1 ELSE 0 END) AS paid_days,
                       SUM(CASE WHEN fud.status_type = 'NON_PAY_LEAVE' THEN 1 ELSE 0 END) AS nonpay_days
                FROM fact_user_day fud
                JOIN teamroles tr ON tr.useruuid = fud.useruuid
                    AND tr.teamuuid = :teamId
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= fud.document_date
                    AND (tr.enddate IS NULL OR tr.enddate > fud.document_date)
                JOIN user u ON u.uuid = fud.useruuid
                WHERE fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type IN ('ACTIVE',%1$s)
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
                """.formatted(LEAVE_STATUS_SQL))
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
            int activeDays = ((Number) row[6]).intValue();
            int leaveDays = ((Number) row[7]).intValue();
            int maternityDays = ((Number) row[8]).intValue();
            int paidDays = ((Number) row[9]).intValue();
            int nonpayDays = ((Number) row[10]).intValue();

            MemberOverrideInfo override = teamOverrides.get(useruuid + "|" + monthKey);
            boolean overridden = override != null;
            boolean included = overridden ? override.included() : activeDays > 0;

            String leaveStatus = leaveDays > 0
                    ? (activeDays == 0 ? "FULL_LEAVE" : "PARTIAL_LEAVE")
                    : null;
            String leaveType = dominantLeaveType(maternityDays, paidDays, nonpayDays);

            MemberDetail member = new MemberDetail(
                    useruuid, fullName(firstName, lastName),
                    billable, available, utilizationOrNull(billable, available),
                    leaveStatus, leaveType, activeDays, leaveDays,
                    included, overridden, overridden ? override.note() : null);
            byMonth.computeIfAbsent(monthKey, k -> new ArrayList<>()).add(member);
        }
        return byMonth;
    }

    /** Member overrides for a team over the given months, keyed {@code "<useruuid>|<monthKey>"}. */
    private Map<String, MemberOverrideInfo> loadTeamMemberOverrides(String teamId, List<String> monthKeys) {
        Map<String, MemberOverrideInfo> result = new HashMap<>();
        for (TeamleadBonusMemberOverride o : TeamleadBonusMemberOverride.listByTeamAndMonths(teamId, monthKeys)) {
            result.put(o.useruuid + "|" + o.month, new MemberOverrideInfo(o.included, o.note));
        }
        return result;
    }

    /** Excluded leader UUIDs for one team in a fiscal year. */
    private Set<String> loadTeamLeaderExclusions(int fiscalYear, String teamId) {
        Set<String> result = new HashSet<>();
        for (TeamleadBonusLeaderExclusion e :
                TeamleadBonusLeaderExclusion.<TeamleadBonusLeaderExclusion>list("fiscalYear = ?1 and teamuuid = ?2", fiscalYear, teamId)) {
            result.add(e.useruuid);
        }
        return result;
    }

    /** Dominant leave type of a month (highest day count); {@code null} when there is no leave. */
    private static String dominantLeaveType(int maternityDays, int paidDays, int nonpayDays) {
        if (maternityDays <= 0 && paidDays <= 0 && nonpayDays <= 0) return null;
        String type = "MATERNITY_LEAVE";
        int best = maternityDays;
        if (paidDays > best) {
            best = paidDays;
            type = "PAID_LEAVE";
        }
        if (nonpayDays > best) {
            type = "NON_PAY_LEAVE";
        }
        return type;
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
     * {@link #calculateLeaderOwnRevenueInMonths}), keyed by {@code "<monthKey>|<useruuid>"}. Returns an
     * empty map when no month has a resolved leader.
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
     * Used for ranking display names only.
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
            return new LeaderInfo(UNKNOWN_LEADER_UUID, "Unknown Leader", 0);
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
    // user from holding LEADER roles on TWO such teams in the same FY. Per-user components (production
    // bonus, split bonus, prepaid auto/manual) are keyed by user, so they must only ever count ONCE
    // across that user's team rows — both in the admin dashboard totals and in a payout.

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
                row.monthlyUtilization(),
                row.excluded(), row.excludedNote(), row.teamFullyExcluded(),
                row.teamRawPoints(), row.coveredMonths(), row.sharePct());
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
                primary.monthlyUtilization(),
                primary.excluded(), primary.excludedNote(), primary.teamFullyExcluded(),
                primary.teamRawPoints(), primary.coveredMonths(), primary.sharePct());
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Primary window among a team's leader windows: most attributed months, tie-broken by UUID. */
    private static LeaderWindow primaryWindow(List<LeaderWindow> windows) {
        return windows.stream()
                .max(Comparator.comparingInt(LeaderWindow::monthsAsLeader)
                        .thenComparing(LeaderWindow::leaderUuid))
                .orElseThrow();
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

    /** Admin adjustments aggregated for one leader/FY. */
    public record AdjustmentAggregate(Double utilOverride, String utilOverrideNote,
                                      double splitBonus, double prepaidManual) {}

    /** A member override's effective state for the drill-down roster. */
    private record MemberOverrideInfo(boolean included, String note) {}

    /**
     * One considered month's collapsed team inputs. {@code hasActiveData} is false when the month's
     * only qualifying rows are force-included leave members (0/0 hours): such a month contributes
     * headcount to the size averages but never a utilization observation.
     */
    private record MonthUtilRow(String month, double utilization, int memberCount, boolean hasActiveData) {}

    /**
     * A single leader's own-window inputs for the hybrid split: months attributed by majority rule,
     * the own-window effective utilization (admin override applied), own-window average size/factor
     * and the resulting own-window points, plus the attributed month keys used for production revenue.
     */
    private record LeaderWindow(String leaderUuid, String leaderName, int monthsAsLeader,
                                double ownWindowUtil, boolean overridden, String overrideNote,
                                double ownWindowAvgSize, double ownFactor, double ownWindowPoints,
                                List<String> attributedMonthKeys) {}

    /** Team-level aggregate feeding the hybrid split for one team. */
    private record TeamAggregate(String teamId, String teamName, List<MonthlyUtilization> monthlyUtilization,
                                 double recomposedTeamUtil, double teamAvgSize, double teamFactor,
                                 double teamRawPoints, List<LeaderWindow> leaderWindows, int coveredMonths) {}

    /** Fiscal-year-wide computation context shared by every leader row. */
    public record TeamleadContext(int fiscalYear, LocalDate fyStart, LocalDate fyEnd,
                                  List<YearMonth> consideredMonths, TeamleadBonusConfigDTO config,
                                  PoolBasisBreakdown poolBasis, double poolAmount, double sumRawPoints,
                                  double pricePerPoint, Map<String, AdjustmentAggregate> adjustmentsByLeader,
                                  Map<String, Map<String, String>> leaderExclusionsByTeam,
                                  Set<String> fullyExcludedTeamIds) {}

    /** Fully-computed per-team leader bonus row (display-rounded). */
    public record LeaderBonusRow(String teamId, String teamName, String leaderUuid, String leaderName,
                                 int monthsAsLeader, double teamAvgUtilization, double utilAboveMin,
                                 boolean utilOverridden, String utilOverrideNote, double avgTeamSize,
                                 double teamFactor, double rawPoints, double pricePerPoint, double poolShare,
                                 double adjustedPoolBonus, double ownRevenue, double proratedThreshold,
                                 double productionBonus, double annualizedRevenue, double splitBonus,
                                 double prepaidAuto, double prepaidManual, double totalBonus,
                                 List<MonthlyUtilization> monthlyUtilization,
                                 boolean excluded, String excludedNote, boolean teamFullyExcluded,
                                 double teamRawPoints, int coveredMonths, double sharePct) {}
}
