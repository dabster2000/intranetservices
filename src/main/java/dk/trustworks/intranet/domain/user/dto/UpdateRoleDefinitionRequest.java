package dk.trustworks.intranet.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating a role definition's display label.
 */
public record UpdateRoleDefinitionRequest(
        @NotBlank
        @Size(max = 100)
        String displayLabel
) {}
