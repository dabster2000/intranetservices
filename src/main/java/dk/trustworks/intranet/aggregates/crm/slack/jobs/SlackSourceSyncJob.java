package dk.trustworks.intranet.aggregates.crm.slack.jobs;

import dk.trustworks.intranet.aggregates.crm.slack.model.enums.SlackSyncTrigger;
import dk.trustworks.intranet.aggregates.crm.slack.services.SlackSourceSyncService;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Nightly read of the general Slack channels an admin listed (source-channel spec §4.1).
 *
 * <p>02:40, a deliberate fifteen minutes after {@code AccountSlackSyncJob} at 02:25, so the
 * two Slack lanes do not compete for the connection pool or for the Slack rate limits.
 * Both read whole Copenhagen days and make a model call per channel-day, and this one is
 * the heavier of the pair — one general channel is read on behalf of every account at once
 * — so it goes second and on its own.
 *
 * <p><b>No transaction on this method.</b> The service opens short ones around each write
 * and makes every Slack and model call outside them — a transaction spanning twenty-five
 * channel reads and their model calls would hold a pooled connection for the whole run.
 *
 * <p>{@code SKIP} on concurrent execution and the shutdown guard are the same pair every
 * scheduled job here carries: a run that overlaps the previous one would fight it for the
 * same per-day upserts, and a run that starts during a deployment gets killed halfway
 * through — with the cursor advanced per persisted day, the next night simply continues
 * from the first day this one did not finish. {@code SKIP} covers only the scheduler's own
 * invocations, though, so the lane lock in {@code SlackSyncRunService} is what keeps this
 * run and a manually triggered one apart.
 *
 * <p>{@link SlackSyncTrigger#SCHEDULED} and no actor: this run has nobody behind it, and
 * the run row's {@code started_by} stays null rather than naming a placeholder.
 *
 * <p>The whole run is gated by {@code crm.slack.source-channels.enabled} inside the
 * service, seeded off by the lane's migration.
 */
@JBossLog
@ApplicationScoped
public class SlackSourceSyncJob {

    @Inject
    SlackSourceSyncService syncService;

    @Scheduled(cron = "0 40 2 * * ?",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void nightlySync() {
        log.info("Slack source channel sync starting");
        syncService.syncAll(SlackSyncTrigger.SCHEDULED, null);
    }
}
