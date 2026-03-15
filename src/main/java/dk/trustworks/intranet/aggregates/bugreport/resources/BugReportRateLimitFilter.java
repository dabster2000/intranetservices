package dk.trustworks.intranet.aggregates.bugreport.resources;

import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.jbosslog.JBossLog;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Rate-limiting filter for POST /bug-reports.
 * Limits each user to 10 reports per hour using an in-memory sliding window.
 */
@JBossLog
@Provider
@Priority(Priorities.USER)
public class BugReportRateLimitFilter implements ContainerRequestFilter {

    static final int MAX_REPORTS_PER_WINDOW = 10;
    static final Duration WINDOW_DURATION = Duration.ofHours(1);
    static final int MAX_TRACKED_USERS = 10_000;
    static final Duration EVICTION_AGE = Duration.ofHours(2);

    private static final String BUG_REPORTS_PATH = "bug-reports";
    private static final String ERROR_BODY_TEMPLATE = """
            {"error":"rate_limit_exceeded","message":"Too many bug reports. Try again in %d minutes."}""";

    private final ConcurrentHashMap<String, Deque<Instant>> requestLog = new ConcurrentHashMap<>();

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isBugReportPost(requestContext)) {
            return;
        }

        String userUuid = requestHeaderHolder.getUserUuid();
        if (userUuid == null || "anonymous".equals(userUuid)) {
            return;
        }

        Instant now = Instant.now();
        Instant windowStart = now.minus(WINDOW_DURATION);

        evictStaleEntriesIfNeeded(now);

        Deque<Instant> timestamps = requestLog.computeIfAbsent(userUuid, k -> new ConcurrentLinkedDeque<>());
        purgeExpiredTimestamps(timestamps, windowStart);

        if (timestamps.size() >= MAX_REPORTS_PER_WINDOW) {
            Instant oldest = timestamps.peekFirst();
            long retryAfterMinutes = oldest != null
                    ? Math.max(1, Duration.between(now, oldest.plus(WINDOW_DURATION)).toMinutes())
                    : WINDOW_DURATION.toMinutes();

            log.warnf("Bug report rate limit exceeded for user %s (%d reports in window)",
                    userUuid, timestamps.size());

            requestContext.abortWith(
                    Response.status(429)
                            .header("Retry-After", retryAfterMinutes * 60)
                            .type(MediaType.APPLICATION_JSON_TYPE)
                            .entity(ERROR_BODY_TEMPLATE.formatted(retryAfterMinutes))
                            .build());
            return;
        }

        timestamps.addLast(now);
    }

    private boolean isBugReportPost(ContainerRequestContext context) {
        if (!"POST".equalsIgnoreCase(context.getMethod())) return false;
        String path = context.getUriInfo().getPath();
        // Match exactly /bug-reports (no trailing path segments)
        return path.equals(BUG_REPORTS_PATH) || path.equals("/" + BUG_REPORTS_PATH);
    }

    private void purgeExpiredTimestamps(Deque<Instant> timestamps, Instant windowStart) {
        while (!timestamps.isEmpty()) {
            Instant head = timestamps.peekFirst();
            if (head != null && head.isBefore(windowStart)) {
                timestamps.pollFirst();
            } else {
                break;
            }
        }
    }

    private void evictStaleEntriesIfNeeded(Instant now) {
        if (requestLog.size() <= MAX_TRACKED_USERS) return;
        Instant evictionThreshold = now.minus(EVICTION_AGE);
        Iterator<Map.Entry<String, Deque<Instant>>> it = requestLog.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Deque<Instant>> entry = it.next();
            Instant lastRequest = entry.getValue().peekLast();
            if (lastRequest == null || lastRequest.isBefore(evictionThreshold)) {
                it.remove();
            }
        }
    }
}
