package dk.trustworks.intranet.recruitmentservice.events;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CDI-registered probe reactor with poison-event semantics
 * ({@code maxDeliveryAttempts() == 2}: one live try + one catch-up retry,
 * then swallow and advance — the P9 AI-reactor failure posture). Events
 * whose marker is in {@link #POISON_MARKERS} always fail.
 */
@ApplicationScoped
public class SkippingTestReactor extends RecruitmentReactor {

    public static final String NAME = "test-skipping-reactor";

    public static final Set<String> POISON_MARKERS = ConcurrentHashMap.newKeySet();
    /** handle() invocations per marker, successful or not */
    public static final Map<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();
    /** side effects per marker (poison markers must stay at zero) */
    public static final Map<String, AtomicInteger> SIDE_EFFECTS = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    protected int maxDeliveryAttempts() {
        return 2;
    }

    @Override
    protected void handle(RecruitmentEvent event) {
        String marker = RecruitmentEventTestSupport.readMarker(event);
        if (marker == null) {
            return; // someone else's event — stay inert
        }
        ATTEMPTS.computeIfAbsent(marker, k -> new AtomicInteger()).incrementAndGet();
        if (POISON_MARKERS.contains(marker)) {
            throw new IllegalStateException("poison event for marker " + marker);
        }
        SIDE_EFFECTS.computeIfAbsent(marker, k -> new AtomicInteger()).incrementAndGet();
    }
}
