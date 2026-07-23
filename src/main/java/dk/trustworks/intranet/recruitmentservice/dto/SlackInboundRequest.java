package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * The normalized inbound-Slack envelope the BFF forwards to
 * {@code POST /recruitment/slack/inbound} (P13 base contract, extended by
 * P14 with the payload detail the first handlers consume). The BFF has
 * already verified the Slack request signature against the raw body —
 * everything here is authenticated Slack traffic; the backend adds actor
 * resolution, dedupe and allowlist dispatch.
 * <p>
 * PII discipline: {@code text}, {@code messageText} and
 * {@code stateValues} can carry personal content typed by employees —
 * they are never logged (the P13 {@code LoggingFilter} exclusion covers
 * the whole route) and only ever land in event {@code pii} blocks.
 * <p>
 * Ids round-tripped through Slack ({@code actionValue},
 * {@code privateMetadata}) are claims, never facts — handlers re-authorize
 * the actor against the referenced aggregate before acting (P13 handler
 * contract).
 *
 * @param surface         which BFF route received the payload:
 *                        {@code interactions} | {@code commands} | {@code events}
 * @param payloadId       the provider id used for dedupe — Events API
 *                        {@code event_id}, interactive {@code trigger_id}
 *                        (suffixed {@code .action_id} for block actions),
 *                        command {@code trigger_id}. {@code block_suggestion}
 *                        payloads are query-only and never claimed.
 * @param slackUserId     the acting Slack user id ({@code U…}) — resolved
 *                        fail-closed against {@code users.slackusername}
 * @param slackTeamId     the Slack workspace id ({@code T…}), diagnostic
 * @param kind            the Slack payload type: {@code block_actions} |
 *                        {@code view_submission} | {@code view_closed} |
 *                        {@code message_action} | {@code block_suggestion} |
 *                        {@code command} | {@code event_callback}
 * @param handlerKey      the allowlist key: {@code action_id} | command name
 *                        (e.g. {@code /refer}) | {@code callback_id} | event
 *                        type (e.g. {@code app_home_opened})
 * @param triggerId       Slack {@code trigger_id} when present (modal opens),
 *                        else null
 * @param responseUrl     Slack {@code response_url} when present (delayed
 *                        responses), else null
 * @param text            free text typed by the user: the argument string of
 *                        a slash command ({@code /candidates jane}) or the
 *                        typed query of a {@code block_suggestion}; null
 *                        otherwise. Can be personal content — never logged.
 * @param channelId       the channel the interaction happened in ({@code C…}/
 *                        {@code D…}/{@code G…}) when Slack provides one;
 *                        drives {@code chat.update} and permalink resolution
 * @param messageTs       the ts of the message the interaction anchors to
 *                        (block action's container message, a shortcut's
 *                        target message); null otherwise
 * @param messageText     {@code message_action} only: the text of the message
 *                        the shortcut was invoked on (PII — pre-fills the
 *                        capture modal, never logged), clamped by the BFF
 * @param actionValue     {@code block_actions} only: the clicked button's
 *                        {@code value} (an id claim — always re-authorized)
 * @param privateMetadata {@code view_submission} only: the view's
 *                        {@code private_metadata} round-trip (a claim —
 *                        always re-authorized)
 * @param viewId          the Slack view id when the payload carries a view
 * @param stateValues     {@code view_submission} only: the raw JSON of
 *                        {@code view.state.values} — parsed by the handler
 *                        that owns the view's input blocks
 */
public record SlackInboundRequest(
        String surface,
        String payloadId,
        String slackUserId,
        String slackTeamId,
        String kind,
        String handlerKey,
        String triggerId,
        String responseUrl,
        String text,
        String channelId,
        String messageTs,
        String messageText,
        String actionValue,
        String privateMetadata,
        String viewId,
        String stateValues
) {

    /** The query-only payload kind that is dispatched without a dedupe claim. */
    public static final String KIND_BLOCK_SUGGESTION = "block_suggestion";

    /** P13-shape convenience for tests and internal callers. */
    public SlackInboundRequest(String surface, String payloadId, String slackUserId,
                               String slackTeamId, String kind, String handlerKey,
                               String triggerId, String responseUrl) {
        this(surface, payloadId, slackUserId, slackTeamId, kind, handlerKey,
                triggerId, responseUrl, null, null, null, null, null, null, null, null);
    }
}
