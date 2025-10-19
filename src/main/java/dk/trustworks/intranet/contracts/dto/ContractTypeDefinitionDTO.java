package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for contract type definition.
 * Used when returning contract type data from REST APIs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContractTypeDefinitionDTO {

    private Integer id;
    private String code;
    private String name;
    private String description;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Convert entity to DTO.
     */
    public static ContractTypeDefinitionDTO fromEntity(ContractTypeDefinition entity) {
        return new ContractTypeDefinitionDTO(
            entity.getId(),
            entity.getCode(),
            entity.getName(),
            entity.getDescription(),
            entity.isActive(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    /**
     * Convert entity to DTO (convenience constructor).
     */
    public ContractTypeDefinitionDTO(ContractTypeDefinition entity) {
        this(
            entity.getId(),
            entity.getCode(),
            entity.getName(),
            entity.getDescription(),
            entity.isActive(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
