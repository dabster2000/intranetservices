package dk.trustworks.intranet.financeservice.jobs;

import dk.trustworks.intranet.financeservice.services.BankLiquidityService;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Schedules the e-conomic bank-liquidity import ({@link BankLiquidityService}).
 *
 * <p>Two triggers:</p>
 * <ul>
 *   <li>Nightly full rebuild at 02:40 UTC — after e-conomic's day is settled
 *       and after the staging environment's nightly database reset, which
 *       wipes {@code fact_bank_flow_monthly}.</li>
 *   <li>An hourly self-heal that re-imports only when the table is empty or
 *       stale (&gt; 26 h) — covers fresh deployments and a staging reset that
 *       lands after the nightly run, without doing work at startup.</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class BankLiquidityImportJob {

    @Inject
    BankLiquidityService bankLiquidityService;

    @Scheduled(cron = "0 40 2 * * ?",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void nightlyImport() {
        log.info("BankLiquidityImportJob.nightlyImport");
        bankLiquidityService.importAll();
    }

    @Scheduled(every = "1h", delayed = "7m",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void selfHeal() {
        bankLiquidityService.importIfStale(26);
    }
}
