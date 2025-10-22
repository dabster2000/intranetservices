package dk.trustworks.intranet.contracts.mappers;

import dk.trustworks.intranet.contracts.dto.PricingOverrideDTO;
import dk.trustworks.intranet.contracts.dto.RateOverrideDTO;
import dk.trustworks.intranet.contracts.dto.ValidationOverrideDTO;
import dk.trustworks.intranet.contracts.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mapper utility for converting between contract override entities and DTOs.
 *
 * <p>This mapper handles bidirectional conversion for all three types of rule overrides:
 * <ul>
 *   <li>Validation rule overrides</li>
 *   <li>Rate adjustment overrides</li>
 *   <li>Pricing rule overrides</li>
 * </ul>
 *
 * <p><b>Design Principles:</b>
 * - Entity to DTO: Maps all fields including read-only fields (id, timestamps, createdBy)
 * - DTO to Entity: Maps only writable fields, ignoring read-only fields
 * - Null-safe: Returns null for null inputs
 * - List conversion: Provides bulk conversion methods
 *
 * @see ValidationOverrideDTO
 * @see RateOverrideDTO
 * @see PricingOverrideDTO
 */
@ApplicationScoped
@JBossLog
public class ContractOverrideMapper {

    // ===== Validation Override Mapping =====

    /**
     * Convert validation override entity to DTO.
     *
     * @param entity The entity to convert
     * @return The DTO representation, or null if entity is null
     */
    public ValidationOverrideDTO toDTO(ContractValidationOverride entity) {
        if (entity == null) {
            return null;
        }

        return ValidationOverrideDTO.builder()
            .id(entity.getId())
            .contractUuid(entity.getContractUuid())
            .ruleId(entity.getRuleId())
            .overrideType(entity.getOverrideType())
            .label(entity.getLabel())
            .validationType(entity.getValidationType())
            .required(entity.getRequired())
            .thresholdValue(entity.getThresholdValue())
            .configJson(entity.getConfigJson())
            .priority(entity.getPriority())
            .active(entity.isActive())
            .createdBy(entity.getCreatedBy())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    /**
     * Convert validation override DTO to entity.
     * Note: Read-only fields (id, createdBy, timestamps) are not copied.
     *
     * @param dto The DTO to convert
     * @return The entity representation, or null if DTO is null
     */
    public ContractValidationOverride toEntity(ValidationOverrideDTO dto) {
        if (dto == null) {
            return null;
        }

        ContractValidationOverride entity = new ContractValidationOverride();
        entity.setContractUuid(dto.getContractUuid());
        entity.setRuleId(dto.getRuleId());
        entity.setOverrideType(dto.getOverrideType());
        entity.setLabel(dto.getLabel());
        entity.setValidationType(dto.getValidationType());
        entity.setRequired(dto.getRequired());
        entity.setThresholdValue(dto.getThresholdValue());
        entity.setConfigJson(dto.getConfigJson());
        entity.setPriority(dto.getPriority());
        entity.setActive(dto.getActive() != null ? dto.getActive() : true);

        return entity;
    }

    /**
     * Update existing validation override entity with DTO values.
     * Preserves entity identity (id, contractUuid, ruleId, createdBy, createdAt).
     *
     * @param entity The entity to update (modified in place)
     * @param dto The DTO with new values
     */
    public void updateEntity(ContractValidationOverride entity, ValidationOverrideDTO dto) {
        if (entity == null || dto == null) {
            return;
        }

        // Update mutable fields only
        entity.setOverrideType(dto.getOverrideType());
        entity.setLabel(dto.getLabel());
        entity.setValidationType(dto.getValidationType());
        entity.setRequired(dto.getRequired());
        entity.setThresholdValue(dto.getThresholdValue());
        entity.setConfigJson(dto.getConfigJson());
        entity.setPriority(dto.getPriority());
        entity.setActive(dto.getActive() != null ? dto.getActive() : true);
    }

    /**
     * Convert list of validation override entities to DTOs.
     *
     * @param entities The entities to convert
     * @return List of DTOs, or empty list if entities is null
     */
    public List<ValidationOverrideDTO> toValidationDTOs(List<ContractValidationOverride> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    // ===== Rate Adjustment Override Mapping =====

    /**
     * Convert rate adjustment override entity to DTO.
     *
     * @param entity The entity to convert
     * @return The DTO representation, or null if entity is null
     */
    public RateOverrideDTO toDTO(ContractRateAdjustmentOverride entity) {
        if (entity == null) {
            return null;
        }

        return RateOverrideDTO.builder()
            .id(entity.getId())
            .contractUuid(entity.getContractUuid())
            .ruleId(entity.getRuleId())
            .overrideType(entity.getOverrideType())
            .label(entity.getLabel())
            .adjustmentType(entity.getAdjustmentType())
            .adjustmentPercent(entity.getAdjustmentPercent())
            .frequency(entity.getFrequency())
            .effectiveDate(entity.getEffectiveDate())
            .endDate(entity.getEndDate())
            .priority(entity.getPriority())
            .active(entity.isActive())
            .createdBy(entity.getCreatedBy())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    /**
     * Convert rate adjustment override DTO to entity.
     * Note: Read-only fields (id, createdBy, timestamps) are not copied.
     *
     * @param dto The DTO to convert
     * @return The entity representation, or null if DTO is null
     */
    public ContractRateAdjustmentOverride toEntity(RateOverrideDTO dto) {
        if (dto == null) {
            return null;
        }

        ContractRateAdjustmentOverride entity = new ContractRateAdjustmentOverride();
        entity.setContractUuid(dto.getContractUuid());
        entity.setRuleId(dto.getRuleId());
        entity.setOverrideType(dto.getOverrideType());
        entity.setLabel(dto.getLabel());
        entity.setAdjustmentType(dto.getAdjustmentType());
        entity.setAdjustmentPercent(dto.getAdjustmentPercent());
        entity.setFrequency(dto.getFrequency());
        entity.setEffectiveDate(dto.getEffectiveDate());
        entity.setEndDate(dto.getEndDate());
        entity.setPriority(dto.getPriority());
        entity.setActive(dto.getActive() != null ? dto.getActive() : true);

        return entity;
    }

    /**
     * Update existing rate adjustment override entity with DTO values.
     * Preserves entity identity (id, contractUuid, ruleId, createdBy, createdAt).
     *
     * @param entity The entity to update (modified in place)
     * @param dto The DTO with new values
     */
    public void updateEntity(ContractRateAdjustmentOverride entity, RateOverrideDTO dto) {
        if (entity == null || dto == null) {
            return;
        }

        // Update mutable fields only
        entity.setOverrideType(dto.getOverrideType());
        entity.setLabel(dto.getLabel());
        entity.setAdjustmentType(dto.getAdjustmentType());
        entity.setAdjustmentPercent(dto.getAdjustmentPercent());
        entity.setFrequency(dto.getFrequency());
        entity.setEffectiveDate(dto.getEffectiveDate());
        entity.setEndDate(dto.getEndDate());
        entity.setPriority(dto.getPriority());
        entity.setActive(dto.getActive() != null ? dto.getActive() : true);
    }

    /**
     * Convert list of rate adjustment override entities to DTOs.
     *
     * @param entities The entities to convert
     * @return List of DTOs, or empty list if entities is null
     */
    public List<RateOverrideDTO> toRateDTOs(List<ContractRateAdjustmentOverride> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    // ===== Pricing Rule Override Mapping =====

    /**
     * Convert pricing rule override entity to DTO.
     *
     * @param entity The entity to convert
     * @return The DTO representation, or null if entity is null
     */
    public PricingOverrideDTO toDTO(PricingRuleOverride entity) {
        if (entity == null) {
            return null;
        }

        return PricingOverrideDTO.builder()
            .id(entity.getId())
            .contractUuid(entity.getContractUuid())
            .ruleId(entity.getRuleId())
            .overrideType(entity.getOverrideType())
            .label(entity.getLabel())
            .ruleStepType(entity.getRuleStepType())
            .stepBase(entity.getStepBase())
            .percent(entity.getPercent())
            .amount(entity.getAmount())
            .paramKey(entity.getParamKey())
            .validFrom(entity.getValidFrom())
            .validTo(entity.getValidTo())
            .priority(entity.getPriority())
            .active(entity.isActive())
            .createdBy(entity.getCreatedBy())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    /**
     * Convert pricing rule override DTO to entity.
     * Note: Read-only fields (id, createdBy, timestamps) are not copied.
     *
     * @param dto The DTO to convert
     * @return The entity representation, or null if DTO is null
     */
    public PricingRuleOverride toEntity(PricingOverrideDTO dto) {
        if (dto == null) {
            return null;
        }

        PricingRuleOverride entity = new PricingRuleOverride();
        entity.setContractUuid(dto.getContractUuid());
        entity.setRuleId(dto.getRuleId());
        entity.setOverrideType(dto.getOverrideType());
        entity.setLabel(dto.getLabel());
        entity.setRuleStepType(dto.getRuleStepType());
        entity.setStepBase(dto.getStepBase());
        entity.setPercent(dto.getPercent());
        entity.setAmount(dto.getAmount());
        entity.setParamKey(dto.getParamKey());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        entity.setPriority(dto.getPriority());
        entity.setActive(dto.getActive() != null ? dto.getActive() : true);

        return entity;
    }

    /**
     * Update existing pricing rule override entity with DTO values.
     * Preserves entity identity (id, contractUuid, ruleId, createdBy, createdAt).
     *
     * @param entity The entity to update (modified in place)
     * @param dto The DTO with new values
     */
    public void updateEntity(PricingRuleOverride entity, PricingOverrideDTO dto) {
        if (entity == null || dto == null) {
            return;
        }

        // Update mutable fields only
        entity.setOverrideType(dto.getOverrideType());
        entity.setLabel(dto.getLabel());
        entity.setRuleStepType(dto.getRuleStepType());
        entity.setStepBase(dto.getStepBase());
        entity.setPercent(dto.getPercent());
        entity.setAmount(dto.getAmount());
        entity.setParamKey(dto.getParamKey());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        entity.setPriority(dto.getPriority());
        entity.setActive(dto.getActive() != null ? dto.getActive() : true);
    }

    /**
     * Convert list of pricing rule override entities to DTOs.
     *
     * @param entities The entities to convert
     * @return List of DTOs, or empty list if entities is null
     */
    public List<PricingOverrideDTO> toPricingDTOs(List<PricingRuleOverride> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    // ===== Base Rule Entity to DTO Mapping (for merged effective rules) =====

    /**
     * Convert validation rule entity (framework rule) to DTO.
     * Used for displaying effective merged rules.
     *
     * @param entity The validation rule entity
     * @return The DTO representation, or null if entity is null
     */
    public ValidationOverrideDTO toDTO(ContractValidationRuleEntity entity) {
        if (entity == null) {
            return null;
        }

        return ValidationOverrideDTO.builder()
            .ruleId(entity.getRuleId())
            .label(entity.getLabel())
            .validationType(entity.getValidationType())
            .required(entity.isRequired())
            .thresholdValue(entity.getThresholdValue())
            .configJson(entity.getConfigJson())
            .priority(entity.getPriority())
            .active(entity.isActive())
            .build();
    }

    /**
     * Convert rate adjustment entity (framework rule) to DTO.
     * Used for displaying effective merged rules.
     *
     * @param entity The rate adjustment entity
     * @return The DTO representation, or null if entity is null
     */
    public RateOverrideDTO toDTO(ContractRateAdjustmentEntity entity) {
        if (entity == null) {
            return null;
        }

        return RateOverrideDTO.builder()
            .ruleId(entity.getRuleId())
            .label(entity.getLabel())
            .adjustmentType(entity.getAdjustmentType())
            .adjustmentPercent(entity.getAdjustmentPercent())
            .frequency(entity.getFrequency())
            .effectiveDate(entity.getEffectiveDate())
            .endDate(entity.getEndDate())
            .priority(entity.getPriority())
            .active(entity.isActive())
            .build();
    }

    /**
     * Convert pricing rule step entity (framework rule) to DTO.
     * Used for displaying effective merged rules.
     *
     * @param entity The pricing rule step entity
     * @return The DTO representation, or null if entity is null
     */
    public PricingOverrideDTO toDTO(PricingRuleStepEntity entity) {
        if (entity == null) {
            return null;
        }

        return PricingOverrideDTO.builder()
            .ruleId(entity.getRuleId())
            .label(entity.getLabel())
            .ruleStepType(entity.getRuleStepType())
            .stepBase(entity.getStepBase())
            .percent(entity.getPercent())
            .amount(entity.getAmount())
            .paramKey(entity.getParamKey())
            .validFrom(entity.getValidFrom())
            .validTo(entity.getValidTo())
            .priority(entity.getPriority())
            .active(entity.isActive())
            .build();
    }

    /**
     * Convert list of validation rule entities to DTOs.
     *
     * @param entities The entities to convert
     * @return List of DTOs, or empty list if entities is null
     */
    public List<ValidationOverrideDTO> fromValidationEntities(List<ContractValidationRuleEntity> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream()
            .map(e -> toDTO(e))
            .collect(Collectors.toList());
    }

    /**
     * Convert validation rule entities to DTOs, enriching with override metadata.
     * When a rule has a corresponding override, sets the override's id and type on the DTO.
     *
     * @param entities Merged effective validation rules
     * @param overrides Contract-specific overrides for cross-reference
     * @return List of DTOs with override metadata when applicable
     */
    public List<ValidationOverrideDTO> fromValidationEntities(
        List<ContractValidationRuleEntity> entities,
        List<ContractValidationOverride> overrides
    ) {
        if (entities == null) {
            return List.of();
        }

        // Build map of ruleId -> override for fast lookup
        Map<String, ContractValidationOverride> overrideMap =
            (overrides == null) ? Map.of() :
            overrides.stream()
                .filter(o -> o.isActive() && o.getOverrideType() != dk.trustworks.intranet.contracts.model.enums.OverrideType.DISABLE)
                .collect(Collectors.toMap(
                    ContractValidationOverride::getRuleId,
                    o -> o,
                    (a, b) -> a, // keep first if duplicate (shouldn't happen)
                    java.util.LinkedHashMap::new
                ));

        return entities.stream()
            .map(entity -> {
                // Start with base DTO from entity
                ValidationOverrideDTO dto = toDTO(entity);

                // Enrich with override metadata if this rule came from an override
                ContractValidationOverride override = overrideMap.get(entity.getRuleId());
                if (override != null) {
                    dto.setId(override.getId());
                    dto.setOverrideType(override.getOverrideType());
                    dto.setContractUuid(override.getContractUuid());
                    dto.setCreatedBy(override.getCreatedBy());
                    dto.setCreatedAt(override.getCreatedAt());
                    dto.setUpdatedAt(override.getUpdatedAt());
                }

                return dto;
            })
            .collect(Collectors.toList());
    }

    /**
     * Convert list of rate adjustment entities to DTOs.
     *
     * @param entities The entities to convert
     * @return List of DTOs, or empty list if entities is null
     */
    public List<RateOverrideDTO> fromRateEntities(List<ContractRateAdjustmentEntity> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream()
            .map(e -> toDTO(e))
            .collect(Collectors.toList());
    }

    /**
     * Convert rate adjustment entities to DTOs, enriching with override metadata.
     * When a rule has a corresponding override, sets the override's id and type on the DTO.
     *
     * @param entities Merged effective rate adjustments
     * @param overrides Contract-specific overrides for cross-reference
     * @return List of DTOs with override metadata when applicable
     */
    public List<RateOverrideDTO> fromRateEntities(
        List<ContractRateAdjustmentEntity> entities,
        List<ContractRateAdjustmentOverride> overrides
    ) {
        if (entities == null) {
            return List.of();
        }

        // Build map of ruleId -> override for fast lookup
        Map<String, ContractRateAdjustmentOverride> overrideMap =
            (overrides == null) ? Map.of() :
            overrides.stream()
                .filter(o -> o.isActive() && o.getOverrideType() != dk.trustworks.intranet.contracts.model.enums.OverrideType.DISABLE)
                .collect(Collectors.toMap(
                    ContractRateAdjustmentOverride::getRuleId,
                    o -> o,
                    (a, b) -> a,
                    java.util.LinkedHashMap::new
                ));

        return entities.stream()
            .map(entity -> {
                RateOverrideDTO dto = toDTO(entity);

                ContractRateAdjustmentOverride override = overrideMap.get(entity.getRuleId());
                if (override != null) {
                    dto.setId(override.getId());
                    dto.setOverrideType(override.getOverrideType());
                    dto.setContractUuid(override.getContractUuid());
                    dto.setCreatedBy(override.getCreatedBy());
                    dto.setCreatedAt(override.getCreatedAt());
                    dto.setUpdatedAt(override.getUpdatedAt());
                }

                return dto;
            })
            .collect(Collectors.toList());
    }

    /**
     * Convert list of pricing rule step entities to DTOs.
     *
     * @param entities The entities to convert
     * @return List of DTOs, or empty list if entities is null
     */
    public List<PricingOverrideDTO> fromPricingEntities(List<PricingRuleStepEntity> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream()
            .map(e -> toDTO(e))
            .collect(Collectors.toList());
    }

    /**
     * Convert pricing rule step entities to DTOs, enriching with override metadata.
     * When a rule has a corresponding override, sets the override's id and type on the DTO.
     *
     * @param entities Merged effective pricing rules
     * @param overrides Contract-specific overrides for cross-reference
     * @return List of DTOs with override metadata when applicable
     */
    public List<PricingOverrideDTO> fromPricingEntities(
        List<PricingRuleStepEntity> entities,
        List<PricingRuleOverride> overrides
    ) {
        if (entities == null) {
            return List.of();
        }

        // Build map of ruleId -> override for fast lookup
        Map<String, PricingRuleOverride> overrideMap =
            (overrides == null) ? Map.of() :
            overrides.stream()
                .filter(o -> o.isActive() && o.getOverrideType() != dk.trustworks.intranet.contracts.model.enums.OverrideType.DISABLE)
                .collect(Collectors.toMap(
                    PricingRuleOverride::getRuleId,
                    o -> o,
                    (a, b) -> a,
                    java.util.LinkedHashMap::new
                ));

        return entities.stream()
            .map(entity -> {
                PricingOverrideDTO dto = toDTO(entity);

                PricingRuleOverride override = overrideMap.get(entity.getRuleId());
                if (override != null) {
                    dto.setId(override.getId());
                    dto.setOverrideType(override.getOverrideType());
                    dto.setContractUuid(override.getContractUuid());
                    dto.setCreatedBy(override.getCreatedBy());
                    dto.setCreatedAt(override.getCreatedAt());
                    dto.setUpdatedAt(override.getUpdatedAt());
                }

                return dto;
            })
            .collect(Collectors.toList());
    }
}
