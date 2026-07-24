package dk.trustworks.intranet.recruitmentservice.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Backend defense-in-depth rate limiting for the public recruitment
 * surfaces: the ALB exposes {@code POST /apply/*} (P5 application forms)
 * and {@code POST /consent/*} (P19 consent page) to the internet, so the
 * backend throttles submissions regardless of what sits in front of it.
 * Sliding window (60 s), 429 + {@code Retry-After} — modeled on
 * {@code security/apiclient/TokenRateLimitFilter}.
 *
 * <h3>Identity-aware bucketing</h3>
 * Runs AFTER authentication ({@link Priorities#AUTHORIZATION} - 10; with
 * proactive auth the {@link SecurityIdentity} is established by then):
 * <ul>
 *   <li><b>Authenticated caller</b> (the BFF's system JWT): the rightmost
 *       {@code X-Forwarded-For} at the backend is the BFF task's own IP —
 *       every legitimate applicant would share ONE bucket. Instead the
 *       bucket keys on the {@code X-Client-IP} header the BFF forwards
 *       (ALB-verified client IP). Absent header → rate limiting is
 *       SKIPPED (trusted internal caller without client attribution).</li>
 *   <li><b>Anonymous caller</b> (direct hit on the public endpoint):
 *       buckets on the rightmost {@code X-Forwarded-For} entry — appended
 *       by our own infrastructure, not spoofable. {@code X-Client-IP} is
 *       NEVER trusted from anonymous callers. A garbage bearer token
 *       fails authentication with 401 before this filter runs (proactive
 *       auth — asserted by {@code PublicApplyResourceApiTest}), so an
 *       attacker cannot forge their way into the authenticated
 *       branch.</li>
 * </ul>
 * Bucket keys are prefixed ({@code xff:}/{@code client:}) so an anonymous
 * flood spoofing a victim's IP can never exhaust that applicant's
 * BFF-mediated bucket.
 *
 * <p>GETs (form config) are not limited; only submissions are. Config
 * knobs (test suites can disable or widen):
 * <ul>
 *   <li>{@code recruitment.public-apply.rate-limit.enabled} (default true)</li>
 *   <li>{@code recruitment.public-apply.rate-limit.max-requests} (default 10 / 60 s)</li>
 * </ul>
 */
@JBossLog
@Provider
@Priority(Priorities.AUTHORIZATION - 10)
public class PublicApplyRateLimitFilter implements ContainerRequestFilter {

    static final Duration WINDOW_DURATION = Duration.ofSeconds(60);
    static final int MAX_TRACKED_IPS = 10_000;
    static final Duration EVICTION_AGE = Duration.ofMinutes(5);

    /** Client attribution forwarded by the BFF on its backend calls. */
    static final String CLIENT_IP_HEADER = "X-Client-IP";

    private static final String ERROR_BODY = "{\"error\":\"RATE_LIMITED\"}";

    @ConfigProperty(name = "recruitment.public-apply.rate-limit.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "recruitment.public-apply.rate-limit.max-requests", defaultValue = "10")
    int maxRequests;

    @Inject
    SecurityIdentity securityIdentity;

    private final SlidingWindowLimiter limiter = new SlidingWindowLimiter();

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!enabled || !isRateLimitedPublicPost(requestContext)) {
            return;
        }
        String bucketKey = resolveBucketKey(
                isAnonymous(),
                requestContext.getHeaderString(CLIENT_IP_HEADER),
                requestContext.getHeaderString("X-Forwarded-For"),
                remoteAddressOf(requestContext));
        if (bucketKey == null) {
            // Authenticated internal caller without client attribution.
            return;
        }
        SlidingWindowLimiter.Decision decision =
                limiter.tryAcquire(bucketKey, Instant.now(), maxRequests);
        if (!decision.allowed()) {
            // The key is infrastructure-derived (rightmost XFF, or the
            // BFF's ALB-verified client IP) — never request payload.
            log.warnf("Rate limit exceeded for %s on %s", bucketKey,
                    requestContext.getUriInfo().getPath());
            requestContext.abortWith(Response.status(429)
                    .header("Retry-After", decision.retryAfterSeconds())
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(ERROR_BODY)
                    .build());
        }
    }

    /**
     * Anonymous unless a resolvable, non-anonymous identity is present.
     * Fails CLOSED: any identity-resolution problem falls back to the
     * anonymous branch (XFF bucketing) — never to the trusted skip.
     */
    private boolean isAnonymous() {
        try {
            return securityIdentity == null || securityIdentity.isAnonymous();
        } catch (RuntimeException e) {
            log.debug("SecurityIdentity unresolved in rate-limit filter — treating as anonymous", e);
            return true;
        }
    }

    /**
     * The bucket key for this request, or {@code null} to skip limiting.
     * Pure function — unit-tested with explicit inputs.
     * <ul>
     *   <li>anonymous → {@code xff:<rightmost X-Forwarded-For>} — the
     *       {@code X-Client-IP} header is IGNORED (spoofable);</li>
     *   <li>authenticated + {@code X-Client-IP} → {@code client:<ip>};</li>
     *   <li>authenticated without {@code X-Client-IP} → {@code null}
     *       (trusted internal caller).</li>
     * </ul>
     */
    static String resolveBucketKey(boolean anonymous, String clientIpHeader,
                                   String forwardedFor, String remoteAddress) {
        if (anonymous) {
            return "xff:" + rightmostForwardedFor(forwardedFor, remoteAddress);
        }
        if (clientIpHeader == null || clientIpHeader.isBlank()) {
            return null;
        }
        return "client:" + clientIpHeader.trim();
    }

    static boolean isRateLimitedPublicPost(ContainerRequestContext context) {
        if (!"POST".equalsIgnoreCase(context.getMethod())) {
            return false;
        }
        String path = context.getUriInfo().getPath();
        if (path == null) {
            return false;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path.equals("/apply") || path.startsWith("/apply/")
                || path.equals("/consent") || path.startsWith("/consent/");
    }

    /**
     * Last (rightmost) {@code X-Forwarded-For} entry — appended by our own
     * load balancer, so the caller cannot spoof it. Falls back to the
     * request's remote address.
     */
    static String rightmostForwardedFor(String forwardedFor, String remoteAddress) {
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] parts = forwardedFor.split(",");
            return parts[parts.length - 1].trim();
        }
        return remoteAddress;
    }

    static String remoteAddressOf(ContainerRequestContext context) {
        Object remoteAddr = context.getProperty("org.jboss.resteasy.request.remote.address");
        return remoteAddr != null ? remoteAddr.toString() : "unknown";
    }

    // Visible for testing
    SlidingWindowLimiter limiter() {
        return limiter;
    }

    /**
     * The window logic, isolated from HTTP so it can be unit-tested with
     * explicit instants (no flaky timing tests). Memory is bounded: at
     * most {@link #MAX_TRACKED_IPS} keys; stale entries are lazily evicted.
     */
    static final class SlidingWindowLimiter {

        /** Outcome of an acquisition attempt. */
        record Decision(boolean allowed, long retryAfterSeconds) {
        }

        private final ConcurrentHashMap<String, Deque<Instant>> requestLog = new ConcurrentHashMap<>();

        Decision tryAcquire(String key, Instant now, int maxRequests) {
            Instant windowStart = now.minus(WINDOW_DURATION);
            evictStaleEntriesIfNeeded(now);

            Deque<Instant> timestamps = requestLog.computeIfAbsent(
                    key, k -> new ConcurrentLinkedDeque<>());
            purgeExpiredTimestamps(timestamps, windowStart);

            if (timestamps.size() >= maxRequests) {
                Instant oldest = timestamps.peekFirst();
                long retryAfter = oldest != null
                        ? Math.max(1, Duration.between(now, oldest.plus(WINDOW_DURATION)).getSeconds())
                        : WINDOW_DURATION.getSeconds();
                return new Decision(false, retryAfter);
            }
            timestamps.addLast(now);
            return new Decision(true, 0);
        }

        private static void purgeExpiredTimestamps(Deque<Instant> timestamps, Instant windowStart) {
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
        int trackedKeys() {
            return requestLog.size();
        }
    }
}
