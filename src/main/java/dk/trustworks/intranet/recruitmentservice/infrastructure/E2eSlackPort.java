package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.SlackPort;
import dk.trustworks.intranet.recruitmentservice.ports.slack.SendDmCommand;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Deterministic in-memory {@link SlackPort} for Playwright dev-e2e runs.
 * Captures every DM into {@link #sentDms} so specs can assert on its size and
 * payload contents. Active only under {@code dev-e2e}.
 *
 * <p>Priority chain:
 * <ul>
 *   <li>{@link NoopSlackPort} {@code @Priority(1)}</li>
 *   <li>{@link SlackPortImpl} {@code @Priority(10)}</li>
 *   <li>{@link E2eSlackPort} {@code @Priority(100)}</li>
 * </ul>
 */
@ApplicationScoped
@Alternative
@Priority(100)
@IfBuildProfile("dev-e2e")
public class E2eSlackPort implements SlackPort {

    public final ConcurrentLinkedQueue<SendDmCommand> sentDms = new ConcurrentLinkedQueue<>();

    @Override
    public void sendDirectMessage(SendDmCommand cmd) {
        sentDms.add(cmd);
    }
}
