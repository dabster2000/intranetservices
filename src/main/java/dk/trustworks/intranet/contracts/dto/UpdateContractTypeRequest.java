package dk.trustworks.intranet.contracts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Request DTO for updating an existing contract type definition.
 * Note: Code cannot be changed after creation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateContractTypeRequest {

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
     * Whether the contract type is active.
     */
    private boolean active;
}
