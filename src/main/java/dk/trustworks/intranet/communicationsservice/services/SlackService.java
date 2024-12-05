package dk.trustworks.intranet.communicationsservice.services;

import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.conversations.*;
import dk.trustworks.intranet.userservice.model.User;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.util.Collections;

@JBossLog
@ApplicationScoped
public class SlackService {

    @ConfigProperty(name = "slack.motherSlackBotToken")
    String motherSlackBotToken;

    @ConfigProperty(name = "slack.adminSlackBotToken")
    String adminSlackBotToken;

    public void sendMessage(User user, String textMessage) throws SlackApiException, IOException {
        Slack slack = Slack.getInstance();
        log.info("Sending message to "+user.getUsername());
        ChatPostMessageResponse response = slack.methods(motherSlackBotToken).chatPostMessage(req -> req
                .channel(user.getSlackusername()) // Channel ID
                .text(textMessage));
        log.info("Response received: " + response.getMessage());
    }

    public void sendMessage(String channel, String message) {
        try {
        Slack slack = Slack.getInstance();
            ChatPostMessageResponse response = slack.methods(motherSlackBotToken)
                    .chatPostMessage(req -> req
                            .channel(channel)
                            .text(message));

            if (!response.isOk()) {
                System.err.println("Failed to send message due to error: " + response.getError());
            }
        } catch (Exception e) {
            System.err.println("Error sending Slack message: " + e.getMessage());
        }
    }

    public void addUserToChannel(User user, String channelID) {
        Slack slack = Slack.getInstance();
        try {
            ConversationsInviteResponse inviteResponse = slack.methods(adminSlackBotToken).conversationsInvite(req -> req.channel(channelID).users(Collections.singletonList(user.getSlackusername())));
            if (!inviteResponse.isOk()) {
                System.err.println("Error adding user to channel: " + user.getEmail());
                return;
            }
            System.out.println("User " + user.getUsername() + " successfully added to the channel with ID: " + channelID);
        } catch (IOException | SlackApiException e) {
            System.err.println("Error interacting with Slack API");
            e.printStackTrace();
        }
    }

    public void removeUserFromChannel(User user, String channelID) {
        Slack slack = Slack.getInstance();
        try {
            ConversationsKickResponse response = slack.methods(adminSlackBotToken).conversationsKick(req -> req.channel(channelID).user(user.getSlackusername()));
            if (!response.isOk()) {
                System.err.println("Error removing user from channel: " + user.getEmail());
                return;
            }
            System.out.println("User " + user.getUsername() + " successfully removed from the channel with ID: " + channelID);
        } catch (IOException | SlackApiException e) {
            System.err.println("Error interacting with Slack API");
            e.printStackTrace();
        }
    }

    public String createChannel(String channelName) throws IOException, SlackApiException {
        Slack slack = Slack.getInstance();
        ConversationsCreateResponse response = slack.methods(adminSlackBotToken).conversationsCreate(r -> r.name(channelName));

        if (!response.isOk()) {
            System.out.println("response.getError() = " + response.getError());
        }

        return response.getChannel().getId();
    }

    public boolean closeChannel(String channelName) throws IOException, SlackApiException {
        Slack slack = Slack.getInstance();
        ConversationsArchiveResponse response = slack.methods(adminSlackBotToken).conversationsArchive(r -> r.channel(channelName));

        if (!response.isOk()) {
            System.out.println("response.getError() = " + response.getError());
        }

        return response.isOk();
    }

}