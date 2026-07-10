package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.enums.LifecycleStatus;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response DTO for contract type definition.
 * Used when returning contract type data from REST APIs.
 *
 * <p>{@code status} is derived at mapping time via
 * {@link LifecycleStatus#forAgreement(boolean, LocalDate, LocalDate)}
 * (today in Europe/Copenhagen, {@code validUntil} exclusive):
 * ACTIVE / SCHEDULED / EXPIRED / ARCHIVED.
 *
 * <p>{@code contractCount} and {@code activePricingRuleCount} are list-endpoint
 * enrichments (computed via grouped queries in
 * {@code ContractTypeDefinitionService.listAll}); they are {@code null} on
 * single-item responses.
 */
@Data
@NoArgsConstructor
public class ContractTypeDefinitionDTO {

    private Integer id;
    private String code;
    private String name;
    private String description;
    private LocalDate validFrom;
    private LocalDate validUntil;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** Derived lifecycle status — ACTIVE / SCHEDULED / EXPIRED / ARCHIVED. Never persisted. */
    private LifecycleStatus status;

    /** Number of contracts referencing this agreement's code. Populated on the list endpoint only. */
    private Integer contractCount;

    /** Number of active pricing rules for this agreement. Populated on the list endpoint only. */
    private Integer activePricingRuleCount;

    /**
     * Convert entity to DTO.
     */
    public static ContractTypeDefinitionDTO fromEntity(ContractTypeDefinition entity) {
        return new ContractTypeDefinitionDTO(entity);
    }

    /**
     * Convert entity to DTO (convenience constructor).
     */
    public ContractTypeDefinitionDTO(ContractTypeDefinition entity) {
        this.id = entity.getId();
        this.code = entity.getCode();
        this.name = entity.getName();
        this.description = entity.getDescription();
        this.validFrom = entity.getValidFrom();
        this.validUntil = entity.getValidUntil();
        this.active = entity.isActive();
        this.createdAt = entity.getCreatedAt();
        this.updatedAt = entity.getUpdatedAt();
        this.status = LifecycleStatus.forAgreement(entity.isActive(), entity.getValidFrom(), entity.getValidUntil());
    }
}
