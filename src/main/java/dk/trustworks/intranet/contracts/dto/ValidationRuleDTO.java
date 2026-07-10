package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.model.ContractValidationRuleEntity;
import dk.trustworks.intranet.contracts.model.enums.LifecycleStatus;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO for contract validation rules.
 * Used when returning validation rule data from REST APIs.
 *
 * <p>{@code status} is derived at mapping time via
 * {@link LifecycleStatus#forValidationRule(boolean)} — validation rules have
 * no date columns, so only ACTIVE / DISABLED.
 */
@Data
@NoArgsConstructor
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

    /** Derived lifecycle status — ACTIVE / DISABLED. Never persisted. */
    private LifecycleStatus status;

    /**
     * Convert entity to DTO.
     */
    public static ValidationRuleDTO fromEntity(ContractValidationRuleEntity entity) {
        return new ValidationRuleDTO(entity);
    }

    /**
     * Convert entity to DTO (convenience constructor).
     */
    public ValidationRuleDTO(ContractValidationRuleEntity entity) {
        this.id = entity.getId();
        this.contractTypeCode = entity.getContractTypeCode();
        this.ruleId = entity.getRuleId();
        this.label = entity.getLabel();
        this.validationType = entity.getValidationType();
        this.required = entity.isRequired();
        this.thresholdValue = entity.getThresholdValue();
        this.configJson = entity.getConfigJson();
        this.priority = entity.getPriority();
        this.active = entity.isActive();
        this.createdAt = entity.getCreatedAt();
        this.updatedAt = entity.getUpdatedAt();
        this.status = LifecycleStatus.forValidationRule(entity.isActive());
    }
}
