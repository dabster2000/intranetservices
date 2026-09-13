package dk.trustworks.intranet.aggregates.crm.trustlink.jobs;

import dk.trustworks.intranet.aggregates.crm.trustlink.services.TrustLinkSyncService;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Nightly refresh of the TrustLink connection mirror (CRM spec §3.5, §3.7).
 *
 * <p>02:40, twenty minutes after the calendar sync at 02:20. The two feed the same
 * relationship graph and both spend most of their time waiting on somebody else's HTTP, so
 * they are kept apart rather than made to share the connection pool with each other and
 * with the fact-refresh block.
 *
 * <p>Once a day is the right cadence: a LinkedIn connection made this afternoon is not news
 * that has to be on the account page before tomorrow morning, and TrustLink is a third-party
 * service with no rate contract. The manual trigger on {@code POST /trustlink/sync} exists
 * for the times somebody does not want to wait.
 *
 * <p><b>No transaction on this method.</b> The service opens short ones around each page it
 * writes and makes every HTTP call outside them — a transaction spanning the round trips of
 * a whole run would hold a pooled connection for its entire duration.
 *
 * <p>{@code SKIP} on concurrent execution and the shutdown guard are the pair every
 * scheduled job here carries: an overlapping run would fight the previous one for the same
 * upserts, and a run that starts during a deployment gets killed halfway through.
 *
 * <p>The job is inert until {@code dk.trustworks.crm.trustlink.enabled} is true; the service
 * checks the flag and logs one line saying so.
 */
@JBossLog
@ApplicationScoped
public class TrustLinkSyncJob {

    @Inject
    TrustLinkSyncService syncService;

    @Scheduled(cron = "0 40 2 * * ?",
            timeZone = "Europe/Copenhagen",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void nightlySync() {
        log.info("TrustLink sync starting");
        syncService.syncAll();
    }
}
