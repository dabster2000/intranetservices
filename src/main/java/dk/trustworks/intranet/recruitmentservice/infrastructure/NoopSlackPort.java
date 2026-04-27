package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.SlackPort;
import dk.trustworks.intranet.recruitmentservice.ports.slack.SendDmCommand;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.jboss.logging.Logger;

/**
 * Fallback no-op implementation. Activated when no higher-priority alternative is registered
 * (see {@code SlackPortImpl} with {@code @Priority(10)}). Mirrors the {@code NoopOpenAIPort}
 * pattern from Slice 2.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class NoopSlackPort implements SlackPort {

    private static final Logger LOG = Logger.getLogger(NoopSlackPort.class);

    @Override
    public void sendDirectMessage(SendDmCommand cmd) {
        LOG.infof("NOOP sendDM recipient=%s headline=%s key=%s",
                cmd.recipientUserUuid(), cmd.headline(), cmd.idempotencyKey());
    }
}
