package dk.trustworks.intranet.aggregates.crm.slack.jobs;

import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackSyncService;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Nightly digest of every linked account Slack space (CRM spec §3.2, §4.9).
 *
 * <p>02:25, five minutes after the calendar sync at 02:20 and after the nightly
 * fact-refresh block, so a run that reads ~20 channels and makes one model call per
 * channel-day is not competing with the materialised-view rebuilds for the pool.
 * Digesting at night rather than at the spec's 18:00 means yesterday is digested whole;
 * an 18:00 run would cut the afternoon off.
 *
 * <p><b>No transaction on this method.</b> The service opens short ones around each
 * write and makes every Slack and model call outside them — a transaction spanning
 * twenty channel reads and their model calls would hold a pooled connection for the whole
 * run.
 *
 * <p>{@code SKIP} on concurrent execution and the shutdown guard are the same pair every
 * scheduled job here carries: a run that overlaps the previous one would fight it for the
 * same per-day upserts, and a run that starts during a deployment gets killed halfway
 * through — with the cursor stored per persisted day, the next night simply continues.
 *
 * <p>The whole run is gated by {@code crm.slack.account-spaces.enabled} inside the
 * service, seeded off by V594.
 */
@JBossLog
@ApplicationScoped
public class AccountSlackSyncJob {

    @Inject
    AccountSlackSyncService syncService;

    @Scheduled(cron = "0 25 2 * * ?",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void nightlySync() {
        log.info("Account Slack sync starting");
        syncService.syncAll();
    }
}
