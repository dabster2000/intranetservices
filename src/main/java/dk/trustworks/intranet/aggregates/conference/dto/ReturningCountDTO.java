package dk.trustworks.intranet.aggregates.conference.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReturningCountDTO(
        long returning,
        @JsonProperty("new") long newParticipants,
        long total
) {}
