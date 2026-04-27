package dk.trustworks.intranet.recruitmentservice.infrastructure;

import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.request.conversations.ConversationsOpenRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.conversations.ConversationsOpenResponse;
import com.slack.api.model.Conversation;
import dk.trustworks.intranet.recruitmentservice.ports.slack.SendDmCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlackPortImplTest {

    private MethodsClient slack;
    private SlackUserResolver resolver;
    private SlackPortImpl impl;

    @BeforeEach
    void setUp() {
        slack = mock(MethodsClient.class);
        resolver = mock(SlackUserResolver.class);
        impl = new SlackPortImpl(slack, resolver);
    }

    @Test
    void sendDm_opens_conversation_then_posts_message() throws Exception {
        when(resolver.resolve("u-1")).thenReturn("alice");

        Conversation c = new Conversation();
        c.setId("D123");
        ConversationsOpenResponse open = new ConversationsOpenResponse();
        open.setOk(true);
        open.setChannel(c);
        when(slack.conversationsOpen(any(ConversationsOpenRequest.class))).thenReturn(open);

        ChatPostMessageResponse post = new ChatPostMessageResponse();
        post.setOk(true);
        post.setTs("1700000000.000100");
        when(slack.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(post);

        SendDmCommand cmd = new SendDmCommand("u-1", "Hi", "Reminder", "https://x.y/z", "key-1");
        impl.sendDirectMessage(cmd);

        ArgumentCaptor<ChatPostMessageRequest> sent = ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        verify(slack).chatPostMessage(sent.capture());
        assertEquals("D123", sent.getValue().getChannel());
        assertTrue(sent.getValue().getText().contains("https://x.y/z"));
    }

    @Test
    void sendDm_429_is_retryable() throws Exception {
        when(resolver.resolve("u-1")).thenReturn("alice");
        Conversation c = new Conversation();
        c.setId("D123");
        ConversationsOpenResponse open = new ConversationsOpenResponse();
        open.setOk(true);
        open.setChannel(c);
        when(slack.conversationsOpen(any(ConversationsOpenRequest.class))).thenReturn(open);
        ChatPostMessageResponse post = new ChatPostMessageResponse();
        post.setOk(false);
        post.setError("ratelimited");
        when(slack.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(post);

        SlackException ex = assertThrows(SlackException.class, () -> impl.sendDirectMessage(
                new SendDmCommand("u-1", "h", "b", "lnk", "k")));
        assertTrue(ex.isRetryable());
    }

    @Test
    void sendDm_user_not_found_propagates_terminal() {
        when(resolver.resolve("u-x")).thenThrow(new SlackException(false, "user_not_found", "u-x"));
        SlackException ex = assertThrows(SlackException.class, () -> impl.sendDirectMessage(
                new SendDmCommand("u-x", "h", "b", "lnk", "k")));
        assertFalse(ex.isRetryable());
        assertEquals("user_not_found", ex.getErrorCode());
    }
}
