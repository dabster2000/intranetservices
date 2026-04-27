package dk.trustworks.intranet.recruitmentservice.application.integration.payloads;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/** Payload for {@code OUTLOOK_EVENT_UPDATE} outbox rows. */
public record OutlookUpdatePayload(
        String interviewUuid,
        String organizerMailbox,
        String eventId,
        Instant startUtc,
        Instant endUtc,
        List<String> attendeeEmails
) {
    @JsonCreator
    public OutlookUpdatePayload(
            @JsonProperty("interviewUuid") String interviewUuid,
            @JsonProperty("organizerMailbox") String organizerMailbox,
            @JsonProperty("eventId") String eventId,
            @JsonProperty("startUtc") Instant startUtc,
            @JsonProperty("endUtc") Instant endUtc,
            @JsonProperty("attendeeEmails") List<String> attendeeEmails) {
        this.interviewUuid = interviewUuid;
        this.organizerMailbox = organizerMailbox;
        this.eventId = eventId;
        this.startUtc = startUtc;
        this.endUtc = endUtc;
        this.attendeeEmails = attendeeEmails;
    }
}
