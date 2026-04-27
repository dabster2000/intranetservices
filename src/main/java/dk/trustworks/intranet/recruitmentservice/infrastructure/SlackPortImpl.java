package dk.trustworks.intranet.recruitmentservice.infrastructure;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.request.conversations.ConversationsOpenRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.conversations.ConversationsOpenResponse;
import dk.trustworks.intranet.recruitmentservice.ports.SlackPort;
import dk.trustworks.intranet.recruitmentservice.ports.slack.SendDmCommand;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Live Slack DM adapter. Resolves the recipient via {@link SlackUserResolver}, opens a
 * direct-message channel via {@code conversations.open}, then posts the message via
 * {@code chat.postMessage}.
 *
 * <p>Registered with {@code @Alternative @Priority(10)} so it overrides
 * {@link NoopSlackPort} ({@code @Priority(1)}) in any profile that also configures
 * {@code recruitment.slack.bot-token}. Mirrors {@code OpenAIPortImpl} from Slice 2.
 */
@ApplicationScoped
@Alternative
@Priority(10)
public class SlackPortImpl implements SlackPort {

    private static final Logger LOG = Logger.getLogger(SlackPortImpl.class);
    private static final Set<String> RETRYABLE = Set.of("ratelimited", "service_unavailable", "internal_error");

    private final MethodsClient slack;
    private final SlackUserResolver resolver;

    @Inject
    public SlackPortImpl(MethodsClient slack, SlackUserResolver resolver) {
        this.slack = slack;
        this.resolver = resolver;
    }

    @ApplicationScoped
    static class Producer {
        @ConfigProperty(name = "recruitment.slack.bot-token") String token;

        @Produces
        @ApplicationScoped
        MethodsClient methods() {
            return Slack.getInstance().methods(token);
        }
    }

    @Override
    public void sendDirectMessage(SendDmCommand cmd) {
        String slackUser = resolver.resolve(cmd.recipientUserUuid()); // throws terminal if missing

        try {
            ConversationsOpenResponse open = slack.conversationsOpen(
                    ConversationsOpenRequest.builder().users(List.of(slackUser)).build());
            if (!open.isOk()) {
                throw mapSlackError(open.getError(), "conversations.open");
            }
            String channelId = open.getChannel().getId();

            String text = "*" + cmd.headline() + "*\n" + cmd.bodyMarkdown() + "\n" + cmd.deepLinkUrl();
            ChatPostMessageResponse post = slack.chatPostMessage(
                    ChatPostMessageRequest.builder()
                            .channel(channelId)
                            .text(text)
                            .build());
            if (!post.isOk()) {
                throw mapSlackError(post.getError(), "chat.postMessage");
            }
            LOG.infof("Slack DM sent recipient=%s key=%s ts=%s",
                    cmd.recipientUserUuid(), cmd.idempotencyKey(), post.getTs());
        } catch (SlackApiException ex) {
            throw new SlackException(true, "slack_api_exception", ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new SlackException(true, "slack_io", ex.getMessage(), ex);
        }
    }

    static SlackException mapSlackError(String code, String op) {
        boolean retryable = code != null && RETRYABLE.contains(code);
        return new SlackException(retryable, code == null ? "unknown" : code, op);
    }
}
