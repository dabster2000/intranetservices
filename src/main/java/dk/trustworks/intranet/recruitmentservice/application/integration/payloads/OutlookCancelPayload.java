package dk.trustworks.intranet.recruitmentservice.application.integration.payloads;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Payload for {@code OUTLOOK_EVENT_CANCEL} outbox rows. */
public record OutlookCancelPayload(
        String interviewUuid,
        String organizerMailbox,
        String eventId,
        String reason
) {
    @JsonCreator
    public OutlookCancelPayload(
            @JsonProperty("interviewUuid") String interviewUuid,
            @JsonProperty("organizerMailbox") String organizerMailbox,
            @JsonProperty("eventId") String eventId,
            @JsonProperty("reason") String reason) {
        this.interviewUuid = interviewUuid;
        this.organizerMailbox = organizerMailbox;
        this.eventId = eventId;
        this.reason = reason;
    }
}
