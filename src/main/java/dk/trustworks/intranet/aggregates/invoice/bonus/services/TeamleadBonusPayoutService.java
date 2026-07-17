package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.invoice.bonus.calculator.TeamleadBonusMath;
import dk.trustworks.intranet.aggregates.invoice.bonus.calculator.TeamleadBonusMath.PrepaidAllocation;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadPayoutResultDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusPayout;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.TeamBonusProjectionService.LeaderBonusRow;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.TeamBonusProjectionService.TeamleadContext;
import dk.trustworks.intranet.aggregates.users.services.SalaryLumpSumService;
import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.userservice.model.enums.LumpSumSalaryType;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import org.hibernate.exception.ConstraintViolationException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Teamlead-bonus payout — recomputes amounts server-side and guarantees one payout per
 * (fiscal year, leader). Mirrors {@link PartnerBonusPayoutService}: the unique
 * {@code (fiscal_year, useruuid)} constraint on {@code teamlead_bonus_payouts} is the hard
 * fund-once backstop, and each per-component {@link SalaryLumpSum} is deduped via its unique
 * {@code source_reference}.
 *
 * <p>Prepaid (auto + manual) is deducted from the components in order pool → production → split;
 * no component (and therefore no lump sum) is ever negative (spec §0.4/§0.5).</p>
 */
@ApplicationScoped
public class TeamleadBonusPayoutService {

    @Inject SalaryLumpSumService salaryLumpSumService;
    @Inject TeamBonusProjectionService projectionService;
    @Inject ObjectMapper objectMapper;

    /** True when a payout row already exists for (fiscal year, leader). */
    public boolean hasExistingPayout(int fiscalYear, String userUuid) {
        return TeamleadBonusPayout.findByFiscalYearAndUser(fiscalYear, userUuid) != null;
    }

