package dk.trustworks.intranet.recruitmentservice.events;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.BooleanSupplier;

/**
 * Small shared helpers for recruitment event backbone tests.
 */
public final class RecruitmentEventTestSupport {

    /** Payload key the test reactors use to recognize their own events. */
    public static final String MARKER_KEY = "test_marker";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RecruitmentEventTestSupport() {
    }

    /** Extract the test marker from an event payload; null when absent. */
    public static String readMarker(RecruitmentEvent event) {
        if (event.getPayload() == null) {
            return null;
        }
        try {
            return MAPPER.readTree(event.getPayload()).path(MARKER_KEY).asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Poll until the condition holds or the timeout elapses.
     *
     * @return true iff the condition became true within the timeout
     */
    public static boolean awaitTrue(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return condition.getAsBoolean();
            }
        }
        return condition.getAsBoolean();
    }
}
