package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * The dispatch outcome {@code POST /recruitment/slack/inbound} reports
 * back to the BFF (P13, Slack spec §4.2). The BFF translates the
 * disposition into the Slack-appropriate wire response — ephemeral JSON
 * for slash commands, a {@code response_url} post for block actions,
 * silent 200 for events (Slack requires 200 to stop retries).
 *
 * @param disposition   what happened:
 *                      <ul>
 *                        <li>{@code DISABLED} — master kill switch off;
 *                            relay the ephemeral for interactive
 *                            payloads, ignore for events</li>
 *                        <li>{@code NOT_LINKED} — the Slack user resolves
 *                            to no active intranet user (fail-closed);
 *                            relay the ephemeral</li>
 *                        <li>{@code DUPLICATE} — payload id already
 *                            claimed (Slack retry / double-fire); drop
 *                            silently</li>
 *                        <li>{@code UNKNOWN} — handler key not on the
 *                            allowlist; logged and dropped</li>
 *                        <li>{@code HANDLED} — a registered handler ran
 *                            (first handlers arrive in P14)</li>
 *                      </ul>
 * @param ephemeralText user-facing notice for the dispositions that
 *                      carry one, null otherwise. Plain text, no PII.
 */
public record SlackInboundResponse(
        String disposition,
        String ephemeralText
) {

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
}
