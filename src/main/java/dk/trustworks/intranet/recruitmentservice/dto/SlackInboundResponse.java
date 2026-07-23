package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * The dispatch outcome {@code POST /recruitment/slack/inbound} reports
 * back to the BFF (P13 base contract + the P14 {@code responseAction}
 * relay). The BFF translates the disposition into the Slack-appropriate
 * wire response — ephemeral JSON for slash commands, a
 * {@code response_url} post for block actions, silent 200 for events
 * (Slack requires 200 to stop retries), and for {@code view_submission} /
 * {@code block_suggestion} payloads the raw {@code responseAction} JSON
 * returned synchronously inside Slack's 3-second window.
 *
 * @param disposition    what happened:
 *                       <ul>
 *                         <li>{@code DISABLED} — master kill switch (or the
 *                             feature's own toggle) off; relay the ephemeral
 *                             for interactive payloads, ignore for events</li>
 *                         <li>{@code NOT_LINKED} — the Slack user resolves
 *                             to no active intranet user (fail-closed);
 *                             relay the ephemeral</li>
 *                         <li>{@code DUPLICATE} — payload id already
 *                             claimed (Slack retry / double-fire); drop
 *                             silently</li>
 *                         <li>{@code UNKNOWN} — handler key not on the
 *                             allowlist; logged and dropped</li>
 *                         <li>{@code HANDLED} — a registered handler ran</li>
 *                       </ul>
 * @param ephemeralText  user-facing notice for the dispositions that
 *                       carry one, null otherwise. Plain text, no PII
 *                       beyond what the actor already typed/sees.
 * @param responseAction raw JSON the BFF must return verbatim as the
 *                       synchronous HTTP response body to Slack — a
 *                       {@code view_submission} {@code response_action}
 *                       ({@code errors} | {@code update} | {@code clear})
 *                       or a {@code block_suggestion} {@code options}
 *                       payload. Null ⇒ empty ack.
 */
public record SlackInboundResponse(
        String disposition,
        String ephemeralText,
        String responseAction
) {

    /** P13-shape convenience: disposition + optional ephemeral, no response action. */
    public SlackInboundResponse(String disposition, String ephemeralText) {
        this(disposition, ephemeralText, null);
    }

    public static final String DISPOSITION_DISABLED = "DISABLED";
    public static final String DISPOSITION_NOT_LINKED = "NOT_LINKED";
    public static final String DISPOSITION_DUPLICATE = "DUPLICATE";
    public static final String DISPOSITION_UNKNOWN = "UNKNOWN";
    public static final String DISPOSITION_HANDLED = "HANDLED";

    public static SlackInboundResponse disabled(String text) {
        return new SlackInboundResponse(DISPOSITION_DISABLED, text);
    }

    public static SlackInboundResponse notLinked(String text) {
        return new SlackInboundResponse(DISPOSITION_NOT_LINKED, text);
    }

    public static SlackInboundResponse duplicate() {
        return new SlackInboundResponse(DISPOSITION_DUPLICATE, null);
    }

    public static SlackInboundResponse unknown() {
        return new SlackInboundResponse(DISPOSITION_UNKNOWN, null);
    }

    /** A handler ran; optional courtesy ephemeral for the actor. */
    public static SlackInboundResponse handled(String ephemeralText) {
        return new SlackInboundResponse(DISPOSITION_HANDLED, ephemeralText);
    }

    /** A handler ran and the BFF must relay this JSON synchronously. */
    public static SlackInboundResponse handledWithAction(String responseActionJson) {
        return new SlackInboundResponse(DISPOSITION_HANDLED, null, responseActionJson);
    }
}
