package dk.trustworks.intranet.security.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Paginated wrapper for audit log queries.
 */
public record AuditLogPageResponse(
        List<AuditLogEntryResponse> items,
        @JsonProperty("totalCount") long totalCount,
        int page,
        int size
) {}
