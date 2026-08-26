package dk.trustworks.intranet.utils.aws;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a concrete CloudWatch log-group name from a stable prefix.
 * <p>
 * ECS Express names a service's log group {@code /aws/ecs/default/<service>-<hash>}, where the
 * hash suffix is regenerated whenever the service is recreated — so the full name can never be
 * hard-coded. Several groups share a prefix: alongside the live one sit 0-byte orphans that were
 * created but never received logs. "First match wins" therefore picks arbitrarily, and the only
 * reliable discriminator is that the live group is the one actually holding data.
 * <p>
 * Resolution is therefore: list every group under the prefix, discard the ones holding no data,
 * and take the largest. Observed staging data: {@code tw-quarkus-staging-4769} holds 997 MB while
 * {@code -fc44} holds 0 B; {@code tw-nextjs-staging-5de7} holds 23.8 MB while {@code -fe17} holds
 * 0 B. Production currently has a single group per service.
 * <p>
 * Known limitation: immediately after a service is genuinely recreated, the retired group still
 * holds all the historical bytes while its replacement is close to empty, so largest-wins keeps
 * pointing at the retired group until the new one overtakes it. Queries against it succeed and
 * simply return nothing, so {@link #invalidateGroup(String)} — which only fires on a query
 * <em>error</em> — will not correct it. That window is tolerable here (both callers read only the
 * last few minutes or hours and would find nothing either way) but it is the reason to prefer
 * recreating services rarely, and the thing to revisit if resolution ever looks stuck.
 * <p>
 * Results are cached, since the name only changes on service recreation;
 * {@link #invalidatePrefix(String)} and {@link #invalidateGroup(String)} drop the cache entry when
 * a downstream call proves the cached name is stale.
 * <p>
 * A prefix that resolves to nothing is a misconfiguration, not an empty result — it is logged at
 * ERROR so it surfaces in alerting rather than decaying into a silent "no logs found".
 */
@JBossLog
@ApplicationScoped
public class CloudWatchLogGroupResolver {

    /** DescribeLogGroups caps a page at 50; paginate rather than drop the rest. */
    private static final int PAGE_LIMIT = 50;

    /** Guards against an unbounded loop should the API keep returning a token. */
    private static final int MAX_PAGES = 20;

    /**
     * Built on first use, not in the constructor. An {@code @ApplicationScoped} bean is
     * client-proxied, and ArC's generated proxy subclasses this class and calls its no-arg
     * constructor — so anything the constructor builds is built twice, and the proxy's copy is
     * unreachable and never closed. An {@link ApacheHttpClient} registers its connection pool with
     * the SDK's static idle-connection reaper, so that second copy would leak for the JVM's
     * lifetime. Same double-checked shape as PerformanceDigestBatchlet.
     */
    private volatile CloudWatchLogsClient logsClient;

    /** Non-null only in tests, which inject a stub instead of building a real client. */
    private final CloudWatchLogsClient injectedClient;

    /** prefix -> resolved log-group name. Only successful resolutions are cached. */
    private final ConcurrentHashMap<String, String> resolved = new ConcurrentHashMap<>();

    public CloudWatchLogGroupResolver() {
        this.injectedClient = null;
    }

    /** Test seam — lets unit tests drive resolution with a stubbed client. */
    CloudWatchLogGroupResolver(CloudWatchLogsClient logsClient) {
        this.injectedClient = logsClient;
        this.logsClient = logsClient;
    }

    private CloudWatchLogsClient logsClient() {
        if (injectedClient != null) return injectedClient;
        CloudWatchLogsClient c = logsClient;
        if (c == null) {
            synchronized (this) {
                c = logsClient;
                if (c == null) {
                    ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
                    ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
                            .proxyConfiguration(proxyConfig.build());
                    c = CloudWatchLogsClient.builder()
                            .region(Region.EU_WEST_1)
                            .httpClientBuilder(httpClientBuilder)
                            .build();
                    logsClient = c;
                }
            }
        }
        return c;
    }

    /**
     * Resolves {@code prefix} to the live log-group name, or {@code null} when none can be found.
     * Failures are never cached, so a transient API error is retried on the next call.
     */
    public String resolve(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            log.error("CloudWatch log-group resolution called with a blank prefix");
            return null;
        }
        String cached = resolved.get(prefix);
        if (cached != null) return cached;

        String discovered = discover(prefix);
        if (discovered != null) {
            resolved.put(prefix, discovered);
        }
        return discovered;
    }

    /** Forgets the cached name for {@code prefix}, forcing the next {@link #resolve} to re-discover. */
    public void invalidatePrefix(String prefix) {
        if (prefix != null) resolved.remove(prefix);
    }

    /** Forgets whichever prefix resolved to {@code logGroupName} — used when a query proves it stale. */
    public void invalidateGroup(String logGroupName) {
        if (logGroupName != null) resolved.values().remove(logGroupName);
    }

    private String discover(String prefix) {
        List<LogGroup> groups;
        try {
            groups = listGroups(prefix);
        } catch (Exception e) {
            // Distinct from "nothing matched": this is an API/permission failure, and treating it
            // as an empty result is exactly how the old code hid a broken prefix for months.
            log.errorf(e, "CloudWatch DescribeLogGroups failed for prefix %s: %s", prefix, e.getMessage());
            return null;
        }

        if (groups.isEmpty()) {
            log.errorf("No CloudWatch log group matches prefix %s — the configured prefix is stale " +
                    "(ECS Express groups are named /aws/ecs/default/<service>-<hash>)", prefix);
            return null;
        }

        Optional<LogGroup> best = selectLiveGroup(groups);
        if (best.isEmpty()) {
            log.errorf("All %d CloudWatch log groups matching prefix %s hold 0 stored bytes — " +
                    "no live group is receiving logs under this prefix", groups.size(), prefix);
            return null;
        }

        LogGroup chosen = best.get();
        log.infof("Resolved CloudWatch log group: %s -> %s (%d stored bytes, %d candidate(s))",
                prefix, chosen.logGroupName(), storedBytes(chosen), groups.size());
        return chosen.logGroupName();
    }

    /**
     * Picks the live group among same-prefix candidates: the one holding the most data.
     * Empty (0-byte) groups were provisioned but never used and are never selected.
     * Package-private and pure so the selection rule can be unit tested without AWS.
     */
    static Optional<LogGroup> selectLiveGroup(List<LogGroup> groups) {
        return groups.stream()
                .filter(g -> g.logGroupName() != null)
                .filter(g -> storedBytes(g) > 0)
                .max(Comparator.comparingLong(CloudWatchLogGroupResolver::storedBytes)
                        // Tie-break on recency so a rebuilt group of equal size still wins.
                        .thenComparingLong(g -> g.creationTime() == null ? 0L : g.creationTime()));
    }

    private static long storedBytes(LogGroup group) {
        return group.storedBytes() == null ? 0L : group.storedBytes();
    }

    private List<LogGroup> listGroups(String prefix) {
        List<LogGroup> all = new ArrayList<>();
        String token = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            DescribeLogGroupsRequest request = DescribeLogGroupsRequest.builder()
                    .logGroupNamePrefix(prefix)
                    .limit(PAGE_LIMIT)
                    .nextToken(token)
                    .build();
            DescribeLogGroupsResponse response = logsClient().describeLogGroups(request);
            all.addAll(response.logGroups());
            token = response.nextToken();
            if (token == null || token.isBlank()) break;
        }
        return all;
    }
}
