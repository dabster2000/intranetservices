package dk.trustworks.intranet.vacationservice.services;

import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.vacationservice.engine.EmploymentCoverageCalculator;
import dk.trustworks.intranet.vacationservice.engine.EmploymentCoverageCalculator.StatusInterval;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.PolicyRate;
import dk.trustworks.intranet.vacationservice.model.VacationLedgerEntry;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntrySource;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntryType;
import dk.trustworks.intranet.vacationservice.model.enums.VacationPoolType;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.vacationservice.engine.VacationRules.LEDGER_EPOCH_FERIEAAR;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.ferieaarOf;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.round2;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.startOf;

/**
 * Posts one ACCRUAL entry per pool per completed month per employee, prorated
 * by employment coverage (hire/exit months become fractions; unpaid leave and
 * external consultants accrue nothing). Idempotent: every run backfills from
 * the ledger epoch and inserts only what is missing, so a missed night heals
 * itself and a freshly deployed system backfills history on the first run —
 * where a Danløn baseline already covers a month, the balance engine simply
 * supersedes the duplicate ground truth by as-of date.
 */
@JBossLog
@ApplicationScoped
public class VacationAccrualJob {

    static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");
    private static final double EPS = 0.005;

    @Inject
    VacationPolicyService policyService;

    @Scheduled(cron = "0 40 4 * * ?", timeZone = "Europe/Copenhagen", identity = "vacation-accrual")
    void nightly() {
        try {
            int posted = accrueAll();
            log.infof("vacation-accrual: %d entries posted", posted);
        } catch (Exception e) {
            log.error("vacation-accrual: run failed", e);
        }
    }

    @Transactional
    public int accrueAll() {
        LocalDate today = LocalDate.now(COPENHAGEN);
        YearMonth firstMonth = YearMonth.from(startOf(LEDGER_EPOCH_FERIEAAR));
        YearMonth lastCompleted = YearMonth.from(today).minusMonths(1);
        if (lastCompleted.isBefore(firstMonth)) return 0;

        List<PolicyRate> policies = policyService.rates();

        Map<String, List<StatusInterval>> timelines = UserStatus.<UserStatus>listAll().stream()
                .collect(Collectors.groupingBy(UserStatus::getUseruuid,
                        Collectors.mapping(s -> new StatusInterval(s.getStatusdate(), s.getStatus(), s.getType()),
                                Collectors.toList())));

        // Existing accrual keys: user|pool|accrual:YYYY-MM
        Set<String> existing = VacationLedgerEntry.<VacationLedgerEntry>list("entryType", VacationEntryType.ACCRUAL)
                .stream()
                .map(e -> e.getUseruuid() + "|" + e.getPool() + "|" + e.getSourceRef())
                .collect(Collectors.toCollection(HashSet::new));

        int posted = 0;
        for (Map.Entry<String, List<StatusInterval>> userTimeline : timelines.entrySet()) {
            String useruuid = userTimeline.getKey();
            Map<LocalDate, Double> coverage = EmploymentCoverageCalculator.coverageByMonth(
                    userTimeline.getValue(), firstMonth, lastCompleted);

            for (Map.Entry<LocalDate, Double> monthCoverage : coverage.entrySet()) {
                double fraction = monthCoverage.getValue();
                if (fraction <= EPS) continue;
                YearMonth month = YearMonth.from(monthCoverage.getKey());
                LocalDate monthEnd = month.atEndOfMonth();
                String sourceRef = "accrual:" + month;
                int ferieaar = ferieaarOf(monthEnd);

                for (VacationPoolType pool : VacationPoolType.values()) {
                    if (existing.contains(useruuid + "|" + pool + "|" + sourceRef)) continue;
                    double rate = VacationBalanceEngine.rateFor(policies, pool, monthEnd);
                    double days = round2(rate * fraction);
                    if (days <= EPS) continue;

                    VacationLedgerEntry entry = new VacationLedgerEntry();
                    entry.setUuid(UUID.randomUUID().toString());
                    entry.setUseruuid(useruuid);
                    entry.setFerieaar(ferieaar);
                    entry.setPool(pool);
                    entry.setEntryType(VacationEntryType.ACCRUAL);
                    entry.setDays(days);
                    entry.setEffectiveDate(monthEnd);
                    entry.setSource(VacationEntrySource.SYSTEM);
                    entry.setSourceRef(sourceRef);
                    entry.setCreatedBy("system");
                    entry.persist();
                    posted++;
                }
            }
        }
        return posted;
    }
}
