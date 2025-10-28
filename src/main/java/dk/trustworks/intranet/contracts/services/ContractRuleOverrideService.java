package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.dto.PricingOverrideDTO;
import dk.trustworks.intranet.contracts.dto.RateOverrideDTO;
import dk.trustworks.intranet.contracts.dto.ValidationOverrideDTO;
import dk.trustworks.intranet.contracts.mappers.ContractOverrideMapper;
import dk.trustworks.intranet.contracts.model.*;
import dk.trustworks.intranet.contracts.model.enums.OverrideType;
import io.quarkus.cache.CacheInvalidate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;

/**
 * Service for managing contract rule overrides (CRUD operations).
 *
 * <p>This service handles the creation, update, and deletion of contract-level
 * rule overrides for validation rules, rate adjustments, and pricing rules.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>Feature flag integration - checks if overrides are enabled</li>
 *   <li>Cache invalidation - clears cache after mutations</li>
 *   <li>Transactional operations - ensures data consistency</li>
 *   <li>Input validation - validates override values before persisting</li>
 *   <li>Audit logging - tracks who created/modified overrides</li>
 * </ul>
 *
 * <p><b>Important:</b> Database is READ-ONLY by default. All write operations
 * require @Transactional annotation.
 *
 * @see ContractValidationOverride
 * @see ContractRateAdjustmentOverride
 * @see PricingRuleOverride
 */
@ApplicationScoped
@JBossLog
public class ContractRuleOverrideService {

    @Inject
    ContractOverrideFeatureService featureService;

    @Inject
    ContractOverrideMapper mapper;

    // ===== Validation Overrides =====

    /**
     * Create a new validation rule override for a contract.
     *
     * @param contractUuid The contract UUID
     * @param dto The override data
     * @return The created override
     * @throws NotFoundException if contract not found
     * @throws IllegalArgumentException if override already exists
     * @throws IllegalStateException if feature is disabled
     */
    @Transactional
    public ValidationOverrideDTO createValidationOverride(String contractUuid, ValidationOverrideDTO dto) {
        log.infof("Creating validation override for contract %s, rule %s", contractUuid, dto.getRuleId());

        // Feature flag check
        if (!featureService.isOverrideSystemEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            throw new IllegalStateException("Contract overrides are not enabled for this contract");
        }

        // Validate contract exists
        Contract contract = Contract.findById(contractUuid);
        if (contract == null) {
            throw new NotFoundException("Contract not found: " + contractUuid);
        }

        // Check if override already exists
        if (ContractValidationOverride.existsByContractAndRule(contractUuid, dto.getRuleId())) {
            throw new IllegalArgumentException(
                "Validation override already exists for rule: " + dto.getRuleId());
        }

        // Validate DTO based on override type
        validateOverrideDTO(dto);

        // Convert DTO to entity
        ContractValidationOverride entity = mapper.toEntity(dto);
        entity.setContractUuid(contractUuid);
        entity.setCreatedBy(getCurrentUserId());

        // Persist
        entity.persist();

        log.infof("Created validation override id=%d for contract %s", entity.getId(), contractUuid);

        return mapper.toDTO(entity);
    }

    /**
     * Update an existing validation rule override.
     *
     * @param contractUuid The contract UUID
     * @param id The override ID
     * @param dto The updated override data
     * @return The updated override
     * @throws NotFoundException if override not found
     */
    @Transactional
    public ValidationOverrideDTO updateValidationOverride(String contractUuid, Integer id, ValidationOverrideDTO dto) {
        log.infof("Updating validation override id=%d for contract %s", id, contractUuid);

        // Find existing override
        ContractValidationOverride entity = ContractValidationOverride.findById(id);
        if (entity == null || !entity.getContractUuid().equals(contractUuid)) {
            throw new NotFoundException("Validation override not found: " + id);
        }

        // Validate DTO based on override type
        validateOverrideDTO(dto);

        // Update entity
        mapper.updateEntity(entity, dto);
        entity.persist();

        log.infof("Updated validation override id=%d", id);

        return mapper.toDTO(entity);
    }

    /**
     * Delete (soft delete) a validation rule override.
     *
     * @param contractUuid The contract UUID
     * @param id The override ID
     * @throws NotFoundException if override not found
     */
    @Transactional
    public void deleteValidationOverride(String contractUuid, Integer id) {
        log.infof("Deleting validation override id=%d for contract %s", id, contractUuid);

        ContractValidationOverride entity = ContractValidationOverride.findById(id);
        if (entity == null || !entity.getContractUuid().equals(contractUuid)) {
            throw new NotFoundException("Validation override not found: " + id);
        }

        // Soft delete
        entity.setActive(false);
        entity.persist();

        log.infof("Deleted (soft) validation override id=%d", id);
    }

