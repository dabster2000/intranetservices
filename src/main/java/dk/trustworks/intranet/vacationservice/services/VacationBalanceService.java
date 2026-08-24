package dk.trustworks.intranet.vacationservice.services;

import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.vacationservice.dto.VacationBalanceSummaryDTO;
import dk.trustworks.intranet.vacationservice.dto.VacationFlagDTO;
import dk.trustworks.intranet.vacationservice.dto.VacationOverviewDTO;
import dk.trustworks.intranet.vacationservice.dto.VacationPoolDTO;
import dk.trustworks.intranet.vacationservice.dto.VacationProjectionPointDTO;
import dk.trustworks.intranet.vacationservice.dto.VacationWarningDTO;
import dk.trustworks.intranet.vacationservice.engine.EmploymentCoverageCalculator;
import dk.trustworks.intranet.vacationservice.engine.EmploymentCoverageCalculator.StatusInterval;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.LedgerFact;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.PolicyRate;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.Result;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.UsageFact;
import dk.trustworks.intranet.vacationservice.engine.VacationRules;
import dk.trustworks.intranet.vacationservice.model.VacationLedgerEntry;
import dk.trustworks.intranet.vacationservice.model.enums.VacationPoolType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.vacationservice.engine.VacationRules.LEDGER_EPOCH_FERIEAAR;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.endOfEarning;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.endOfUse;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.ferieaarOf;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.isOpen;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.label;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.startOf;

/**
 * DB-backed orchestration around the pure {@link VacationBalanceEngine}:
 * fetches policies, ledger entries, VACATION-task timesheet rows and the
 * userstatus timeline, and maps engine results to DTOs. Batch variants issue
 * one query per data kind regardless of user count.
 */
@JBossLog
@ApplicationScoped
public class VacationBalanceService {

    static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");

    @Inject
    VacationPolicyService policyService;

    // ── Single user ───────────────────────────────────────────────────────

    public Result computeForUser(String useruuid) {
        return computeForUsers(List.of(useruuid)).get(useruuid);
    }

    public VacationOverviewDTO overview(String useruuid) {
        Result result = computeForUser(useruuid);
        LocalDate today = LocalDate.now(COPENHAGEN);
        List<VacationPoolDTO> pools = result.pools().stream().map(p -> toPoolDTO(p, today)).toList();
        return new VacationOverviewDTO(useruuid, result.usageCutoff(), pools,
                result.warnings().stream().map(VacationBalanceService::toWarningDTO).toList());
    }

    public List<VacationProjectionPointDTO> projection(String useruuid, LocalDate until) {
        Result result = computeForUser(useruuid);
        LocalDate today = LocalDate.now(COPENHAGEN);
        return VacationBalanceEngine.projection(result, today, until).stream()
                .map(p -> new VacationProjectionPointDTO(p.date(), p.ferieRemaining(), p.feriefridageRemaining(),
                        VacationRules.round2(p.ferieRemaining() + p.feriefridageRemaining())))
                .toList();
    }

    // ── Batch ─────────────────────────────────────────────────────────────

    public Map<String, Result> computeForUsers(List<String> useruuids) {
        if (useruuids.isEmpty()) return Map.of();
        LocalDate today = LocalDate.now(COPENHAGEN);
        List<PolicyRate> policies = policyService.rates();

        Map<String, List<LedgerFact>> factsByUser = VacationLedgerEntry.findByUsers(useruuids).stream()
                .collect(Collectors.groupingBy(VacationLedgerEntry::getUseruuid,
                        Collectors.mapping(e -> new LedgerFact(e.getFerieaar(), e.getPool(), e.getEntryType(),
                                e.getDays(), e.getEffectiveDate(), e.getSourceRef(), e.getCreatedAt()), Collectors.toList())));

        Map<String, List<UsageFact>> usageByUser = Work
                .<Work>list("taskuuid = ?1 AND useruuid IN (?2)", WorkService.VACATION, useruuids).stream()
                .filter(w -> w.getWorkduration() > 0)
                .collect(Collectors.groupingBy(Work::getUseruuid,
                        Collectors.mapping(w -> new UsageFact(w.getRegistered(), w.getWorkduration(), w.getPaidOut()),
                                Collectors.toList())));

        Map<String, List<StatusInterval>> timelineByUser = UserStatus
                .<UserStatus>list("useruuid IN (?1)", useruuids).stream()
                .collect(Collectors.groupingBy(UserStatus::getUseruuid,
                        Collectors.mapping(s -> new StatusInterval(s.getStatusdate(), s.getStatus(), s.getType()),
                                Collectors.toList())));

        YearMonth coverageFrom = YearMonth.from(startOf(LEDGER_EPOCH_FERIEAAR));
        YearMonth coverageTo = YearMonth.from(endOfEarning(ferieaarOf(today) + 1));

        Map<String, Result> results = new LinkedHashMap<>();
        for (String useruuid : useruuids) {
            Map<LocalDate, Double> coverage = EmploymentCoverageCalculator.coverageByMonth(
                    timelineByUser.getOrDefault(useruuid, List.of()), coverageFrom, coverageTo);
            results.put(useruuid, VacationBalanceEngine.compute(
                    policies,
                    factsByUser.getOrDefault(useruuid, List.of()),
                    usageByUser.getOrDefault(useruuid, List.of()),
                    coverage,
                    today));
        }
        return results;
    }

