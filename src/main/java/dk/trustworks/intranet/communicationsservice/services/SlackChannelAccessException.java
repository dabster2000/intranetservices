package dk.trustworks.intranet.communicationsservice.services;

import java.io.IOException;
import java.util.Set;

/**
 * Slack said the token cannot read THIS channel — {@code not_in_channel},
 * {@code channel_not_found} or {@code is_archived} — as opposed to a token or scope
 * fault ({@link SlackConfigurationException}) or a transient blip (plain
 * {@link IOException}).
 *
 * <p>These three are a property of one channel, not of the app: the bot has not been
 * invited, the name was typed wrong, or the space was archived. {@code AccountSlackSyncService}
 * records the code on the account ({@code client_account.slack_link_error}) so the
 * header can show the person who linked the channel what to do, and moves on to the next
 * account. They are deliberately NOT in {@code SlackService.PERMANENT_CONFIG_ERRORS}:
 * {@code channel_not_found} is what Slack answers for a private channel the bot has
 * simply not been invited to yet, which an invitation fixes without touching the app.
 */
public class SlackChannelAccessException extends IOException {

    static final Set<String> CHANNEL_ACCESS_ERRORS = Set.of(
            "not_in_channel", "channel_not_found", "is_archived");

    private final String slackError;

    public SlackChannelAccessException(String message, String slackError) {
        super(message);
        this.slackError = slackError;
    }

    /** The Slack error code, e.g. {@code not_in_channel}. */
    public String getSlackError() {
        return slackError;
    }

    /** True when {@code error} is one of the three per-channel codes this class exists for. */
    public static boolean isChannelAccessError(String error) {
        return error != null && CHANNEL_ACCESS_ERRORS.contains(error);
    }
}