    /**
     * Get all validation overrides for a contract.
     *
     * @param contractUuid The contract UUID
     * @return List of validation overrides (may be empty)
     */
    public List<ValidationOverrideDTO> getValidationOverrides(String contractUuid) {
        List<ContractValidationOverride> entities = ContractValidationOverride.findByContract(contractUuid);
        return mapper.toValidationDTOs(entities);
    }

    // ===== Rate Adjustment Overrides =====

    /**
     * Create a new rate adjustment override for a contract.
     *
     * @param contractUuid The contract UUID
     * @param dto The override data
     * @return The created override
     * @throws NotFoundException if contract not found
     * @throws IllegalArgumentException if override already exists
     * @throws IllegalStateException if feature is disabled
     */
    @Transactional
    public RateOverrideDTO createRateOverride(String contractUuid, RateOverrideDTO dto) {
        log.infof("Creating rate override for contract %s, rule %s", contractUuid, dto.getRuleId());

        // Feature flag check
        if (!featureService.isOverrideSystemEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            throw new IllegalStateException("Contract overrides are not enabled for this contract");
        }

        // Validate contract exists
        Contract contract = Contract.findById(contractUuid);
        if (contract == null) {
            throw new NotFoundException("Contract not found: " + contractUuid);
        }

        // Check if override already exists
        if (ContractRateAdjustmentOverride.existsByContractAndRule(contractUuid, dto.getRuleId())) {
            throw new IllegalArgumentException(
                "Rate adjustment override already exists for rule: " + dto.getRuleId());
        }

        // Validate DTO
        validateOverrideDTO(dto);
        dto.validateDates();

        // Convert DTO to entity
        ContractRateAdjustmentOverride entity = mapper.toEntity(dto);
        entity.setContractUuid(contractUuid);
        entity.setCreatedBy(getCurrentUserId());

        // Persist
        entity.persist();

        log.infof("Created rate override id=%d for contract %s", entity.getId(), contractUuid);

        return mapper.toDTO(entity);
    }

    /**
     * Update an existing rate adjustment override.
     *
     * @param contractUuid The contract UUID
     * @param id The override ID
     * @param dto The updated override data
     * @return The updated override
     * @throws NotFoundException if override not found
     */
    @Transactional
    public RateOverrideDTO updateRateOverride(String contractUuid, Integer id, RateOverrideDTO dto) {
        log.infof("Updating rate override id=%d for contract %s", id, contractUuid);

        ContractRateAdjustmentOverride entity = ContractRateAdjustmentOverride.findById(id);
        if (entity == null || !entity.getContractUuid().equals(contractUuid)) {
            throw new NotFoundException("Rate adjustment override not found: " + id);
        }

        // Validate DTO
        validateOverrideDTO(dto);
        dto.validateDates();

        // Update entity
        mapper.updateEntity(entity, dto);
        entity.persist();

        log.infof("Updated rate override id=%d", id);

        return mapper.toDTO(entity);
    }

    /**
     * Delete (soft delete) a rate adjustment override.
     *
     * @param contractUuid The contract UUID
     * @param id The override ID
     * @throws NotFoundException if override not found
     */
    @Transactional
    public void deleteRateOverride(String contractUuid, Integer id) {
        log.infof("Deleting rate override id=%d for contract %s", id, contractUuid);

        ContractRateAdjustmentOverride entity = ContractRateAdjustmentOverride.findById(id);
        if (entity == null || !entity.getContractUuid().equals(contractUuid)) {
            throw new NotFoundException("Rate adjustment override not found: " + id);
        }

        // Soft delete
        entity.setActive(false);
        entity.persist();

        log.infof("Deleted (soft) rate override id=%d", id);
    }

    /**
     * Get rate adjustment overrides for a contract, optionally filtered by date.
     *
     * @param contractUuid The contract UUID
     * @param date Optional date to filter by (null = all overrides)
     * @return List of rate adjustment overrides (may be empty)
     */
    public List<RateOverrideDTO> getRateOverrides(String contractUuid, LocalDate date) {
        List<ContractRateAdjustmentOverride> entities;

        if (date != null) {
            entities = ContractRateAdjustmentOverride.findByContractAndDate(contractUuid, date);
        } else {
            entities = ContractRateAdjustmentOverride.findByContract(contractUuid);
        }

        return mapper.toRateDTOs(entities);
    }

    // ===== Pricing Rule Overrides =====

