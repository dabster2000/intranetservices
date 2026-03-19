package dk.trustworks.intranet.security.apiclient;

import jakarta.annotation.Priority;
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
 * Rate-limiting filter for the {@code POST /auth/token} endpoint.
 *
 * <p>Uses an in-memory sliding window (60 seconds) to limit each source IP
 * to at most {@value #MAX_REQUESTS_PER_WINDOW} requests within the window.
 * When the limit is exceeded, the filter aborts the request with HTTP 429
 * (Too Many Requests) and includes a {@code Retry-After} header indicating
 * how many seconds the client should wait.
 *
 * <p>IP extraction uses the last (rightmost) value from the {@code X-Forwarded-For}
 * header — AWS App Runner appends the real client IP, so the last entry is
 * the one set by infrastructure and cannot be spoofed. Falls back to the
 * servlet remote address.
 *
 * <p>Memory is bounded: at most {@value #MAX_TRACKED_IPS} IPs are tracked.
 * Entries older than {@value #EVICTION_AGE_MINUTES} minutes are lazily
 * evicted on each request.
 *
 * <p>Runs at {@link Priorities#AUTHENTICATION} - 10 so it executes
 * <em>before</em> authentication logic, preventing credential-stuffing
 * attacks from consuming bcrypt CPU cycles.
 */
@JBossLog
@Provider
@Priority(Priorities.AUTHENTICATION - 10)
public class TokenRateLimitFilter implements ContainerRequestFilter {

    static final int MAX_REQUESTS_PER_WINDOW = 10;
    static final Duration WINDOW_DURATION = Duration.ofSeconds(60);
    static final int MAX_TRACKED_IPS = 10_000;
    static final Duration EVICTION_AGE = Duration.ofMinutes(5);

    private static final String TOKEN_PATH = "/auth/token";
    private static final String ERROR_BODY_TEMPLATE = """
            {"error":"rate_limit_exceeded","error_description":"Too many requests. Try again in %d seconds."}""";

    private final ConcurrentHashMap<String, Deque<Instant>> requestLog = new ConcurrentHashMap<>();

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isTokenEndpoint(requestContext)) {
            return;
        }

        String sourceIp = extractSourceIp(requestContext);
        Instant now = Instant.now();
        Instant windowStart = now.minus(WINDOW_DURATION);

        evictStaleEntriesIfNeeded(now);

        Deque<Instant> timestamps = requestLog.computeIfAbsent(sourceIp, k -> new ConcurrentLinkedDeque<>());

        // Remove timestamps outside the current window
        purgeExpiredTimestamps(timestamps, windowStart);

        if (timestamps.size() >= MAX_REQUESTS_PER_WINDOW) {
            // Determine when the earliest request in the window will expire
            Instant oldest = timestamps.peekFirst();
            long retryAfterSeconds = oldest != null
                    ? Math.max(1, Duration.between(now, oldest.plus(WINDOW_DURATION)).getSeconds())
                    : WINDOW_DURATION.getSeconds();

            log.warnf("Rate limit exceeded for IP %s on POST /auth/token (%d requests in window)",
                    sourceIp, timestamps.size());

            requestContext.abortWith(
                    Response.status(429)
                            .header("Retry-After", retryAfterSeconds)
                            .type(MediaType.APPLICATION_JSON_TYPE)
                            .entity(ERROR_BODY_TEMPLATE.formatted(retryAfterSeconds))
                            .build()
            );
            return;
        }

        timestamps.addLast(now);
    }

    private boolean isTokenEndpoint(ContainerRequestContext context) {
        return "POST".equalsIgnoreCase(context.getMethod())
                && context.getUriInfo().getPath().endsWith(TOKEN_PATH);
    }

    /**
     * Extracts the client IP address. Uses the last (rightmost) entry from
     * {@code X-Forwarded-For} — AWS App Runner appends the real client IP,
     * so the last value is infrastructure-set and cannot be spoofed.
     * Falls back to the request's remote address property.
     */
    String extractSourceIp(ContainerRequestContext context) {
        String forwarded = context.getHeaderString("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        // Fallback: JAX-RS does not expose remote address directly on
        // ContainerRequestContext, but Quarkus/RESTEasy populates the
        // property "org.jboss.resteasy.request.remote.address" when available.
        Object remoteAddr = context.getProperty("org.jboss.resteasy.request.remote.address");
        if (remoteAddr != null) {
            return remoteAddr.toString();
        }
        return "unknown";
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

    /**
     * Lazily evicts IPs whose most recent request is older than
     * {@link #EVICTION_AGE}, or when the map exceeds {@link #MAX_TRACKED_IPS}.
     */
    private void evictStaleEntriesIfNeeded(Instant now) {
        if (requestLog.size() <= MAX_TRACKED_IPS) {
            return;
        }

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

    // Visible for testing
    ConcurrentHashMap<String, Deque<Instant>> getRequestLog() {
        return requestLog;
    }
}
