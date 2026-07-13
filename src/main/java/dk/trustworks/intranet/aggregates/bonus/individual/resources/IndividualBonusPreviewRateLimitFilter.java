package dk.trustworks.intranet.aggregates.bonus.individual.resources;

import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded per-actor Preview workload: 30 starts per five minutes and at most five in flight. */
@Provider
@Priority(Priorities.USER + 110)
public class IndividualBonusPreviewRateLimitFilter
        implements ContainerRequestFilter, ContainerResponseFilter {

    static final int MAX_REQUESTS = 30;
    static final int MAX_CONCURRENT = 5;
    static final Duration WINDOW = Duration.ofMinutes(5);
    private static final String PATH = "individual-bonuses/preview";
    private static final String ACTIVE_PROPERTY = IndividualBonusPreviewRateLimitFilter.class.getName() + ".active";
    private static final String ERROR = """
            {"error":"rate_limit_exceeded","message":"Too many bonus Previews. Try again later."}""";

    private final ConcurrentHashMap<String, ActorState> states = new ConcurrentHashMap<>();

    @Inject RequestHeaderHolder requestHeaderHolder;

    @Override
    public void filter(ContainerRequestContext context) {
        if (!isPreview(context)) return;
        String actor = requestHeaderHolder.getUserUuid();
        // The monthly resource performs the authoritative UUID check. Legacy read-only Preview remains
        // compatible; an unidentified caller simply cannot consume another actor's allowance.
        if (actor == null || actor.isBlank() || "anonymous".equals(actor)) return;

        Instant now = Instant.now();
        ActorState state = states.computeIfAbsent(actor, ignored -> new ActorState());
        synchronized (state) {
            while (!state.starts.isEmpty() && state.starts.peekFirst().isBefore(now.minus(WINDOW))) {
                state.starts.removeFirst();
            }
            if (state.starts.size() >= MAX_REQUESTS || state.inFlight >= MAX_CONCURRENT) {
                context.abortWith(Response.status(Response.Status.TOO_MANY_REQUESTS)
                        .header("Retry-After", 1)
                        .type(MediaType.APPLICATION_JSON_TYPE)
                        .entity(ERROR)
                        .build());
                return;
            }
            state.starts.addLast(now);
            state.inFlight++;
            context.setProperty(ACTIVE_PROPERTY, actor);
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        Object actorValue = request.getProperty(ACTIVE_PROPERTY);
        if (!(actorValue instanceof String actor)) return;
        ActorState state = states.get(actor);
        if (state == null) return;
        synchronized (state) {
            state.inFlight = Math.max(0, state.inFlight - 1);
        }
    }

    private static boolean isPreview(ContainerRequestContext context) {
        if (!"POST".equalsIgnoreCase(context.getMethod())) return false;
        String path = context.getUriInfo().getPath();
        return PATH.equals(path) || ("/" + PATH).equals(path);
    }

    static final class ActorState {
        final Deque<Instant> starts = new ArrayDeque<>();
        int inFlight;
    }
}
