package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The P22 living card's <b>View profile</b> button ({@code SlackCardReactor}).
 * It is a URL button — the candidate profile opens client-side in the
 * browser — but Slack still delivers a {@code block_actions} payload for
 * the click. Registering a deliberate no-op keeps expected traffic off the
 * unknown-key WARN path (which is reserved for genuinely unregistered ids),
 * exactly as {@link SlackTriageViewButtonHandler} does for the P14 triage
 * ping's <b>View in intranet</b> button.
 * <p>
 * Shipping the card without this handler is what produced the production
 * {@code no handler on the allowlist (key=recruitment_card_view)} warnings:
 * the clicks themselves always worked, but every one of them logged a WARN
 * and burned a dedupe-table row.
 */
@ApplicationScoped
public class SlackCardViewButtonHandler implements SlackInboundHandler {

    public static final String KEY = "recruitment_card_view";

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
