package dk.trustworks.intranet.communicationsservice.services;

import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.conversations.*;
import com.slack.api.methods.response.users.UsersLookupByEmailResponse;
import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.HeaderBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.TextObject;
import dk.trustworks.intranet.communicationsservice.dto.NewLeadNotificationDTO;
import dk.trustworks.intranet.domain.user.entity.User;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.slack.api.model.block.Blocks.*;
import static com.slack.api.model.block.composition.BlockCompositions.*;
import static com.slack.api.model.block.element.BlockElements.*;

@JBossLog
@ApplicationScoped
public class SlackService {

    /** Slack rejects a message with {@code invalid_blocks} if any text object exceeds this. */
    private static final int SLACK_TEXT_OBJECT_MAX_CHARS = 3000;

    /** Slack's hard cap on notification text is 40.000; it recommends staying under 4.000. */
    private static final int SLACK_FALLBACK_TEXT_MAX_CHARS = 4000;

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
        sendMessage(channel, message, "mother");
    }

    /**
     * Posts a plain-text message to the given Slack channel using the bot
     * token selected by {@code tokenKey}.
     *
     * <p>Token selection: {@code "admin"} (case-insensitive) → {@code adminSlackBotToken};
     * any other value (including {@code null} / blank / {@code "mother"})
     * → {@code motherSlackBotToken}.
     *
     * <p>Token contents are never logged — only the {@code tokenKey} label.
     *
     * @param channel  Slack channel ID
     * @param message  plain-text message body (Slack mrkdwn supported)
     * @param tokenKey {@code "mother"} or {@code "admin"} — selects which bot
     *                 token to authenticate with
     */
    public void sendMessage(String channel, String message, String tokenKey) {
        String resolvedKey = (tokenKey != null && tokenKey.equalsIgnoreCase("admin"))
                ? "admin" : "mother";
        String token = "admin".equals(resolvedKey) ? adminSlackBotToken : motherSlackBotToken;
        try {
            Slack slack = Slack.getInstance();
            ChatPostMessageResponse response = slack.methods(token)
                    .chatPostMessage(req -> req
                            .channel(channel)
                            .text(message));

            if (!response.isOk()) {
                log.errorf("Failed to send Slack message via tokenKey=%s channel=%s: %s",
                        resolvedKey, channel, response.getError());
            }
        } catch (Exception e) {
            log.errorf(e, "Error sending Slack message via tokenKey=%s channel=%s: %s",
                    resolvedKey, channel, e.getMessage());
        }
    }

    /**
     * Posts a Block Kit message to the given channel using the mother bot token.
     * Best-effort: logs (label + channel only, never the token) and swallows on
     * failure; never throws. {@code fallbackText} is the notification/preview text.
     */
    public void sendMessage(String channel, String fallbackText, java.util.List<com.slack.api.model.block.LayoutBlock> blocks) {
        try {
            Slack slack = Slack.getInstance();
            ChatPostMessageResponse response = slack.methods(motherSlackBotToken)
                    .chatPostMessage(req -> req
                            .channel(channel)
                            .text(fallbackText)
                            .blocks(blocks));
            if (!response.isOk()) {
                log.errorf("Failed to send Slack block message channel=%s: %s", channel, response.getError());
            }
        } catch (Exception e) {
            log.errorf(e, "Error sending Slack block message channel=%s: %s", channel, e.getMessage());
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

        List<LayoutBlock> blocks = buildNewLeadBlocks(dto);
        String fallbackText = newLeadFallback(dto);

        // Send the message
        ChatPostMessageResponse response = slack.methods(motherSlackBotToken).chatPostMessage(req -> req
            .channel(channel)
            .text(fallbackText) // Fallback text for notifications
            .blocks(blocks)
        );

        if (!response.isOk()) {
            logNewLeadRejection(response, blocks);
            throw new RuntimeException("Slack API error: " + response.getError());
        }

        log.info("New lead notification sent successfully. Message ts: " + response.getTs());
    }

    /**
     * Builds the Block Kit layout for a new lead. Package-private so the layout can be asserted in a
     * unit test without a Slack client — {@link LayoutBlock}s are plain objects until they are sent.
     *
     * <p>Lead free-text arrives from {@code sales_lead.description} and
     * {@code sales_lead.detailed_description}, both {@code TEXT} columns, so every text object that
     * embeds them is clamped to Slack's limit before it is added — see {@link #clampText}.
     */
    List<LayoutBlock> buildNewLeadBlocks(NewLeadNotificationDTO dto) {
        // Construct the deep link URL to the lead
        String leadUrl = constructLeadUrl(dto.getLeadUuid());

        // Build the Block Kit message with blocks list
        List<LayoutBlock> blocks = new ArrayList<>();

        // Header
        blocks.add(header(h -> h.text(plainText("🎯 New Sales Lead Created"))));

        // Client and description section
        String clientText = clampText(
            "*" + dto.getClientName() + "*\n" +
            (dto.getDescription() != null ? dto.getDescription() : "No description provided"),
            SLACK_TEXT_OBJECT_MAX_CHARS, "description");
        blocks.add(section(s -> s.text(markdownText(clientText))));

        // Divider
        blocks.add(divider());

        // Detailed description (if available)
        if (dto.getDetailedDescription() != null && !dto.getDetailedDescription().trim().isEmpty()) {
            String detailedText = clampText(
                "*Detailed Description:*\n" + dto.getDetailedDescription(),
                SLACK_TEXT_OBJECT_MAX_CHARS, "detailedDescription");
            blocks.add(section(s -> s.text(markdownText(detailedText))));
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

        return blocks;
    }

    /**
     * Builds the notification/preview text shown where blocks cannot render. Package-private for test.
     */
    String newLeadFallback(NewLeadNotificationDTO dto) {
        return clampText("New Sales Lead: " + dto.getClientName() + " - " + dto.getDescription(),
                SLACK_FALLBACK_TEXT_MAX_CHARS, "fallback text");
    }

    /**
     * Clamps an assembled Block Kit string to a Slack character limit.
     *
     * <p>Slack rejects the <em>entire</em> message with {@code invalid_blocks} when a single text
     * object overflows, so an over-long lead field would otherwise cost the whole notification.
     * Truncating is the deliberate trade: a shortened notification beats a silently lost one.
     *
     * <p>Clamps the assembled string rather than the raw field, because the literal labels
     * ({@code "*Detailed Description:*\n"}) count against the same budget.
     *
     * @param label field name for the warning — never the field's value, which is customer content
     */
    private String clampText(String text, int maxChars, String label) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        log.warnf("New lead notification: %s clamped from %d to %d chars to fit Slack's limit",
                label, text.length(), maxChars);
        return StringUtils.abbreviate(text, maxChars);
    }

    /**
     * Explains a Slack rejection well enough to act on without a data hunt. {@code response_metadata}
     * carries Slack's per-block complaint (e.g. {@code "[ERROR] must be less than 3001 characters
     * [json-pointer:/blocks/2/text/text]"}), which is the only pointer to the offending block.
     *
     * <p>Lead free-text is customer content, so blocks are described by type and text <em>length</em>
     * only. Never serialize the payload here — that would put the lead's description in the logs.
     */
    private void logNewLeadRejection(ChatPostMessageResponse response, List<LayoutBlock> blocks) {
        List<String> messages = response.getResponseMetadata() != null
                && response.getResponseMetadata().getMessages() != null
                ? response.getResponseMetadata().getMessages().stream()
                        // Slack authors these, but clamp in case a complaint echoes the offending value.
                        .map(m -> StringUtils.abbreviate(m, 300))
                        .collect(Collectors.toList())
                : Collections.emptyList();

        log.errorf("Failed to send new lead notification: error=%s, warning=%s, messages=%s, blocks=%s",
                response.getError(), response.getWarning(), messages, describeBlockShape(blocks));
    }

    /**
     * Renders the shape of a block list for diagnostics — types and text lengths, never text.
     */
    private static String describeBlockShape(List<LayoutBlock> blocks) {
        return java.util.stream.IntStream.range(0, blocks.size())
                .mapToObj(i -> i + ":" + blocks.get(i).getType() + textLengthsOf(blocks.get(i)))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String textLengthsOf(LayoutBlock block) {
        if (block instanceof SectionBlock section) {
            String text = section.getText() != null ? "(text=" + section.getText().getText().length() + ")" : "";
            String fields = section.getFields() != null
                    ? section.getFields().stream()
                            .map(f -> String.valueOf(f.getText().length()))
                            .collect(Collectors.joining(",", "(fields=", ")"))
                    : "";
            return text + fields;
        }
        if (block instanceof HeaderBlock header && header.getText() != null) {
            return "(text=" + header.getText().getText().length() + ")";
        }
        if (block instanceof ContextBlock context && context.getElements() != null) {
            return context.getElements().stream()
                    .filter(TextObject.class::isInstance)
                    .map(e -> String.valueOf(((TextObject) e).getText().length()))
                    .collect(Collectors.joining(",", "(elements=", ")"));
        }
        return "";
    }

    /**
     * Constructs the full URL to view a specific lead in the Trustworks Intranet application.
     * The URL follows the pattern: {baseUrl}/sales-leads?lead={leadUuid}
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
        String fullUrl = baseUrl + "/sales-leads?lead=" + leadUuid;
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

    /**
     * Sends a notification to a user when their signed document has been
     * uploaded to SharePoint. Uses Block Kit for a clean, professional layout.
     *
     * @param user The user to notify (must have slackusername set)
     * @param documentName The name of the signed document
     * @param completedAt When the document was completed/uploaded
     * @throws IOException If there's an I/O error communicating with Slack
     * @throws SlackApiException If Slack API returns an error
     */
    public void sendSignedDocumentNotification(User user, String documentName, LocalDateTime completedAt)
            throws IOException, SlackApiException {
        Slack slack = Slack.getInstance();

        log.infof("Sending signed document notification to %s for document: %s",
            user.getUsername(), documentName);

        // Construct the profile view URL
        String profileUrl = constructProfileUrl();

        // Build Block Kit message
        java.util.List<com.slack.api.model.block.LayoutBlock> blocks = new java.util.ArrayList<>();

        // Section with main message
        blocks.add(section(s -> s.text(markdownText(
            ":white_check_mark: *Document Signed & Uploaded*\n" +
            "Your document *" + documentName + "* has been signed by all parties and uploaded to SharePoint."
        ))));

        // Divider
        blocks.add(divider());

        // Context footer with timestamp and link
        String timestamp = completedAt != null
            ? completedAt.format(DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm"))
            : LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm"));

        if (profileUrl != null) {
            blocks.add(context(c -> c.elements(asContextElements(
                markdownText("Completed: " + timestamp + " | <" + profileUrl + "|View Documents>")
            ))));
        } else {
            blocks.add(context(c -> c.elements(asContextElements(
                markdownText("Completed: " + timestamp)
            ))));
        }

        // Send the message as DM to user
        ChatPostMessageResponse response = slack.methods(motherSlackBotToken).chatPostMessage(req -> req
            .channel(user.getSlackusername())
            .text("Your document \"" + documentName + "\" has been signed and uploaded to SharePoint.")
            .blocks(blocks)
        );

        if (!response.isOk()) {
            log.errorf("Failed to send signed document notification to %s: %s",
                user.getUsername(), response.getError());
            throw new RuntimeException("Slack API error: " + response.getError());
        }

        log.infof("Signed document notification sent successfully to %s. Message ts: %s",
            user.getUsername(), response.getTs());
    }

    /**
     * Constructs the profile view URL for the current application.
     *
     * @return Full URL to the profile view, or null if base URL not configured
     */
    private String constructProfileUrl() {
        if (applicationBaseUrl == null || applicationBaseUrl.trim().isEmpty()) {
            log.warn("Application base URL not configured, cannot construct profile link");
            return null;
        }

        String baseUrl = applicationBaseUrl.trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return baseUrl + "/profile-view";
    }

}