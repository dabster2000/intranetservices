package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The triage ping's <b>View in intranet</b> button (P14). It is a URL
 * button — the browser opens client-side — but Slack still delivers a
 * {@code block_actions} payload for the click. Registering a deliberate
 * no-op keeps expected traffic off the unknown-key WARN path (which is
 * reserved for genuinely unregistered ids).
 */
@ApplicationScoped
public class SlackTriageViewButtonHandler implements SlackInboundHandler {

    public static final String KEY = "recruitment_triage_view";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        // The URL already opened client-side — nothing to do server-side.
        return SlackInboundResponse.handled(null);
    }
}
