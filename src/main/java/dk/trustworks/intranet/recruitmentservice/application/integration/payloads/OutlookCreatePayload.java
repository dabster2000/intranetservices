package dk.trustworks.intranet.recruitmentservice.application.integration.payloads;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/** Payload for {@code OUTLOOK_EVENT_CREATE} outbox rows. */
public record OutlookCreatePayload(
        String interviewUuid,
        String organizerMailbox,
        String subject,
        String bodyHtml,
        Instant startUtc,
        Instant endUtc,
        List<String> attendeeEmails,
        boolean teamsEnabled
) {
    @JsonCreator
    public OutlookCreatePayload(
            @JsonProperty("interviewUuid") String interviewUuid,
            @JsonProperty("organizerMailbox") String organizerMailbox,
            @JsonProperty("subject") String subject,
            @JsonProperty("bodyHtml") String bodyHtml,
            @JsonProperty("startUtc") Instant startUtc,
            @JsonProperty("endUtc") Instant endUtc,
            @JsonProperty("attendeeEmails") List<String> attendeeEmails,
            @JsonProperty("teamsEnabled") boolean teamsEnabled) {
        this.interviewUuid = interviewUuid;
        this.organizerMailbox = organizerMailbox;
        this.subject = subject;
        this.bodyHtml = bodyHtml;
        this.startUtc = startUtc;
        this.endUtc = endUtc;
        this.attendeeEmails = attendeeEmails;
        this.teamsEnabled = teamsEnabled;
    }
}
