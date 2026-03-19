package dk.trustworks.intranet.security.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Single audit log entry in the paginated audit log response.
 * The clientId is resolved from the ApiClient aggregate by UUID join.
 */
public record AuditLogEntryResponse(
        long id,
        @JsonProperty("client_uuid") String clientUuid,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("ip_address") String ipAddress,
        String details,
        @JsonProperty("created_at") LocalDateTime createdAt
) {}