    public List<VacationBalanceSummaryDTO> summaries(List<User> users) {
        Map<String, Result> results = computeForUsers(users.stream().map(User::getUuid).toList());
        LocalDate today = LocalDate.now(COPENHAGEN);
        List<VacationBalanceSummaryDTO> summaries = new ArrayList<>();
        for (User user : users) {
            Result result = results.get(user.getUuid());
            if (result == null) continue;
            double ferie = 0;
            double ff = 0;
            double pending = 0;
            double planned = 0;
            for (VacationBalanceEngine.PoolStatus pool : result.pools()) {
                if (!isOpen(pool.ferieaar, today)) continue;
                if (pool.pool == VacationPoolType.FERIE) ferie += pool.remaining();
                else ff += pool.remaining();
                pending += pool.usedPending;
                planned += pool.usedPlanned;
            }
            summaries.add(new VacationBalanceSummaryDTO(user.getUuid(), user.getFullname(),
                    VacationRules.round2(ferie), VacationRules.round2(ff), VacationRules.round2(ferie + ff),
                    VacationRules.round2(pending), VacationRules.round2(planned), result.warnings().size()));
        }
        return summaries;
    }

    public List<VacationFlagDTO> flags(List<User> users) {
        Map<String, Result> results = computeForUsers(users.stream().map(User::getUuid).toList());
        Map<String, String> names = users.stream()
                .collect(HashMap::new, (m, u) -> m.put(u.getUuid(), u.getFullname()), HashMap::putAll);
        List<VacationFlagDTO> flags = new ArrayList<>();
        results.forEach((useruuid, result) -> result.warnings().forEach(w ->
                flags.add(new VacationFlagDTO(useruuid, names.getOrDefault(useruuid, useruuid),
                        w.type().name(), w.ferieaar(), label(w.ferieaar()), w.days(), w.message()))));
        return flags;
    }

    // ── Mapping ───────────────────────────────────────────────────────────

    static VacationPoolDTO toPoolDTO(VacationBalanceEngine.PoolStatus pool, LocalDate today) {
        return new VacationPoolDTO(
                pool.ferieaar,
                label(pool.ferieaar),
                pool.pool,
                startOf(pool.ferieaar),
                endOfEarning(pool.ferieaar),
                endOfUse(pool.ferieaar),
                isOpen(pool.ferieaar, today),
                pool.baselineAsOf,
                pool.baselineEarned,
                pool.baselineUsed,
                pool.accrued,
                pool.earnedToDate(),
                pool.projectedEarnedTotal(),
                pool.transferredIn,
                pool.transferredOut,
                pool.paidOutDays,
                pool.adjustment,
                pool.usedConfirmed,
                pool.usedPending,
                pool.usedPlanned,
                pool.usedTotal(),
                pool.remaining(),
                pool.remainingProjected(),
                pool.transferableNow());
    }

    static VacationWarningDTO toWarningDTO(VacationBalanceEngine.Warning warning) {
        return new VacationWarningDTO(warning.type().name(), warning.ferieaar(),
                label(warning.ferieaar()), warning.days(), warning.message());
    }
}
