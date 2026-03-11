package dk.trustworks.intranet.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new role definition.
 */
public record CreateRoleDefinitionRequest(
        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "Role name must be UPPER_SNAKE_CASE")
        String name,

        @NotBlank
        @Size(max = 100)
        String displayLabel
) {}
