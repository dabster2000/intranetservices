package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response DTO for pricing rule step.
 * Used when returning pricing rule data from REST APIs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PricingRuleStepDTO {

    private Integer id;
    private String contractTypeCode;
    private String ruleId;
    private String label;
    private RuleStepType ruleStepType;
    private StepBase stepBase;
    private BigDecimal percent;
    private BigDecimal amount;
    private String paramKey;
    private LocalDate validFrom;
    private LocalDate validTo;
    private Integer priority;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Convert entity to DTO.
     */
    public static PricingRuleStepDTO fromEntity(PricingRuleStepEntity entity) {
        return new PricingRuleStepDTO(
            entity.getId(),
            entity.getContractTypeCode(),
            entity.getRuleId(),
            entity.getLabel(),
            entity.getRuleStepType(),
            entity.getStepBase(),
            entity.getPercent(),
            entity.getAmount(),
            entity.getParamKey(),
            entity.getValidFrom(),
            entity.getValidTo(),
            entity.getPriority(),
            entity.isActive(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    /**
     * Convert entity to DTO (convenience constructor).
     */
    public PricingRuleStepDTO(PricingRuleStepEntity entity) {
        this(
            entity.getId(),
            entity.getContractTypeCode(),
            entity.getRuleId(),
            entity.getLabel(),
            entity.getRuleStepType(),
            entity.getStepBase(),
            entity.getPercent(),
            entity.getAmount(),
            entity.getParamKey(),
            entity.getValidFrom(),
            entity.getValidTo(),
            entity.getPriority(),
            entity.isActive(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
