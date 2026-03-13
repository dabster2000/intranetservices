package dk.trustworks.intranet.security.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateClientRequest(
        @JsonProperty("client_id")
        @NotBlank @Size(min = 3, max = 100)
        @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]*$",
                message = "client_id must start with alphanumeric and contain only alphanumeric, dots, hyphens, underscores")
        String clientId,

        @NotBlank @Size(max = 255)
        String name,

        @Size(max = 2000)
        String description,

        @NotNull @Size(min = 1)
        Set<@NotBlank @Size(max = 100) String> scopes,

        @JsonProperty("token_ttl_seconds")
        Integer tokenTtlSeconds
) {}
