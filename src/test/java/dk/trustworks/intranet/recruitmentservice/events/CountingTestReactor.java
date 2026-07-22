package dk.trustworks.intranet.recruitmentservice.events;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CDI-registered probe reactor for the backbone integration tests: receives
 * every event through the real dispatch machinery but only reacts to events
 * carrying its {@code test_marker} payload key — so it is inert for all
 * other tests sharing this Quarkus instance.
 * <p>
 * Failure injection: arm {@link #FAILURES_REMAINING} for a marker and the
 * next N deliveries of events with that marker throw before counting.
 */
@ApplicationScoped
public class CountingTestReactor extends RecruitmentReactor {

    public static final String NAME = "test-counting-reactor";

    /** side effects per marker (increments exactly once per successfully handled event) */
    public static final Map<String, AtomicInteger> SIDE_EFFECTS = new ConcurrentHashMap<>();
    /** handle() invocations per marker, successful or not */
    public static final Map<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();
    /** number of upcoming deliveries per marker that must fail */
    public static final Map<String, AtomicInteger> FAILURES_REMAINING = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    protected void handle(RecruitmentEvent event) {
        String marker = RecruitmentEventTestSupport.readMarker(event);
        if (marker == null) {
            return; // someone else's event — stay inert
        }
        ATTEMPTS.computeIfAbsent(marker, k -> new AtomicInteger()).incrementAndGet();
        AtomicInteger failures = FAILURES_REMAINING.get(marker);
        if (failures != null && failures.getAndDecrement() > 0) {
            throw new IllegalStateException("injected test failure for marker " + marker);
        }
        SIDE_EFFECTS.computeIfAbsent(marker, k -> new AtomicInteger()).incrementAndGet();
    }
}
