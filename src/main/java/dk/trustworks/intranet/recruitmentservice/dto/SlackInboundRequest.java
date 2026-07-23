package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * The normalized inbound-Slack envelope the BFF forwards to
 * {@code POST /recruitment/slack/inbound} (P13, Slack spec §4.2). The BFF
 * has already verified the Slack request signature against the raw body —
 * everything here is authenticated Slack traffic; the backend adds actor
 * resolution, dedupe and allowlist dispatch.
 * <p>
 * Deliberately minimal in P13: the allowlist is empty, so no handler
 * consumes payload detail yet. P14 extends this contract (modal state,
 * command text, {@code private_metadata}) together with the first
 * handlers — BFF and backend ship in lockstep per the plan's deploy
 * rules.
 *
 * @param surface     which BFF route received the payload:
 *                    {@code interactions} | {@code commands} | {@code events}
 * @param payloadId   the provider id used for dedupe — Events API
 *                    {@code event_id}, interactive {@code trigger_id}
 *                    (suffixed {@code .action_id} for block actions),
 *                    command {@code trigger_id}
 * @param slackUserId the acting Slack user id ({@code U…}) — resolved
 *                    fail-closed against {@code users.slackusername}
 * @param slackTeamId the Slack workspace id ({@code T…}), diagnostic
 * @param kind        the Slack payload type: {@code block_actions} |
 *                    {@code view_submission} | {@code view_closed} |
 *                    {@code message_action} | {@code command} |
 *                    {@code event_callback}
 * @param handlerKey  the allowlist key: {@code action_id} | command name
 *                    (e.g. {@code /refer}) | {@code callback_id} | event
 *                    type (e.g. {@code app_home_opened})
 * @param triggerId   Slack {@code trigger_id} when present (modal opens —
 *                    P14), else null
 * @param responseUrl Slack {@code response_url} when present (delayed
 *                    responses — P14), else null
 */
public record SlackInboundRequest(
        String surface,
        String payloadId,
        String slackUserId,
        String slackTeamId,
        String kind,
        String handlerKey,
        String triggerId,
        String responseUrl
) {
}