    /**
     * Pay one teamlead for a fiscal year. Amounts are recomputed from the current projection; any
     * client-supplied figures are ignored.
     */
    @Transactional
    public TeamleadPayoutResultDTO payLeader(String userUuid, int fiscalYear, LocalDate payoutMonth, String requestedBy) {
        if (userUuid == null || userUuid.isBlank()) throw new BadRequestException("userUuid is required");
        LocalDate payMonth = (payoutMonth != null ? payoutMonth : LocalDate.now()).withDayOfMonth(1);

        // Friendly guard; the unique constraint is the hard backstop against races.
        if (hasExistingPayout(fiscalYear, userUuid)) {
            throw new ClientErrorException(
                    "Payout already exists for this leader and fiscal year", Response.Status.CONFLICT);
        }

        LeaderBonusRow row = recomputeLeaderRow(userUuid, fiscalYear);

        double poolAmount = row.adjustedPoolBonus();
        double productionAmount = row.productionBonus();
        double splitAmount = row.splitBonus();
        double prepaidTotal = round2(row.prepaidAuto() + row.prepaidManual());

        PrepaidAllocation allocation = TeamleadBonusMath.allocatePrepaid(
                poolAmount, productionAmount, splitAmount, prepaidTotal);
        double payablePool = round2(allocation.poolAmount());
        double payableProduction = round2(allocation.productionAmount());
        double payableSplit = round2(allocation.splitAmount());
        double total = round2(allocation.total());

        String label = fiscalYearLabel(fiscalYear);
        String poolLumpSumUuid = createLumpSumIfAbsent(userUuid, LumpSumSalaryType.TEAMLEAD_BONUS, payablePool, payMonth,
                "Teamlead pool bonus " + label, "teamlead_pool_" + fiscalYear + "_" + userUuid);
        String productionLumpSumUuid = createLumpSumIfAbsent(userUuid, LumpSumSalaryType.PROD_BONUS, payableProduction, payMonth,
                "Teamlead produktionsbonus " + label, "teamlead_prod_" + fiscalYear + "_" + userUuid);
        String splitLumpSumUuid = createLumpSumIfAbsent(userUuid, LumpSumSalaryType.TEAM_SPLIT_BONUS, payableSplit, payMonth,
                "Teamlead split bonus " + label, "teamlead_split_" + fiscalYear + "_" + userUuid);

        int lumpSumsCreated = countNonNull(poolLumpSumUuid, productionLumpSumUuid, splitLumpSumUuid);

        TeamleadBonusPayout payout = new TeamleadBonusPayout();
        payout.fiscalYear = fiscalYear;
        payout.useruuid = userUuid;
        payout.payoutMonth = payMonth;
        payout.poolAmount = payablePool;
        payout.productionAmount = payableProduction;
        payout.splitAmount = payableSplit;
        payout.prepaidDeduction = prepaidTotal;
        payout.totalAmount = total;
        payout.poolLumpSumUuid = poolLumpSumUuid;
        payout.productionLumpSumUuid = productionLumpSumUuid;
        payout.splitLumpSumUuid = splitLumpSumUuid;
        payout.calculationSnapshot = buildSnapshot(row, payMonth, prepaidTotal, allocation);
        payout.createdBy = requestedBy;
        payout.persist();
        flushRemappingDuplicateToConflict();

        Log.infof("Teamlead payout for %s FY %d: pool=%.2f prod=%.2f split=%.2f prepaid=%.2f total=%.2f lumpSums=%d",
                userUuid, fiscalYear, payablePool, payableProduction, payableSplit, prepaidTotal, total, lumpSumsCreated);

        return new TeamleadPayoutResultDTO(payablePool, payableProduction, payableSplit, prepaidTotal, total, lumpSumsCreated);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Flushes all pending inserts (lump sums + payout row) inside this transaction so the fund-once
     * unique constraints ({@code uq_teamlead_bonus_payouts_fy_user},
     * {@code uk_salary_lump_sum_source_ref}) fire here rather than at commit. The
     * {@link #hasExistingPayout} pre-check is non-locking, so a concurrent double-submit loser lands
     * on the constraint: it still rolls back fully (no orphan lump sums, no double payment) but now
     * receives the contract's 409 instead of a generic 500.
     */
    private void flushRemappingDuplicateToConflict() {
        try {
            TeamleadBonusPayout.getEntityManager().flush();
        } catch (ConstraintViolationException e) {
            throw new ClientErrorException(
                    "Payout already exists for this leader and fiscal year", Response.Status.CONFLICT);
        }
    }

    /**
     * Recompute the leader's payable row across ALL teamleadbonus teams they lead in this FY. When a
     * user leads several teams (the data model does not prevent it), the pool components are summed
     * across the teams and the per-user components (production, split, prepaid) are counted once —
     * mirroring {@link TeamleadBonusDashboardService}'s dual-leadership guard — so the single
     * (fiscal_year, useruuid) payout covers every led team.
     */
    private LeaderBonusRow recomputeLeaderRow(String userUuid, int fiscalYear) {
        TeamleadContext ctx = projectionService.buildContext(fiscalYear);
        List<LeaderBonusRow> rows = new ArrayList<>();
        for (Team team : Team.<Team>list("teamleadbonus = true")) {
            LeaderBonusRow row = projectionService.computeLeaderRow(team, ctx);
            if (userUuid.equals(row.leaderUuid())) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            throw new BadRequestException("User " + userUuid + " is not a teamlead-bonus leader for FY " + fiscalYear);
        }
        return TeamBonusProjectionService.combineLeaderRows(rows);
    }

    /**
     * Create a lump sum unless the amount is non-positive or one with the same source reference
     * already exists. Returns the lump-sum UUID when a new row was created, else {@code null}.
     */
    private String createLumpSumIfAbsent(String userUuid, LumpSumSalaryType type, double amount,
                                         LocalDate month, String description, String sourceRef) {
        if (amount <= 0.01) return null;
        Optional<SalaryLumpSum> existing = SalaryLumpSum.<SalaryLumpSum>find("sourceReference", sourceRef)
                .firstResultOptional();
        if (existing.isPresent()) {
            Log.infof("Skipping %s teamlead payout for %s — already exists (%s)", type, userUuid, sourceRef);
            return null;
        }
        SalaryLumpSum ls = new SalaryLumpSum();
        ls.setUuid(UUID.randomUUID().toString());
        ls.setUseruuid(userUuid);
        ls.setSalaryType(type);
        ls.setLumpSum(amount);
        ls.setPension(false);
        ls.setMonth(month);
        ls.setDescription(description);
        ls.setSourceReference(sourceRef);
        salaryLumpSumService.create(ls);
        Log.infof("Created %s for teamlead %s: %.2f (%s)", type, userUuid, amount, sourceRef);
        return ls.getUuid();
    }

    private String buildSnapshot(LeaderBonusRow row, LocalDate payMonth, double prepaidTotal, PrepaidAllocation alloc) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("teamId", row.teamId());
        snapshot.put("teamName", row.teamName());
        snapshot.put("leaderUuid", row.leaderUuid());
        snapshot.put("leaderName", row.leaderName());
        snapshot.put("payoutMonth", payMonth.toString());
        snapshot.put("monthsAsLeader", row.monthsAsLeader());
        snapshot.put("teamAvgUtilization", row.teamAvgUtilization());
        snapshot.put("utilOverridden", row.utilOverridden());
        snapshot.put("avgTeamSize", row.avgTeamSize());
        snapshot.put("teamFactor", row.teamFactor());
        snapshot.put("rawPoints", row.rawPoints());
        snapshot.put("pricePerPoint", row.pricePerPoint());
        snapshot.put("poolShare", row.poolShare());
        snapshot.put("adjustedPoolBonus", row.adjustedPoolBonus());
        snapshot.put("ownRevenue", row.ownRevenue());
        snapshot.put("proratedThreshold", row.proratedThreshold());
        snapshot.put("productionBonus", row.productionBonus());
        snapshot.put("splitBonus", row.splitBonus());
        snapshot.put("prepaidAuto", row.prepaidAuto());
        snapshot.put("prepaidManual", row.prepaidManual());
        snapshot.put("prepaidTotal", prepaidTotal);
        snapshot.put("payablePool", round2(alloc.poolAmount()));
        snapshot.put("payableProduction", round2(alloc.productionAmount()));
        snapshot.put("payableSplit", round2(alloc.splitAmount()));
        snapshot.put("payableTotal", round2(alloc.total()));
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            Log.warnf("Failed to serialize teamlead payout snapshot for %s: %s", row.leaderUuid(), e.getMessage());
            return null;
        }
    }

    private static int countNonNull(String... values) {
        int count = 0;
        for (String v : values) if (v != null) count++;
        return count;
    }

    private static String fiscalYearLabel(int fiscalYear) {
        return fiscalYear + "/" + String.format("%02d", (fiscalYear + 1) % 100);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
