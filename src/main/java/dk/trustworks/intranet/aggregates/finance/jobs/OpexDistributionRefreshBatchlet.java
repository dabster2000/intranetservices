package dk.trustworks.intranet.aggregates.finance.jobs;

import dk.trustworks.intranet.aggregates.finance.services.OpexDistributionRefreshService;
import dk.trustworks.intranet.aggregates.finance.services.OpexDistributionRefreshService.RefreshOutcome;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Nightly trigger for {@link OpexDistributionRefreshService}.
 * Runs at 03:30 UTC (30 minutes after sp_nightly_bi_refresh) so that
 * fact_user_day and fact_salary_monthly are guaranteed fresh.
 *
 * <p>Cold-start guard: if the table is empty on startup (fresh deploy,
 * RDS backup restore), kicks off a one-shot refresh on a worker thread
 * so the readiness probe doesn't have to wait for it.
 *
 * <p>Failure handling: catches all exceptions (so the scheduler keeps
 * running), routes errors to Sentry via JBoss-log, and posts a Slack
 * alert with 6h rate-limiting (same pattern as
 * FactChangeLogBacklogAlertBatchlet).
 */
@JBossLog
@ApplicationScoped
public class OpexDistributionRefreshBatchlet {

    static final Duration ALERT_REPEAT_INTERVAL = Duration.ofHours(6);

    @Inject
    OpexDistributionRefreshService refreshService;

    @Inject
    SlackService slackService;

    @Inject
    ManagedExecutor managedExecutor;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    // null encodes "no alert in flight". Prevents Slack spam across rolling outages.
    final AtomicReference<Instant> lastAlertSent = new AtomicReference<>(null);

    @Scheduled(cron = "0 30 3 * * ?", identity = "fact-opex-distribution-refresh")
    public void scheduledRun() {
        try {
            RefreshOutcome outcome = refreshService.refresh();
            log.infof("fact_opex_distribution_mat refreshed: inserted=%d deleted=%d took=%dms window=[%s..%s)",
                    outcome.inserted(), outcome.deleted(), outcome.took().toMillis(),
                    outcome.windowFrom(), outcome.windowTo());
            lastAlertSent.set(null);
        } catch (Exception e) {
            log.errorf(e, "fact_opex_distribution_mat refresh failed");
            fireSlackAlertIfNeeded(e);
        }
    }

    /**
     * Startup refresh: fires a one-shot async refresh on every boot so we can
     * verify the nightly batch path immediately after deploy without waiting
     * for the next 03:30 UTC cycle. Reverted to "only when empty" in PR 2
     * once the read path is flipped and confidence in the refresh is high.
     */
    void onStart(@Observes StartupEvent ev) {
        log.warnf("Triggering one-shot fact_opex_distribution_mat refresh asynchronously on startup (verification mode)");
        managedExecutor.submit(() -> {
            try {
                refreshService.refresh();
            } catch (Exception e) {
                log.errorf(e, "startup one-shot refresh failed");
                fireSlackAlertIfNeeded(e);
            }
        });
    }

    void fireSlackAlertIfNeeded(Exception e) {
        Instant now = Instant.now();
        Instant previous = lastAlertSent.get();
        if (previous != null
                && Duration.between(previous, now).compareTo(ALERT_REPEAT_INTERVAL) < 0) {
            log.debugf("fact_opex_distribution_mat refresh failure still active — suppressing duplicate Slack alert (last sent %s)", previous);
            return;
        }
        String msg = ":rotating_light: *fact_opex_distribution_mat refresh failed*\n"
                + "• error: `" + e.getClass().getSimpleName() + ": " + e.getMessage() + "`\n"
                + "• impact: EBITDA Forecast chart will use yesterday's snapshot until next 03:30 UTC.\n"
                + "• action: check Quarkus production logs for the stack trace; "
                + "recycle the task to retry via the cold-start guard. "
                + "Do NOT increase `opex-distribution.refresh-window-fy-back` — "
                + "that widens the failing workload.";
        slackService.sendMessage(opsAlertChannel, msg, "mother");
        lastAlertSent.set(now);
    }
}
