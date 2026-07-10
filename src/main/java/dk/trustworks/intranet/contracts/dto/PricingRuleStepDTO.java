package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import dk.trustworks.intranet.contracts.model.enums.LifecycleStatus;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response DTO for pricing rule step.
 * Used when returning pricing rule data from REST APIs.
 *
 * <p>{@code status} is derived at mapping time via
 * {@link LifecycleStatus#forPricingRule(boolean, LocalDate, LocalDate)}
 * (today in Europe/Copenhagen, {@code validTo} exclusive):
 * ACTIVE / SCHEDULED / EXPIRED / DISABLED. Note the pricing engine evaluates
 * rule dates against each invoice's date — this status is a "today" view.
 */
@Data
@NoArgsConstructor
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

    /** Derived lifecycle status — ACTIVE / SCHEDULED / EXPIRED / DISABLED. Never persisted. */
    private LifecycleStatus status;

    /**
     * Convert entity to DTO.
     */
    public static PricingRuleStepDTO fromEntity(PricingRuleStepEntity entity) {
        return new PricingRuleStepDTO(entity);
    }

    /**
     * Convert entity to DTO (convenience constructor).
     */
    public PricingRuleStepDTO(PricingRuleStepEntity entity) {
        this.id = entity.getId();
        this.contractTypeCode = entity.getContractTypeCode();
        this.ruleId = entity.getRuleId();
        this.label = entity.getLabel();
        this.ruleStepType = entity.getRuleStepType();
        this.stepBase = entity.getStepBase();
        this.percent = entity.getPercent();
        this.amount = entity.getAmount();
        this.paramKey = entity.getParamKey();
        this.validFrom = entity.getValidFrom();
        this.validTo = entity.getValidTo();
        this.priority = entity.getPriority();
        this.active = entity.isActive();
        this.createdAt = entity.getCreatedAt();
        this.updatedAt = entity.getUpdatedAt();
        this.status = LifecycleStatus.forPricingRule(entity.isActive(), entity.getValidFrom(), entity.getValidTo());
    }
}
