package dk.trustworks.intranet.communicationsservice.services;

import java.io.IOException;

/**
 * A Slack call that failed for a PERMANENT reason in the Slack app's own
 * configuration — a missing OAuth scope, or a token that is dead or revoked —
 * as opposed to a transient transport, rate-limit or availability blip.
 * <p>
 * The distinction matters because the two need opposite handling. A transient
 * failure is retried away by the next scheduled run and deserves a WARN. A
 * configuration fault answers <em>every</em> call the same way until a human
 * changes the app at api.slack.com/apps and reinstalls it — so a caller that
 * degrades gracefully (the P24 DPO drift check) will keep degrading silently,
 * forever, unless the fault is surfaced at a severity someone alarms on. This
 * is the same "configuration state, not an incident" split already drawn for
 * {@code views.publish} / {@code not_enabled} in
 * {@link SlackService#reportPublishFailure(String, String)}, applied in the
 * other direction: there the goal was to stop a permanent state from paging,
 * here it is to stop one from hiding.
 * <p>
 * Extends {@link IOException} deliberately: every Slack caller already declares
 * {@code throws IOException}, so introducing this type is purely additive —
 * callers that do not care about the distinction are unaffected, and callers
 * that do can catch it ahead of the generic handler.
 *
 * @see SlackService#slackFailure(String, com.slack.api.methods.SlackApiTextResponse)
 */
public class SlackConfigurationException extends IOException {

    /** Slack's raw error code, e.g. {@code missing_scope}. */
    private final String slackError;

    /** The scope Slack says the call needs, when it says so ({@code null} otherwise). */
    private final String needed;

    /** The scopes the token actually carries, when Slack reports them ({@code null} otherwise). */
    private final String provided;

    SlackConfigurationException(String message, String slackError, String needed, String provided) {
        super(message);
        this.slackError = slackError;
        this.needed = needed;
        this.provided = provided;
    }

    public String getSlackError() {
        return slackError;
    }

    public String getNeeded() {
        return needed;
    }

    public String getProvided() {
        return provided;
    }
}
