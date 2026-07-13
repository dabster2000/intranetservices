package dk.trustworks.intranet.aggregates.bonus.individual.jobs;

import dk.trustworks.intranet.aggregates.bonus.individual.config.IndividualBonusMonthlyConfig;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusReconciliationScanRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusReconciliationService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/** Daily correction detection after the nightly fact refresh. */
@JBossLog
@ApplicationScoped
public class ReconcileIndividualBonusMonthlyJob {
    private static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");

    @Inject IndividualBonusMonthlyConfig config;
    @Inject IndividualBonusReconciliationService reconciliationService;

    @Scheduled(cron = "0 30 8 * * ?", timeZone = "Europe/Copenhagen")
    public void reconcile() {
        if (!config.reconciliationEnabled()) return;
        YearMonth current = YearMonth.now(COPENHAGEN);
        LocalDate from = current.minusMonths(config.boundedDueLookbackMonths()).atDay(1);
        LocalDate to = current.minusMonths(1).atDay(1);
        var result = reconciliationService.scan(new IndividualBonusReconciliationScanRequest(
                null, from, to, null, false), "system:individual-bonus-reconciliation");
        log.infof("Individual bonus reconciliation finished: scanned=%d created=%d blocked=%d",
                result.scanned(), result.created(), result.blocked());
    }
}
