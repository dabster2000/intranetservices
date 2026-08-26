package dk.trustworks.intranet.aggregates.bugreport.services;

import dk.trustworks.intranet.utils.aws.CloudWatchLogGroupResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

/**
 * Retrieves recent backend and frontend logs from CloudWatch Logs for a specific user.
 * The concrete log-group names carry a hash suffix that changes whenever the ECS service is
 * recreated, so they are discovered from a stable prefix via {@link CloudWatchLogGroupResolver}.
 * Caps log excerpt at 500KB to prevent oversized database entries.
 */
@JBossLog
@ApplicationScoped
public class BugReportLogService {

    private static final int MAX_LOG_BYTES = 500 * 1024; // 500KB
    private static final int LOG_EVENT_LIMIT = 500;
    private static final long LOOKBACK_MINUTES = 5;

    @ConfigProperty(name = "bug-report.cloudwatch.log-group-backend")
    String backendLogGroupPrefix;

    @ConfigProperty(name = "bug-report.cloudwatch.log-group-frontend")
    String frontendLogGroupPrefix;

    @Inject
    CloudWatchLogGroupResolver logGroupResolver;

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
     * Retrieves the user's recent logs from both backend and frontend log groups,
     * filtered by the page URL's domain path for relevance.
     *
     * @param userUuid the user UUID to filter logs by
     * @param pageUrl  the page URL from the bug report (used to narrow log relevance)
     * @return log excerpt as a string, capped at 500KB, or null if retrieval fails
     */
    public String retrieveLogExcerpt(String userUuid, String pageUrl) {
        try {
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(LOOKBACK_MINUTES, ChronoUnit.MINUTES);

            List<FilteredLogEvent> allEvents = new ArrayList<>();

            // The resolver already logs the specific failure at ERROR; a bug report that silently
            // attaches no logs is the failure mode this whole path exists to avoid.
            String backendGroup = logGroupResolver.resolve(backendLogGroupPrefix);
            if (backendGroup != null) {
                allEvents.addAll(queryLogGroup(backendGroup, userUuid, startTime, endTime));
            } else {
                log.errorf("Bug report for user %s will carry no backend logs: prefix %s resolved to no log group",
                        userUuid, backendLogGroupPrefix);
            }

            String frontendGroup = logGroupResolver.resolve(frontendLogGroupPrefix);
            if (frontendGroup != null) {
                allEvents.addAll(queryLogGroup(frontendGroup, userUuid, startTime, endTime));
            } else {
                log.errorf("Bug report for user %s will carry no frontend logs: prefix %s resolved to no log group",
                        userUuid, frontendLogGroupPrefix);
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

    private List<FilteredLogEvent> queryLogGroup(String logGroupName, String userUuid,
                                                  Instant startTime, Instant endTime) {
        try {
            // CloudWatch filter patterns only support AND (space-separated quoted terms),
            // not OR or parentheses. Use UUID-only filter for broad matching.
            String filterPattern = "\"" + userUuid + "\"";

            FilterLogEventsRequest request = FilterLogEventsRequest.builder()
                    .logGroupName(logGroupName)
                    .filterPattern(filterPattern)
                    .startTime(startTime.toEpochMilli())
                    .endTime(endTime.toEpochMilli())
                    .limit(LOG_EVENT_LIMIT)
                    .build();

            FilterLogEventsResponse response = logsClient.filterLogEvents(request);
            log.infof("CloudWatch query returned %d events from %s for user %s",
                    response.events().size(), logGroupName, userUuid);
            return response.events();
        } catch (Exception e) {
            // A resolved-but-unqueryable group is either a stale name or a missing
            // logs:FilterLogEvents grant — both silently strip logs off the bug report, so this
            // is an ERROR, not a warning.
            log.errorf(e, "Could not query log group %s for user %s: %s", logGroupName, userUuid, e.getMessage());
            // Drop the cached name in case the service was recreated.
            logGroupResolver.invalidateGroup(logGroupName);
            return List.of();
        }
    }

    /**
     * Extracts a domain keyword from the page URL to use as a log filter.
     * E.g., "/admin/bug-reports/abc-123" → "bug-reports"
     *       "/profile" → "profile"
     *       "/clients/xyz" → "clients"
     */
    private String extractDomainKeyword(String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) return null;
        // Remove leading slash, strip query params
        String path = pageUrl.split("\\?")[0];
        if (path.startsWith("/")) path = path.substring(1);
        // Skip "admin/" prefix to get the domain
        if (path.startsWith("admin/")) path = path.substring(6);
        // Take the first path segment as the domain keyword
        int slash = path.indexOf('/');
        String keyword = slash > 0 ? path.substring(0, slash) : path;
        return keyword.isBlank() ? null : keyword;
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
