package dk.trustworks.intranet.recruitmentservice.application.integration.payloads;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Payload for both Slack DM outbox kinds (interview-tomorrow + scorecard-overdue). */
public record SlackDmPayload(
        String recipientUserUuid,
        String headline,
        String bodyMarkdown,
        String deepLinkUrl,
        String idempotencyKey
) {
    @JsonCreator
    public SlackDmPayload(
            @JsonProperty("recipientUserUuid") String recipientUserUuid,
            @JsonProperty("headline") String headline,
            @JsonProperty("bodyMarkdown") String bodyMarkdown,
            @JsonProperty("deepLinkUrl") String deepLinkUrl,
            @JsonProperty("idempotencyKey") String idempotencyKey) {
        this.recipientUserUuid = recipientUserUuid;
        this.headline = headline;
        this.bodyMarkdown = bodyMarkdown;
        this.deepLinkUrl = deepLinkUrl;
        this.idempotencyKey = idempotencyKey;
    }
}
