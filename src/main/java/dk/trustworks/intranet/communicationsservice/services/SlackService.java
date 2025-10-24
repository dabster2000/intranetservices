package dk.trustworks.intranet.communicationsservice.services;

import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.conversations.*;
import com.slack.api.methods.response.users.UsersLookupByEmailResponse;
import dk.trustworks.intranet.communicationsservice.dto.NewLeadNotificationDTO;
import dk.trustworks.intranet.domain.user.entity.User;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

import static com.slack.api.model.block.Blocks.*;
import static com.slack.api.model.block.composition.BlockCompositions.*;
import static com.slack.api.model.block.element.BlockElements.*;

@JBossLog
@ApplicationScoped
public class SlackService {

    @ConfigProperty(name = "slack.motherSlackBotToken")
    String motherSlackBotToken;

    @ConfigProperty(name = "slack.adminSlackBotToken")
    String adminSlackBotToken;

    @ConfigProperty(name = "quarkus.application.base-url")
    String applicationBaseUrl;

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

    public String findUserIdByEmail(String email) throws IOException, SlackApiException {
        Slack slack = Slack.getInstance();
        log.info("Looking up Slack user for email " + email);
        UsersLookupByEmailResponse response = slack.methods(adminSlackBotToken)
                .usersLookupByEmail(r -> r.email(email));
        if (!response.isOk()) {
            log.warn("Slack lookup failed for " + email + ": " + response.getError());
            return null;
        }
        return response.getUser() != null ? response.getUser().getId() : null;
    }

    /**
     * Sends a beautifully formatted Block Kit message to Slack when a new sales lead is created.
     * The message includes all key information about the lead in a visually appealing layout.
     *
     * @param dto The new lead notification data
     * @throws IOException If there's an I/O error communicating with Slack
     * @throws SlackApiException If Slack API returns an error
     */
    public void sendNewLeadNotification(NewLeadNotificationDTO dto) throws IOException, SlackApiException {
        Slack slack = Slack.getInstance();
        String channel = "#sales_team";

        log.info("Sending new lead notification to " + channel + " for client: " + dto.getClientName());

        // Construct the deep link URL to the lead
        String leadUrl = constructLeadUrl(dto.getLeadUuid());

        // Build the Block Kit message with blocks list
        java.util.List<com.slack.api.model.block.LayoutBlock> blocks = new java.util.ArrayList<>();

        // Header
        blocks.add(header(h -> h.text(plainText("🎯 New Sales Lead Created"))));

        // Client and description section
        blocks.add(section(s -> s.text(markdownText(
            "*" + dto.getClientName() + "*\n" +
            (dto.getDescription() != null ? dto.getDescription() : "No description provided")
        ))));

        // Divider
        blocks.add(divider());

        // Detailed description (if available)
        if (dto.getDetailedDescription() != null && !dto.getDetailedDescription().trim().isEmpty()) {
            blocks.add(section(s -> s.text(markdownText(
                "*Detailed Description:*\n" + dto.getDetailedDescription()
            ))));
        }

        // Key information in two-column layout
        blocks.add(section(s -> s.fields(asSectionFields(
            markdownText("*Status*\n" + getStatusEmoji(dto.getStatus()) + " " + dto.getStatus()),
            markdownText("*Allocation*\n" + (dto.getAllocation() != null ? dto.getAllocation() + "%" : "N/A")),
            markdownText("*Period*\n" + (dto.getPeriod() != null ? dto.getPeriod() + " months" : "N/A")),
            markdownText("*Rate*\n" + (dto.getRate() != null ? String.format("%.0f kr", dto.getRate()) : "N/A")),
            markdownText("*Start Date*\n" + formatDate(dto.getCloseDate())),
            markdownText("*Lead Manager*\n👤 " + (dto.getLeadManagerName() != null ? dto.getLeadManagerName() : "Unassigned"))
        ))));

        // Context footer with optional link
        if (leadUrl != null) {
            blocks.add(context(c -> c.elements(asContextElements(
                markdownText("Created: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm")) +
                            " | Source: Trustworks Intranet | <" + leadUrl + "|View Lead>")
            ))));
        } else {
            blocks.add(context(c -> c.elements(asContextElements(
                markdownText("Created: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm")) +
                            " | Source: Trustworks Intranet")
            ))));
        }

        // Actions block with "View Lead" button (only if URL is valid)
        if (leadUrl != null) {
            blocks.add(actions(a -> a.elements(asElements(
                button(b -> b
                    .text(plainText("View Lead in Trustworks"))
                    .url(leadUrl)
                    .style("primary")  // Blue button for prominence
                )
            ))));
            log.info("Added View Lead button with URL: " + leadUrl);
        } else {
            log.warn("Lead UUID not provided or base URL not configured - button will not be included");
        }

        // Send the message
        ChatPostMessageResponse response = slack.methods(motherSlackBotToken).chatPostMessage(req -> req
            .channel(channel)
            .text("New Sales Lead: " + dto.getClientName() + " - " + dto.getDescription()) // Fallback text for notifications
            .blocks(blocks)
        );

        if (!response.isOk()) {
            log.error("Failed to send new lead notification: " + response.getError());
            throw new RuntimeException("Slack API error: " + response.getError());
        }

        log.info("New lead notification sent successfully. Message ts: " + response.getTs());
    }

    /**
     * Constructs the full URL to view a specific lead in the Trustworks Intranet application.
     * The URL follows the pattern: {baseUrl}/sales-list/{leadUuid}
     *
     * @param leadUuid The unique identifier of the lead
     * @return Full URL to the lead, or null if inputs are invalid
     */
    private String constructLeadUrl(String leadUuid) {
        // Validate lead UUID
        if (leadUuid == null || leadUuid.trim().isEmpty()) {
            log.warn("Lead UUID is null or empty, cannot construct deep link");
            return null;
        }

        // Validate base URL configuration
        if (applicationBaseUrl == null || applicationBaseUrl.trim().isEmpty()) {
            log.error("Application base URL not configured! Set APPLICATION_BASE_URL environment variable.");
            return null;
        }

        // Sanitize base URL (remove trailing slash if present)
        String baseUrl = applicationBaseUrl.trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        // Construct and return the full URL
        String fullUrl = baseUrl + "/sales-list/" + leadUuid;
        log.debug("Constructed lead URL: " + fullUrl);

        return fullUrl;
    }

    /**
     * Maps lead status to an appropriate emoji for visual clarity
     */
    private String getStatusEmoji(String status) {
        if (status == null) return "📋";

        return switch (status.toUpperCase()) {
            case "DETECTED" -> "🔍";
            case "QUALIFIED" -> "✅";
            case "PROPOSAL" -> "📄";
            case "SHORTLISTED" -> "⭐";
            case "NEGOTIATION" -> "🤝";
            case "WON" -> "🎉";
            case "LOST" -> "❌";
            default -> "📋";
        };
    }

    /**
     * Formats a LocalDate for display in Slack messages
     */
    private String formatDate(LocalDate date) {
        if (date == null) {
            return "Not set";
        }
        return date.format(DateTimeFormatter.ofPattern("MMM dd, yyyy"));
    }

}