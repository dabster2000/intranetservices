package dk.trustworks.intranet.utils.aws;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the resolution rule that the production incident turned on: the live ECS Express log
 * group carries a hash suffix, and 0-byte orphans sit under the same prefix — so the group must be
 * chosen by stored bytes, never by name or creation time. The cases below deliberately make the
 * orphan win on both of those weaker rules.
 */
class CloudWatchLogGroupResolverTest {

    private static final String PREFIX = "/aws/ecs/default/tw-quarkus-production";

    private static LogGroup group(String name, Long storedBytes, Long creationTime) {
        return LogGroup.builder()
                .logGroupName(name)
                .storedBytes(storedBytes)
                .creationTime(creationTime)
                .build();
    }

    /** Replays canned DescribeLogGroups pages and records the requests it was given. */
    private static class StubClient implements CloudWatchLogsClient {
        private final Deque<DescribeLogGroupsResponse> pages = new ArrayDeque<>();
        private final List<DescribeLogGroupsRequest> requests = new ArrayList<>();
        private RuntimeException failure;
        private int calls;

        @Override public String serviceName() { return "logs"; }
        @Override public void close() { }

        @Override
        public DescribeLogGroupsResponse describeLogGroups(DescribeLogGroupsRequest request) {
            calls++;
            requests.add(request);
            if (failure != null) throw failure;
            return pages.isEmpty()
                    ? DescribeLogGroupsResponse.builder().build()
                    : pages.poll();
        }
    }

    private static StubClient stubReturning(LogGroup... groups) {
        StubClient c = new StubClient();
        c.pages.add(DescribeLogGroupsResponse.builder().logGroups(groups).build());
        return c;
    }

    // ---- selection rule -------------------------------------------------------------------

    @Test
    void selectsNonEmptyGroupOverNewerZeroByteDuplicate() {
        // The orphan sorts first alphabetically, so "first match wins" would pick it.
        LogGroup live = group(PREFIX + "-4cbe", 5_000_000L, 1_000L);
        LogGroup orphan = group(PREFIX + "-0aaa", 0L, 9_999L);

        Optional<LogGroup> chosen = CloudWatchLogGroupResolver.selectLiveGroup(List.of(orphan, live));

        assertTrue(chosen.isPresent());
        assertEquals(PREFIX + "-4cbe", chosen.get().logGroupName());
    }

    @Test
    void selectsLargestWhenSeveralGroupsHoldData() {
        LogGroup small = group(PREFIX + "-1111", 100L, 5_000L);
        LogGroup large = group(PREFIX + "-2222", 900L, 1_000L);

        Optional<LogGroup> chosen = CloudWatchLogGroupResolver.selectLiveGroup(List.of(small, large));

        assertEquals(PREFIX + "-2222", chosen.orElseThrow().logGroupName());
    }

    @Test
    void tieOnStoredBytesFallsBackToNewest() {
        LogGroup older = group(PREFIX + "-1111", 500L, 1_000L);
        LogGroup newer = group(PREFIX + "-2222", 500L, 8_000L);

        Optional<LogGroup> chosen = CloudWatchLogGroupResolver.selectLiveGroup(List.of(older, newer));

        assertEquals(PREFIX + "-2222", chosen.orElseThrow().logGroupName());
    }

    @Test
    void returnsEmptyWhenEveryCandidateIsZeroBytes() {
        assertTrue(CloudWatchLogGroupResolver.selectLiveGroup(List.of(
                group(PREFIX + "-1111", 0L, 1_000L),
                group(PREFIX + "-2222", null, 2_000L))).isEmpty());
    }

    @Test
    void toleratesNullStoredBytesAndNullCreationTime() {
        LogGroup live = group(PREFIX + "-4cbe", 42L, null);
        LogGroup unknown = group(PREFIX + "-0aaa", null, null);

        assertEquals(PREFIX + "-4cbe",
                CloudWatchLogGroupResolver.selectLiveGroup(List.of(unknown, live)).orElseThrow().logGroupName());
    }

    @Test
    void doesNotRequireTheApplicationSuffixThatAppRunnerUsed() {
        // Regression guard: the old filter was endsWith("/application"), which no ECS Express
        // group satisfies. That alone made every resolution return null.
        LogGroup ecsGroup = group("/aws/ecs/default/tw-nextjs-production-7bf7", 1L, 1L);

        assertTrue(CloudWatchLogGroupResolver.selectLiveGroup(List.of(ecsGroup)).isPresent());
    }

    // ---- resolve() behaviour --------------------------------------------------------------

