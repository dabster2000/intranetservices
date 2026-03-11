package dk.trustworks.intranet.domain.user.dto;

import java.time.LocalDateTime;

/**
 * Response DTO for role definitions, includes usage count.
 */
public record RoleDefinitionDTO(
        String name,
        String displayLabel,
        boolean isSystem,
        long usageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
