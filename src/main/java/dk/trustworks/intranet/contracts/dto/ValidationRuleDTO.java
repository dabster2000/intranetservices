package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.model.ContractValidationRuleEntity;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO for contract validation rules.
 * Used when returning validation rule data from REST APIs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidationRuleDTO {

    private Integer id;
    private String contractTypeCode;
    private String ruleId;
    private String label;
    private ValidationType validationType;
    private boolean required;
    private BigDecimal thresholdValue;
    private String configJson;
    private Integer priority;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Convert entity to DTO.
     */
    public static ValidationRuleDTO fromEntity(ContractValidationRuleEntity entity) {
        return new ValidationRuleDTO(
            entity.getId(),
            entity.getContractTypeCode(),
            entity.getRuleId(),
            entity.getLabel(),
            entity.getValidationType(),
            entity.isRequired(),
            entity.getThresholdValue(),
            entity.getConfigJson(),
            entity.getPriority(),
            entity.isActive(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    /**
     * Convert entity to DTO (convenience constructor).
     */
    public ValidationRuleDTO(ContractValidationRuleEntity entity) {
        this(
            entity.getId(),
            entity.getContractTypeCode(),
            entity.getRuleId(),
            entity.getLabel(),
            entity.getValidationType(),
            entity.isRequired(),
            entity.getThresholdValue(),
            entity.getConfigJson(),
            entity.getPriority(),
            entity.isActive(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
