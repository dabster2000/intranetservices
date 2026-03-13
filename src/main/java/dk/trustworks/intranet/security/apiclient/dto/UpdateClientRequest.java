package dk.trustworks.intranet.security.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

public record UpdateClientRequest(
        @Size(max = 255)
        String name,

        @Size(max = 2000)
        String description,

        @JsonProperty("token_ttl_seconds")
        Integer tokenTtlSeconds
) {}