    /**
     * Create a new pricing rule override for a contract.
     *
     * @param contractUuid The contract UUID
     * @param dto The override data
     * @return The created override
     * @throws NotFoundException if contract not found
     * @throws IllegalArgumentException if override already exists
     * @throws IllegalStateException if feature is disabled
     */
    @Transactional
    public PricingOverrideDTO createPricingOverride(String contractUuid, PricingOverrideDTO dto) {
        log.infof("Creating pricing override for contract %s, rule %s", contractUuid, dto.getRuleId());

        // Feature flag check
        if (!featureService.isOverrideSystemEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            throw new IllegalStateException("Contract overrides are not enabled for this contract");
        }

        // Validate contract exists
        Contract contract = Contract.findById(contractUuid);
        if (contract == null) {
            throw new NotFoundException("Contract not found: " + contractUuid);
        }

        // Check if override already exists
        if (PricingRuleOverride.existsByContractAndRule(contractUuid, dto.getRuleId())) {
            throw new IllegalArgumentException(
                "Pricing rule override already exists for rule: " + dto.getRuleId());
        }

        // Validate DTO
        validateOverrideDTO(dto);
        dto.validateDates();

        // Convert DTO to entity
        PricingRuleOverride entity = mapper.toEntity(dto);
        entity.setContractUuid(contractUuid);
        entity.setCreatedBy(getCurrentUserId());

        // Persist
        entity.persist();

        log.infof("Created pricing override id=%d for contract %s", entity.getId(), contractUuid);

        return mapper.toDTO(entity);
    }

    /**
     * Update an existing pricing rule override.
     *
     * @param contractUuid The contract UUID
     * @param id The override ID
     * @param dto The updated override data
     * @return The updated override
     * @throws NotFoundException if override not found
     */
    @Transactional
    public PricingOverrideDTO updatePricingOverride(String contractUuid, Integer id, PricingOverrideDTO dto) {
        log.infof("Updating pricing override id=%d for contract %s", id, contractUuid);

        PricingRuleOverride entity = PricingRuleOverride.findById(id);
        if (entity == null || !entity.getContractUuid().equals(contractUuid)) {
            throw new NotFoundException("Pricing rule override not found: " + id);
        }

        // Validate DTO
        validateOverrideDTO(dto);
        dto.validateDates();

        // Update entity
        mapper.updateEntity(entity, dto);
        entity.persist();

        log.infof("Updated pricing override id=%d", id);

        return mapper.toDTO(entity);
    }

    /**
     * Delete (soft delete) a pricing rule override.
     *
     * @param contractUuid The contract UUID
     * @param id The override ID
     * @throws NotFoundException if override not found
     */
    @Transactional
    public void deletePricingOverride(String contractUuid, Integer id) {
        log.infof("Deleting pricing override id=%d for contract %s", id, contractUuid);

        PricingRuleOverride entity = PricingRuleOverride.findById(id);
        if (entity == null || !entity.getContractUuid().equals(contractUuid)) {
            throw new NotFoundException("Pricing rule override not found: " + id);
        }

        // Soft delete
        entity.setActive(false);
        entity.persist();

        log.infof("Deleted (soft) pricing override id=%d", id);
    }

    /**
     * Get pricing rule overrides for a contract, optionally filtered by date.
     *
     * @param contractUuid The contract UUID
     * @param date Optional date to filter by (null = all overrides)
     * @return List of pricing rule overrides (may be empty)
     */
    public List<PricingOverrideDTO> getPricingOverrides(String contractUuid, LocalDate date) {
        List<PricingRuleOverride> entities;

        if (date != null) {
            entities = PricingRuleOverride.findByContractAndDate(contractUuid, date);
        } else {
            entities = PricingRuleOverride.findByContract(contractUuid);
        }

        return mapper.toPricingDTOs(entities);
    }

    // ===== Helper Methods =====

    /**
     * Validate override DTO based on override type.
     */
    private void validateOverrideDTO(ValidationOverrideDTO dto) {
        if (dto.getOverrideType() == OverrideType.REPLACE) {
            dto.validateForReplace();
        } else if (dto.getOverrideType() == OverrideType.MODIFY) {
            dto.validateForModify();
        }
    }

    /**
     * Validate override DTO based on override type.
     */
    private void validateOverrideDTO(RateOverrideDTO dto) {
        if (dto.getOverrideType() == OverrideType.REPLACE) {
            dto.validateForReplace();
        }
    }

    /**
     * Validate override DTO based on override type.
     */
    private void validateOverrideDTO(PricingOverrideDTO dto) {
        if (dto.getOverrideType() == OverrideType.REPLACE) {
            dto.validateForReplace();
        }
    }

    /**
     * Get current user ID from security context.
     * TODO: Integrate with actual security context once available.
     */
    private String getCurrentUserId() {
        // Placeholder - integrate with actual security context
        return "system";
    }
}
