package dk.trustworks.intranet.communicationsservice.services;

import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.model.BotProfile;
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

    /**
     * MUTHER — the intranet's own mother bot — posts into the channels both CRM lanes
     * read. Testing bot_id alone let two of its three shapes through: an app posting while
     * appearing as a bot USER carries app_id with no bot_id, and some
     * conversations.replies payloads carry only bot_profile. Either one reaching the
     * digest means the intranet's own notifications are read as colleague conversation
     * and, on the source-channel lane, mined for client names — the CRM reading its own
     * output back in as client news.
     */
    @Test
    void anAppPostIsABotPostWhicheverFieldSlackSets() {
        Message byAppId = new Message();
        byAppId.setTs("3.000000");
        byAppId.setUser("U0BOTUSER");
        byAppId.setAppId("A0MUTHER");
        byAppId.setText("Ny bugrapport: #4711");
        assertEquals("bot_message", SlackService.toChannelMessage(byAppId).subtype(),
                "an app posting as a bot user has no bot_id");

        Message byProfile = new Message();
        byProfile.setTs("4.000000");
        byProfile.setUser("U0BOTUSER");
        byProfile.setBotProfile(new BotProfile());
        byProfile.setText("Ugentlig digest");
        assertEquals("bot_message", SlackService.toChannelMessage(byProfile).subtype());

        Message realSubtype = new Message();
        realSubtype.setTs("5.000000");
        realSubtype.setUser("U1");
        realSubtype.setSubtype("channel_join");
        assertEquals("channel_join", SlackService.toChannelMessage(realSubtype).subtype(),
                "a genuine subtype is never overwritten");
    }
}
