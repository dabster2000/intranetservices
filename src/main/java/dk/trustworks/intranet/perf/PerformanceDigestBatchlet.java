package dk.trustworks.intranet.perf;

import dk.trustworks.intranet.batch.monitoring.BatchJobExecutionTracking;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nightly performance digest. Batch-job regressions come from the existing
 * batch_job_execution_tracking table; invoice/external/infra come from CloudWatch
 * Logs Insights (Task 10). Posts to Slack #ops-alert. Off by default; enable per env.
 */
@JBossLog
@ApplicationScoped
public class PerformanceDigestBatchlet {

    @Inject
    EntityManager em;

    @Inject
    SlackService slackService;

    @ConfigProperty(name = "dk.trustworks.perf.digest.enabled", defaultValue = "false")
    boolean digestEnabled;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    @ConfigProperty(name = "dk.trustworks.perf.digest.recent-window-hours", defaultValue = "24")
    int recentWindowHours;

    @ConfigProperty(name = "dk.trustworks.perf.digest.baseline-days", defaultValue = "7")
    int baselineDays;

    @ConfigProperty(name = "dk.trustworks.perf.digest.regression-factor", defaultValue = "1.5")
    double regressionFactor;

    @Scheduled(cron = "0 30 6 * * ?", identity = "performance-digest")
    void scheduledRun() {
        if (!digestEnabled) {
            log.debug("performance-digest skipped: dk.trustworks.perf.digest.enabled=false");
            return;
        }
        try {
            runOnce();
        } catch (Exception e) {
            log.errorf(e, "performance-digest failed");
        }
    }

    void runOnce() {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder msg = new StringBuilder();
        msg.append(":bar_chart: *Performance digest* — last ").append(recentWindowHours).append("h\n\n");
        msg.append(batchSection(now));
        // Task 10 appends invoice/external/infra sections from Logs Insights here.
        slackService.sendMessage(opsAlertChannel, msg.toString(), "mother");
    }

    String batchSection(LocalDateTime now) {
        LocalDateTime since = now.minusDays(baselineDays);
        List<BatchJobExecutionTracking> rows = em.createQuery(
                        "SELECT e FROM BatchJobExecutionTracking e " +
                                "WHERE e.endTime IS NOT NULL AND e.startTime >= :since " +
                                "ORDER BY e.startTime", BatchJobExecutionTracking.class)
                .setParameter("since", since)
                .getResultList();
        return buildBatchDigest(rows, now, recentWindowHours, regressionFactor);
    }

    /** Pure regression detector — unit tested. */
    String buildBatchDigest(List<BatchJobExecutionTracking> rows, LocalDateTime now,
                            int recentWindowHours, double regressionFactor) {
        LocalDateTime recentCutoff = now.minusHours(recentWindowHours);
        Map<String, double[]> agg = new LinkedHashMap<>(); // job -> [recentSum, recentN, baseSum, baseN]
        for (BatchJobExecutionTracking e : rows) {
            if (e.getStartTime() == null || e.getEndTime() == null) continue;
            double durSec = Duration.between(e.getStartTime(), e.getEndTime()).toMillis() / 1000.0;
            double[] a = agg.computeIfAbsent(e.getJobName(), k -> new double[4]);
            if (e.getStartTime().isAfter(recentCutoff)) { a[0] += durSec; a[1] += 1; }
            else { a[2] += durSec; a[3] += 1; }
        }
        StringBuilder sb = new StringBuilder(":hourglass: *Batch jobs*\n");
        boolean any = false;
        for (Map.Entry<String, double[]> en : agg.entrySet()) {
            double[] a = en.getValue();
            if (a[1] == 0 || a[3] == 0) continue; // need both windows
            double recentAvg = a[0] / a[1];
            double baseAvg = a[2] / a[3];
            if (baseAvg > 0 && recentAvg > baseAvg * regressionFactor) {
                any = true;
                sb.append(String.format("• *%s* — %.0fs vs %.0fs baseline (%.1f× slower)%n",
                        en.getKey(), recentAvg, baseAvg, recentAvg / baseAvg));
            }
        }
        if (!any) sb.append("• no batch-job regressions\n");
        return sb.toString();
    }
}
