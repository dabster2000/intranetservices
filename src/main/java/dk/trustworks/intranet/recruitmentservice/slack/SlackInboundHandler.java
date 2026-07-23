package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;

/**
 * One entry on the inbound-Slack action allowlist (P13, Slack spec §4.2
 * step 3). CDI beans implementing this interface register themselves by
 * {@link #key()}; {@link SlackInboundDispatchService} dispatches only to
 * registered keys — unknown ids are logged and dropped, never
 * dynamically resolved.
 * <p>
 * Contract for implementations (first ones arrive in P14):
 * <ul>
 *   <li>Execute as the resolved {@code actor} through the <em>same</em>
 *       command services as the REST resources — all authorization
 *       (roles, involvement, circle filters, blind rules) applies
 *       unchanged.</li>
 *   <li>Never trust round-tripped state: ids from
 *       {@code private_metadata} or button values are claims — always
 *       re-authorize the actor against the referenced aggregate.</li>
 *   <li>Resulting events carry {@code payload.origin='slack'} so the
 *       timeline shows provenance and reports can measure adoption.</li>
 *   <li>Runs inside the dispatch transaction, together with the dedupe
 *       claim: both commit or neither, so a crash mid-handler lets the
 *       Slack retry re-execute cleanly.</li>
 * </ul>
 */
public interface SlackInboundHandler {

    /**
     * The allowlist key this handler owns: an {@code action_id}, a
     * command name (e.g. {@code /refer}), a {@code callback_id} or an
     * Events API event type (e.g. {@code app_home_opened}).
     */
    String key();

    /** Execute the payload as {@code actor}. */
    SlackInboundResponse handle(User actor, SlackInboundRequest request);
}
