package dk.trustworks.intranet.aggregates.crm.retention.jobs;

import dk.trustworks.intranet.aggregates.crm.retention.services.CrmRetentionPurgeService;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The nightly 24-month CRM retention purge (spec §7).
 *
 * <p>03:30, deliberately after everything that writes the tables it sweeps: calendar at
 * 02:20, TrustLink and Slack at 02:40, the person registry at 03:00. A purge that ran before
 * the registry rebuild would delete rows the rebuild then re-derives from sources that are
 * themselves being erased, and the counts in the run row would be about a state that never
 * existed. It also avoids 02:40, which is already triple-booked.
 *
 * <p><b>No transaction on this method.</b> The service opens one per account and makes its
 * whole-table reads outside them — a transaction spanning a sweep of ten accounts would hold
 * a pooled connection for the entire run.
 *
 * <p>{@code SKIP} on concurrent execution and the shutdown guard are the pair every scheduled
 * job here carries: an overlapping run would fight the previous one for the same rows, and a
 * run that starts during a deployment gets killed halfway through — which for this job means
 * a run row left open, and nothing else, because each account commits on its own. {@code
 * SKIP} covers only the scheduler's own invocations, though, so the run lock in
 * {@link CrmRetentionPurgeService} is what keeps this tick and a manually triggered run
 * apart.
 *
 * <p><b>Two switches, and this is the first of them.</b> {@link #purgeEnabled} stops the job
 * from starting at all and defaults to {@code true} — it is the lever for stopping a
 * misbehaving job without a deploy. It is not what makes the purge safe to ship: that is the
 * {@code app_settings} row {@code crm.retention.purge.enabled}, seeded {@code 'false'} by
 * V608, which the service checks and which decides whether a run that <em>has</em> started
 * deletes anything. While that row is off this job files a {@code STOPPED} run every night
 * saying so, which is exactly what shipping a destructive job dark should look like: visible,
 * dated and inert.
 */
@JBossLog
@ApplicationScoped
public class CrmRetentionPurgeJob {

    /**
     * The kill switch. Off ⇒ the job never even starts, so not even a run row is written —
     * which is the point: this is the switch for "stop doing that", not the one for "do not
     * delete yet".
     */
    @ConfigProperty(name = "dk.trustworks.crm.retention.purge.enabled", defaultValue = "true")
    boolean purgeEnabled;

    @Inject
    CrmRetentionPurgeService purgeService;

    @Scheduled(cron = "0 30 3 * * ?",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void nightly() {
        if (!purgeEnabled) {
            log.debug("CRM retention purge skipped: dk.trustworks.crm.retention.purge.enabled=false");
            return;
        }
        log.info("CRM retention purge starting");
        purgeService.nightly();
    }
}
