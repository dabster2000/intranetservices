package dk.trustworks.intranet.aggregates.crm.calendar.jobs;

import dk.trustworks.intranet.aggregates.crm.calendar.services.AccountCalendarSyncService;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Nightly refresh of the account meeting metadata (CRM spec §3.2).
 *
 * <p>02:20, after the nightly fact-refresh block, so a run that reads ~100 mailboxes is not
 * competing with the materialised-view rebuilds for the connection pool.
 *
 * <p><b>No transaction on this method.</b> The service opens short ones around each write
 * and makes every Graph call outside them — a transaction spanning a hundred HTTP round
 * trips would hold a pooled connection for the whole run.
 *
 * <p>{@code SKIP} on concurrent execution and the shutdown guard are the same pair every
 * scheduled job here carries: a run that overlaps the previous one would fight it for the
 * same upserts, and a run that starts during a deployment gets killed halfway through.
 */
@JBossLog
@ApplicationScoped
public class AccountCalendarSyncJob {

    @Inject
    AccountCalendarSyncService syncService;

    @Scheduled(cron = "0 20 2 * * ?",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void nightlySync() {
        log.info("Account calendar sync starting");
        syncService.syncAll();
    }
}
