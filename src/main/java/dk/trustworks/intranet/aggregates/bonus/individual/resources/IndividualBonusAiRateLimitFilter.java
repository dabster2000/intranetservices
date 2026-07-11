package dk.trustworks.intranet.aggregates.bonus.individual.resources;

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

/** Per-actor sliding-window guard for the expensive contract-to-bonus model call. */
@JBossLog
@Provider
// Run just after HeaderInterceptor's default USER priority so RequestHeaderHolder contains the actor.
@Priority(Priorities.USER + 100)
public class IndividualBonusAiRateLimitFilter implements ContainerRequestFilter {

    static final int MAX_GENERATIONS_PER_WINDOW = 10;
    static final Duration WINDOW_DURATION = Duration.ofHours(1);
    static final int MAX_TRACKED_ACTORS = 10_000;
    static final Duration EVICTION_AGE = Duration.ofHours(2);

    private static final String GENERATE_PATH = "individual-bonuses/generate-from-text";
    private static final String ERROR_BODY = """
            {"error":"rate_limit_exceeded","message":"Too many bonus-spec generations. Try again later."}""";
    private static final String MISSING_ACTOR_BODY = """
            {"error":"missing_actor","message":"X-Requested-By actor identity is required."}""";

    private final ConcurrentHashMap<String, Deque<Instant>> requestLog = new ConcurrentHashMap<>();

    @Inject RequestHeaderHolder requestHeaderHolder;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isGenerationPost(requestContext)) return;

        String actor = requestHeaderHolder.getUserUuid();
        // Never leave the expensive endpoint unbounded or place unidentified clients in a shared
        // pseudo-user bucket. The BFF always supplies X-Requested-By; direct callers must do so too.
        if (actor == null || actor.isBlank() || "anonymous".equals(actor)) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(MISSING_ACTOR_BODY)
                    .build());
            return;
        }

        Instant now = Instant.now();
        evictStaleEntriesIfNeeded(now);

        Deque<Instant> timestamps = requestLog.computeIfAbsent(actor, ignored -> new ConcurrentLinkedDeque<>());
        // Check-and-add must be atomic for a single actor; otherwise concurrent browser retries can
        // both observe size 9 and exceed the promised 10-call ceiling.
        synchronized (timestamps) {
            purgeExpiredTimestamps(timestamps, now.minus(WINDOW_DURATION));

            if (timestamps.size() >= MAX_GENERATIONS_PER_WINDOW) {
                Instant oldest = timestamps.peekFirst();
                long retryAfterSeconds = oldest == null ? WINDOW_DURATION.toSeconds()
                        : Math.max(1, Duration.between(now, oldest.plus(WINDOW_DURATION)).toSeconds());
                log.warnf("Individual bonus AI rate limit exceeded for actor %s (%d generations in window)",
                        actor, timestamps.size());
                requestContext.abortWith(Response.status(Response.Status.TOO_MANY_REQUESTS)
                        .header("Retry-After", retryAfterSeconds)
                        .type(MediaType.APPLICATION_JSON_TYPE)
                        .entity(ERROR_BODY)
                        .build());
                return;
            }

            timestamps.addLast(now);
        }
    }

    private static boolean isGenerationPost(ContainerRequestContext context) {
        if (!"POST".equalsIgnoreCase(context.getMethod())) return false;
        String path = context.getUriInfo().getPath();
        return GENERATE_PATH.equals(path) || ("/" + GENERATE_PATH).equals(path);
    }

    private static void purgeExpiredTimestamps(Deque<Instant> timestamps, Instant windowStart) {
        while (!timestamps.isEmpty()) {
            Instant first = timestamps.peekFirst();
            if (first != null && first.isBefore(windowStart)) timestamps.pollFirst(); else return;
        }
    }

    private void evictStaleEntriesIfNeeded(Instant now) {
        if (requestLog.size() <= MAX_TRACKED_ACTORS) return;
        Instant threshold = now.minus(EVICTION_AGE);
        Iterator<Map.Entry<String, Deque<Instant>>> iterator = requestLog.entrySet().iterator();
        while (iterator.hasNext()) {
            Deque<Instant> timestamps = iterator.next().getValue();
            Instant last = timestamps.peekLast();
            if (last == null || last.isBefore(threshold)) iterator.remove();
        }
    }

    Map<String, Deque<Instant>> requestLog() {
        return requestLog;
    }
}
