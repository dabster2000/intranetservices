package dk.trustworks.intranet.aggregates.bugreport.services;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retrieves recent backend and frontend logs from CloudWatch Logs for a specific user.
 * Dynamically discovers the active App Runner log group by prefix (since App Runner
 * log group names contain a random instance ID that changes on each deployment).
 * Caps log excerpt at 500KB to prevent oversized database entries.
 */
@JBossLog
@ApplicationScoped
public class BugReportLogService {

    private static final int MAX_LOG_BYTES = 500 * 1024; // 500KB
    private static final int LOG_EVENT_LIMIT = 500;
    private static final long LOOKBACK_MINUTES = 10;

    @ConfigProperty(name = "bug-report.cloudwatch.log-group-backend")
    String backendLogGroupPrefix;

    @ConfigProperty(name = "bug-report.cloudwatch.log-group-frontend")
    String frontendLogGroupPrefix;

    private final CloudWatchLogsClient logsClient;

    // Cache resolved log group names (they only change on redeploy)
    private final ConcurrentHashMap<String, String> resolvedLogGroups = new ConcurrentHashMap<>();

    public BugReportLogService() {
        ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
                .proxyConfiguration(proxyConfig.build());
        this.logsClient = CloudWatchLogsClient.builder()
                .region(Region.EU_WEST_1)
                .httpClientBuilder(httpClientBuilder)
                .build();
    }

    /**
     * Retrieves the user's last 10 minutes of logs from both backend and frontend log groups.
     * Results are merged and sorted by timestamp.
     *
     * @param userUuid the user UUID to filter logs by
     * @return log excerpt as a string, capped at 500KB, or null if retrieval fails
     */
    public String retrieveLogExcerpt(String userUuid) {
        try {
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(LOOKBACK_MINUTES, ChronoUnit.MINUTES);

            List<FilteredLogEvent> allEvents = new ArrayList<>();

            String backendGroup = resolveLogGroup(backendLogGroupPrefix);
            if (backendGroup != null) {
                allEvents.addAll(queryLogGroup(backendGroup, userUuid, startTime, endTime));
            } else {
                log.warnf("Could not resolve backend log group from prefix: %s", backendLogGroupPrefix);
            }

            String frontendGroup = resolveLogGroup(frontendLogGroupPrefix);
            if (frontendGroup != null) {
                allEvents.addAll(queryLogGroup(frontendGroup, userUuid, startTime, endTime));
            } else {
                log.warnf("Could not resolve frontend log group from prefix: %s", frontendLogGroupPrefix);
            }

            allEvents.sort(Comparator.comparingLong(FilteredLogEvent::timestamp));

            var sb = new StringBuilder();
            for (FilteredLogEvent event : allEvents) {
                sb.append(event.message());
                if (!event.message().endsWith("\n")) {
                    sb.append('\n');
                }
            }

            String result = sb.toString();
            if (result.isBlank()) {
                log.infof("No CloudWatch log events found for user %s in last %d minutes", userUuid, LOOKBACK_MINUTES);
                return null;
            }
            return capAtMaxSize(result);
        } catch (Exception e) {
            log.warnf("Failed to retrieve CloudWatch logs for user %s: %s", userUuid, e.getMessage());
            return null;
        }
    }

    /**
     * Discovers the most recent "application" log group matching the given prefix.
     * App Runner log groups have the format: /aws/apprunner/{service-name}/{instance-id}/application
     * The instance ID changes on each deployment, so we discover it dynamically.
     * Results are cached until invalidated.
     */
    private String resolveLogGroup(String prefix) {
        return resolvedLogGroups.computeIfAbsent(prefix, this::discoverLogGroup);
    }

    private String discoverLogGroup(String prefix) {
        try {
            var response = logsClient.describeLogGroups(
                    DescribeLogGroupsRequest.builder()
                            .logGroupNamePrefix(prefix)
                            .limit(20)
                            .build());

            var resolved = response.logGroups().stream()
                    .filter(g -> g.logGroupName().endsWith("/application"))
                    .max(Comparator.comparingLong(LogGroup::creationTime))
                    .map(LogGroup::logGroupName)
                    .orElse(null);

            if (resolved != null) {
                log.infof("Resolved log group: %s → %s", prefix, resolved);
            } else {
                log.warnf("No application log group found for prefix: %s (found %d groups total)",
                        prefix, response.logGroups().size());
            }
            return resolved;
        } catch (Exception e) {
            log.warnf("Could not discover log group for prefix %s: %s", prefix, e.getMessage());
            return null;
        }
    }

    private List<FilteredLogEvent> queryLogGroup(String logGroupName, String userUuid,
                                                  Instant startTime, Instant endTime) {
        try {
            FilterLogEventsRequest request = FilterLogEventsRequest.builder()
                    .logGroupName(logGroupName)
                    .filterPattern("\"" + userUuid + "\"")
                    .startTime(startTime.toEpochMilli())
                    .endTime(endTime.toEpochMilli())
                    .limit(LOG_EVENT_LIMIT)
                    .build();

            FilterLogEventsResponse response = logsClient.filterLogEvents(request);
            log.infof("CloudWatch query returned %d events from %s for user %s",
                    response.events().size(), logGroupName, userUuid);
            return response.events();
        } catch (Exception e) {
            log.warnf("Could not query log group %s for user %s: %s", logGroupName, userUuid, e.getMessage());
            // Invalidate cached log group in case it changed (redeploy)
            resolvedLogGroups.values().remove(logGroupName);
            return List.of();
        }
    }

    private String capAtMaxSize(String logExcerpt) {
        byte[] bytes = logExcerpt.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_LOG_BYTES) {
            return logExcerpt;
        }
        String truncated = new String(bytes, 0, MAX_LOG_BYTES, StandardCharsets.UTF_8);
        return truncated + "\n--- Log excerpt truncated at 500KB (original size: %s) ---"
                .formatted(formatSize(bytes.length));
    }

    private String formatSize(int bytes) {
        if (bytes < 1024) return bytes + "B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return "%.1fKB".formatted(kb);
        return "%.1fMB".formatted(kb / 1024.0);
    }
}
