package dk.trustworks.intranet.aggregates.bugreport.services;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Retrieves recent backend and frontend logs from CloudWatch Logs for a specific user.
 * Caps log excerpt at 500KB to prevent oversized database entries.
 */
@JBossLog
@ApplicationScoped
public class BugReportLogService {

    private static final int MAX_LOG_BYTES = 500 * 1024; // 500KB
    private static final int LOG_EVENT_LIMIT = 200;
    private static final long LOOKBACK_MINUTES = 3;

    @ConfigProperty(name = "bug-report.cloudwatch.log-group-backend")
    String backendLogGroup;

    @ConfigProperty(name = "bug-report.cloudwatch.log-group-frontend")
    String frontendLogGroup;

    private final CloudWatchLogsClient logsClient;

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
     * Retrieves the user's last 3 minutes of logs from both backend and frontend log groups.
     * Results are merged and sorted by timestamp.
     *
     * @param userUuid the user UUID to filter logs by
     * @return log excerpt as a string, capped at 500KB
     */
    public String retrieveLogExcerpt(String userUuid) {
        try {
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(LOOKBACK_MINUTES, ChronoUnit.MINUTES);

            List<FilteredLogEvent> allEvents = new ArrayList<>();
            allEvents.addAll(queryLogGroup(backendLogGroup, userUuid, startTime, endTime));
            allEvents.addAll(queryLogGroup(frontendLogGroup, userUuid, startTime, endTime));

            allEvents.sort(Comparator.comparingLong(FilteredLogEvent::timestamp));

            var sb = new StringBuilder();
            for (FilteredLogEvent event : allEvents) {
                sb.append(event.message());
                if (!event.message().endsWith("\n")) {
                    sb.append('\n');
                }
            }

            String result = sb.toString();
            return capAtMaxSize(result);
        } catch (Exception e) {
            log.warnf("Failed to retrieve CloudWatch logs for user %s: %s", userUuid, e.getMessage());
            return null;
        }
    }

    private List<FilteredLogEvent> queryLogGroup(String logGroupName, String userUuid,
                                                  Instant startTime, Instant endTime) {
        try {
            FilterLogEventsRequest request = FilterLogEventsRequest.builder()
                    .logGroupName(logGroupName)
                    .filterPattern("\"userUuid=" + userUuid + "\"")
                    .startTime(startTime.toEpochMilli())
                    .endTime(endTime.toEpochMilli())
                    .limit(LOG_EVENT_LIMIT)
                    .build();

            FilterLogEventsResponse response = logsClient.filterLogEvents(request);
            return response.events();
        } catch (Exception e) {
            log.debugf("Could not query log group %s for user %s: %s", logGroupName, userUuid, e.getMessage());
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
