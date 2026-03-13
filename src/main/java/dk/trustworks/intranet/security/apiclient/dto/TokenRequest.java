package dk.trustworks.intranet.security.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record TokenRequest(
        @NotBlank
        @JsonProperty("client_id")
        String clientId,

        @NotBlank
        @JsonProperty("client_secret")
        String clientSecret
) {}
