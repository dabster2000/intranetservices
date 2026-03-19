package dk.trustworks.intranet.security.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.security.apiclient.model.ApiClient;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Response DTO for client detail/list endpoints.
 * Never includes the client_secret_hash.
 */
public record ClientResponse(
        String uuid,
        @JsonProperty("client_id") String clientId,
        String name,
        String description,
        Set<String> scopes,
        @JsonProperty("token_ttl_seconds") int tokenTtlSeconds,
        boolean enabled,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt,
        @JsonProperty("created_by") String createdBy
) {
    public static ClientResponse from(ApiClient client) {
        return new ClientResponse(
                client.getUuid(),
                client.getClientId(),
                client.getName(),
                client.getDescription(),
                client.getScopeNames(),
                client.getTokenTtlSeconds(),
                client.isEnabled(),
                client.getCreatedAt(),
                client.getUpdatedAt(),
                client.getCreatedBy()
        );
    }
}
