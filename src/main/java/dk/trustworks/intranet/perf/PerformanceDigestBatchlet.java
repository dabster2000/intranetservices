package dk.trustworks.intranet.perf;

import dk.trustworks.intranet.batch.monitoring.BatchJobExecutionTracking;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
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

    @ConfigProperty(name = "dk.trustworks.perf.log-group", defaultValue = "/ecs/tw-quarkus-production")
    String logGroup;

    private volatile CloudWatchLogsClient logsClient;

    private CloudWatchLogsClient logsClient() {
        if (logsClient == null) {
            synchronized (this) {
                if (logsClient == null) {
                    ProxyConfiguration.Builder proxy = ProxyConfiguration.builder();
                    ApacheHttpClient.Builder http = ApacheHttpClient.builder().proxyConfiguration(proxy.build());
                    logsClient = CloudWatchLogsClient.builder()
                            .region(Region.EU_WEST_1)
                            .httpClientBuilder(http)
                            .build();
                }
            }
        }
        return logsClient;
    }

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

        LocalDateTime to = now;
        LocalDateTime from = now.minusHours(recentWindowHours);
        String apiQuery =
                "filter ispresent(ExternalApiDurationMs) " +
                "| stats pct(ExternalApiDurationMs, 95) as p95 by api " +
                "| sort p95 desc | limit 10";
        try {
            List<Map<String, String>> apiRows = runInsightsQuery(apiQuery, from, to);
            msg.append('\n').append(formatInsightsSection(
                    ":satellite_antenna: *External API p95 (ms)*", apiRows, "api", "p95", "ms"));
        } catch (Exception e) {
            log.warnf("perf digest: external-API Logs Insights query failed: %s", e.getMessage());
        }

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

    /** Pure Slack-section formatter — unit tested. Sorts rows by numeric value desc. */
    String formatInsightsSection(String title, List<Map<String, String>> rows,
                                 String labelField, String valueField, String unit) {
        StringBuilder sb = new StringBuilder(title).append('\n');
        if (rows == null || rows.isEmpty()) {
            sb.append("• no data\n");
            return sb.toString();
        }
        rows.stream()
                .sorted(Comparator.comparingDouble(
                        (Map<String, String> r) -> parseDouble(r.get(valueField))).reversed())
                .limit(10)
                .forEach(r -> sb.append(String.format("• %s — %s %s%n",
                        r.getOrDefault(labelField, "?"), r.getOrDefault(valueField, "?"), unit)));
        return sb.toString();
    }

    private static double parseDouble(String s) {
        try { return s == null ? 0 : Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Runs a Logs Insights query and returns each result row as field->value. */
    List<Map<String, String>> runInsightsQuery(String query, LocalDateTime from, LocalDateTime to) {
        CloudWatchLogsClient c = logsClient();
        StartQueryResponse started = c.startQuery(StartQueryRequest.builder()
                .logGroupName(logGroup)
                .startTime(from.toEpochSecond(ZoneOffset.UTC))
                .endTime(to.toEpochSecond(ZoneOffset.UTC))
                .queryString(query)
                .build());
        String queryId = started.queryId();
        for (int i = 0; i < 30; i++) {
            GetQueryResultsResponse res = c.getQueryResults(
                    GetQueryResultsRequest.builder().queryId(queryId).build());
            if (res.status() == QueryStatus.COMPLETE) {
                List<Map<String, String>> out = new ArrayList<>();
                res.results().forEach(row -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    row.forEach(f -> m.put(f.field(), f.value()));
                    out.add(m);
                });
                return out;
            }
            if (res.status() == QueryStatus.FAILED || res.status() == QueryStatus.CANCELLED) break;
            try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        return List.of();
    }
}
