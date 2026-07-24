package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The {@code app_home_opened} Events API handler (P23, Slack spec §5.7):
 * the user opened the mother app's Home tab → publish their role-aware
 * recruitment dashboard. The primary refresh path — the targeted-refresh
 * reactor only covers the gap between opens.
 * <p>
 * All gating (master gate, pipeline flag, app-home toggle) lives in
 * {@link SlackAppHomeService#publishFor}; with any gate off nothing is
 * published and Slack keeps showing the default empty Home (plan §P23
 * DoD). Events API responses carry no user-visible content, so this
 * handler always answers a bare {@code HANDLED} — publish failures are
 * logged by the service and the next open retries naturally.
 */
@ApplicationScoped
public class SlackAppHomeOpenedHandler implements SlackInboundHandler {

    /** The Events API event type doubles as the allowlist key (P13 contract). */
    public static final String KEY = "app_home_opened";

    @Inject
    SlackAppHomeService appHomeService;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        appHomeService.publishFor(actor.getUuid());
        return SlackInboundResponse.handled(null);
    }
}
