package dk.trustworks.intranet.contracts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Request DTO for creating a new contract type definition.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateContractTypeRequest {

    /**
     * Unique code for the contract type (e.g., "SKI0217_2026").
     * Must be alphanumeric with underscores, 3-50 characters, uppercase.
     */
    @NotBlank(message = "Contract type code is required")
    @Size(min = 3, max = 50, message = "Code must be between 3 and 50 characters")
    @Pattern(regexp = "^[A-Z0-9_]+$", message = "Code must contain only uppercase letters, numbers, and underscores")
    private String code;

    /**
     * Display name for the contract type.
     */
    @NotBlank(message = "Contract type name is required")
    @Size(max = 255, message = "Name must not exceed 255 characters")
    private String name;

    /**
     * Detailed description of the contract type.
     */
    private String description;

    /**
     * Whether the contract type is active (defaults to true).
     */
    private boolean active = true;
}
