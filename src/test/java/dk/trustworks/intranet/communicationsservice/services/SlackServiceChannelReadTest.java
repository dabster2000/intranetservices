package dk.trustworks.intranet.communicationsservice.services;

import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.model.Message;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three-tier classification behind the account-space read, and the shape rule that
 * makes bot posts droppable with one null check. Static and package-private on
 * {@link SlackService} precisely so this needs no Slack client.
 */
class SlackServiceChannelReadTest {

    private static ConversationsHistoryResponse failing(String error) {
        ConversationsHistoryResponse response = new ConversationsHistoryResponse();
        response.setOk(false);
        response.setError(error);
        return response;
    }

    /** A fact about THIS channel: recorded on the account, the loop continues. */
    @Test
    void perChannelCodesBecomeAChannelAccessException() {
        for (String code : new String[] {"not_in_channel", "channel_not_found", "is_archived"}) {
            IOException failure = SlackService.channelFailure("read", failing(code));
            SlackChannelAccessException access = assertInstanceOf(SlackChannelAccessException.class, failure, code);
            assertEquals(code, access.getSlackError());
            assertTrue(access.getMessage().contains(code));
        }
    }

    /** A fact about the APP: the same answer for every account, so the run stops. */
    @Test
    void scopeAndTokenFaultsStayConfigurationExceptions() {
        assertInstanceOf(SlackConfigurationException.class, SlackService.channelFailure("read", failing("missing_scope")));
        assertInstanceOf(SlackConfigurationException.class, SlackService.channelFailure("read", failing("invalid_auth")));
    }

    /** Everything else is a blip the next night fixes by itself. */
    @Test
    void transientCodesAreAPlainIOException() {
        IOException failure = SlackService.channelFailure("read", failing("ratelimited"));
        assertFalse(failure instanceof SlackChannelAccessException);
        assertFalse(failure instanceof SlackConfigurationException);
    }

    @Test
    void aBotPostWithoutASubtypeIsStampedAsOne() {
        Message bot = new Message();
        bot.setTs("1.000000");
        bot.setBotId("B123");
        bot.setText("New lead: Ørsted");
        assertEquals("bot_message", SlackService.toChannelMessage(bot).subtype());

        Message human = new Message();
        human.setTs("2.000000");
        human.setUser("U1");
        human.setText("hej");
        human.setReplyCount(null);
        SlackService.SlackChannelMessage converted = SlackService.toChannelMessage(human);
        assertNull(converted.subtype());
        assertEquals(0, converted.replyCount(), "Slack omits reply_count on a non-parent; that is zero, not null");
        assertEquals("U1", converted.user());
    }
}
