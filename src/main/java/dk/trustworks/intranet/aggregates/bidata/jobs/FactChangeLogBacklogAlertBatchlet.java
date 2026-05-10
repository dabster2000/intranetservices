package dk.trustworks.intranet.aggregates.bidata.jobs;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Watchdog for {@code fact_change_log} — fires a Slack alert when the
 * unprocessed backlog grows unhealthy (typically because
 * {@code sp_incremental_bi_refresh()} has stalled). Runs every 5 minutes.
 *
 * <p>Repeat-suppression: while a breach is firing continuously, the channel
 * receives at most one message per {@link #ALERT_REPEAT_INTERVAL}. The window
 * resets the moment the breach clears, so a flap re-alerts immediately.
 */
@JBossLog
@ApplicationScoped
public class FactChangeLogBacklogAlertBatchlet {

    static final Duration ALERT_REPEAT_INTERVAL = Duration.ofMinutes(60);

    @Inject
    EntityManager em;

    @Inject
    SlackService slackService;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    @ConfigProperty(name = "factChangeLog.backlogAlert.maxPending", defaultValue = "500")
    long maxPending;

    @ConfigProperty(name = "factChangeLog.backlogAlert.maxOldestPendingMinutes", defaultValue = "30")
    long maxOldestPendingMinutes;

    // null encodes "no alert in flight" — drives the immediate re-alert on
    // a breach that flaps. Atomic so overlapping scheduler invocations don't
    // race on the rate-limit window.
    private final AtomicReference<Instant> lastAlertSent = new AtomicReference<>(null);

    @Scheduled(cron = "0 */5 * * * ?", identity = "fact-change-log-backlog-alert")
    public void scheduledRun() {
        try {
            checkBacklog();
        } catch (RuntimeException e) {
            log.errorf(e, "FactChangeLogBacklogAlertBatchlet failed");
        }
    }

    void checkBacklog() {
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT COUNT(*), TIMESTAMPDIFF(MINUTE, MIN(created_at), NOW()) " +
                "FROM fact_change_log WHERE processed_at IS NULL")
                .getSingleResult();
        long pending = ((Number) row[0]).longValue();
        Long oldestPendingMinutes = row[1] == null ? null : ((Number) row[1]).longValue();

        boolean alert = pending > maxPending
                || (oldestPendingMinutes != null && oldestPendingMinutes > maxOldestPendingMinutes);

        log.debugf("fact_change_log backlog check — pending=%d oldestMinutes=%s alert=%s",
                Long.valueOf(pending), oldestPendingMinutes, Boolean.valueOf(alert));

        if (!alert) {
            lastAlertSent.set(null);
            return;
        }

        Instant now = Instant.now();
        Instant previous = lastAlertSent.get();
        boolean withinSuppressionWindow = previous != null
                && Duration.between(previous, now).compareTo(ALERT_REPEAT_INTERVAL) < 0;

        if (withinSuppressionWindow) {
            log.debugf("fact_change_log backlog still breached — suppressing duplicate alert (last sent %s)", previous);
            return;
        }

        String message = buildMessage(pending, oldestPendingMinutes);
        log.warnf("fact_change_log backlog alert firing — pending=%d oldestMinutes=%s",
                Long.valueOf(pending), oldestPendingMinutes);
        slackService.sendMessage(opsAlertChannel, message, "mother");
        lastAlertSent.set(now);
    }

    private String buildMessage(long pending, Long oldestPendingMinutes) {
        String oldest = oldestPendingMinutes == null ? "n/a" : oldestPendingMinutes.toString();
        return ":rotating_light: *fact_change_log backlog alert*\n"
                + "• pending events: " + pending + "\n"
                + "• oldest pending: " + oldest + " minutes\n"
                + "• threshold: pending > " + maxPending
                + " OR oldest > " + maxOldestPendingMinutes + " min\n"
                + "Cron `ev_bi_incremental_refresh` may be stalled. "
                + "Check `sp_incremental_bi_refresh()` execution log and DB advisory lock `bi_refresh`.";
    }
}
