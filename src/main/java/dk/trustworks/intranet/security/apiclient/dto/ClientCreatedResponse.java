package dk.trustworks.intranet.security.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Response DTO returned only on client creation.
 * Includes the plaintext client_secret, which is shown exactly once.
 */
public record ClientCreatedResponse(
        String uuid,
        @JsonProperty("client_id") String clientId,
        String name,
        @JsonProperty("client_secret") String clientSecret,
        Set<String> scopes,
        @JsonProperty("token_ttl_seconds") int tokenTtlSeconds,
        boolean enabled,
        @JsonProperty("created_at") LocalDateTime createdAt
) {}