    @Test
    void resolveReturnsLiveGroupName() {
        StubClient client = stubReturning(
                group(PREFIX + "-0aaa", 0L, 9_999L),
                group(PREFIX + "-4cbe", 5_000_000L, 1_000L));

        assertEquals(PREFIX + "-4cbe", new CloudWatchLogGroupResolver(client).resolve(PREFIX));
        assertEquals(PREFIX, client.requests.get(0).logGroupNamePrefix());
    }

    @Test
    void resolveCachesSuccessfulLookups() {
        StubClient client = stubReturning(group(PREFIX + "-4cbe", 1L, 1L));
        CloudWatchLogGroupResolver resolver = new CloudWatchLogGroupResolver(client);

        assertEquals(PREFIX + "-4cbe", resolver.resolve(PREFIX));
        assertEquals(PREFIX + "-4cbe", resolver.resolve(PREFIX));

        assertEquals(1, client.calls, "second resolve must be served from cache");
    }

    @Test
    void resolveDoesNotCacheFailures() {
        StubClient client = new StubClient(); // no pages -> zero groups
        CloudWatchLogGroupResolver resolver = new CloudWatchLogGroupResolver(client);

        assertNull(resolver.resolve(PREFIX));
        assertNull(resolver.resolve(PREFIX));

        assertEquals(2, client.calls, "a failed resolution must be retried, not cached as null");
    }

    @Test
    void resolveReturnsNullWhenDescribeThrows() {
        StubClient client = new StubClient();
        client.failure = new RuntimeException("AccessDeniedException");

        assertNull(new CloudWatchLogGroupResolver(client).resolve(PREFIX));
    }

    @Test
    void resolveRejectsBlankPrefixWithoutCallingAws() {
        StubClient client = new StubClient();
        CloudWatchLogGroupResolver resolver = new CloudWatchLogGroupResolver(client);

        assertNull(resolver.resolve(null));
        assertNull(resolver.resolve("  "));
        assertEquals(0, client.calls);
    }

    @Test
    void resolveFollowsPaginationAcrossPages() {
        StubClient client = new StubClient();
        client.pages.add(DescribeLogGroupsResponse.builder()
                .logGroups(group(PREFIX + "-0aaa", 0L, 1L))
                .nextToken("page-2")
                .build());
        client.pages.add(DescribeLogGroupsResponse.builder()
                .logGroups(group(PREFIX + "-4cbe", 7L, 1L))
                .build());

        assertEquals(PREFIX + "-4cbe", new CloudWatchLogGroupResolver(client).resolve(PREFIX));
        assertEquals(2, client.calls);
        assertNull(client.requests.get(0).nextToken());
        assertEquals("page-2", client.requests.get(1).nextToken());
    }

    @Test
    void invalidateGroupForcesRediscovery() {
        StubClient client = new StubClient();
        client.pages.add(DescribeLogGroupsResponse.builder()
                .logGroups(group(PREFIX + "-4cbe", 1L, 1L)).build());
        client.pages.add(DescribeLogGroupsResponse.builder()
                .logGroups(group(PREFIX + "-9dd0", 1L, 1L)).build());
        CloudWatchLogGroupResolver resolver = new CloudWatchLogGroupResolver(client);

        assertEquals(PREFIX + "-4cbe", resolver.resolve(PREFIX));
        resolver.invalidateGroup(PREFIX + "-4cbe");

        assertEquals(PREFIX + "-9dd0", resolver.resolve(PREFIX), "service recreation must be picked up");
    }

    @Test
    void invalidatePrefixForcesRediscovery() {
        StubClient client = new StubClient();
        client.pages.add(DescribeLogGroupsResponse.builder()
                .logGroups(group(PREFIX + "-4cbe", 1L, 1L)).build());
        client.pages.add(DescribeLogGroupsResponse.builder()
                .logGroups(group(PREFIX + "-9dd0", 1L, 1L)).build());
        CloudWatchLogGroupResolver resolver = new CloudWatchLogGroupResolver(client);

        assertEquals(PREFIX + "-4cbe", resolver.resolve(PREFIX));
        resolver.invalidatePrefix(PREFIX);

        assertEquals(PREFIX + "-9dd0", resolver.resolve(PREFIX));
    }

    @Test
    void distinctPrefixesResolveIndependently() {
        String frontend = "/aws/ecs/default/tw-nextjs-production";
        StubClient client = new StubClient();
        client.pages.add(DescribeLogGroupsResponse.builder()
                .logGroups(group(PREFIX + "-4cbe", 1L, 1L)).build());
        client.pages.add(DescribeLogGroupsResponse.builder()
                .logGroups(group(frontend + "-7bf7", 1L, 1L)).build());
        CloudWatchLogGroupResolver resolver = new CloudWatchLogGroupResolver(client);

        assertEquals(PREFIX + "-4cbe", resolver.resolve(PREFIX));
        assertEquals(frontend + "-7bf7", resolver.resolve(frontend));
    }
}
