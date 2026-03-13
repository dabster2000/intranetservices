package dk.trustworks.intranet.security.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenErrorResponse(
        String error,

        @JsonProperty("error_description")
        String errorDescription
) {}
