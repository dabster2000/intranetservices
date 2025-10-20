package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.dto.CreateValidationRuleRequest;
import dk.trustworks.intranet.contracts.dto.UpdateValidationRuleRequest;
import dk.trustworks.intranet.contracts.dto.ValidationRuleDTO;
import dk.trustworks.intranet.contracts.model.ContractValidationRuleEntity;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing contract validation rules.
 * Provides CRUD operations and business logic for validation rules that enforce constraints.
 */
@JBossLog
@ApplicationScoped
public class ContractValidationRuleService {

    /**
     * Create a new validation rule for a contract type.
     *
     * @param contractTypeCode The contract type code
     * @param request The validation rule data
     * @return The created validation rule DTO
     * @throws BadRequestException if validation fails
     */
    @Transactional
    public ValidationRuleDTO create(String contractTypeCode, CreateValidationRuleRequest request) {
        log.info("ContractValidationRuleService.create");
        log.info("contractTypeCode = " + contractTypeCode + ", request = " + request);

        // Validate uniqueness
        if (ContractValidationRuleEntity.existsByContractTypeAndRuleId(contractTypeCode, request.getRuleId())) {
            throw new BadRequestException("Validation rule with ID '" + request.getRuleId() +
                    "' already exists for contract type '" + contractTypeCode + "'");
        }

        // Create entity
        ContractValidationRuleEntity entity = new ContractValidationRuleEntity();
        entity.setContractTypeCode(contractTypeCode);
        entity.setRuleId(request.getRuleId());
        entity.setLabel(request.getLabel());
        entity.setValidationType(request.getValidationType());
        entity.setRequired(request.isRequired());
        entity.setThresholdValue(request.getThresholdValue());
        entity.setConfigJson(request.getConfigJson());
        entity.setPriority(request.getPriority());
        entity.setActive(request.isActive());

        // Persist
        entity.persist();

        log.info("Created validation rule: " + entity.getRuleId() + " for contract type " + contractTypeCode);
        return ValidationRuleDTO.fromEntity(entity);
    }

    /**
     * Update an existing validation rule.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @param request The updated data
     * @return The updated validation rule DTO
     * @throws NotFoundException if rule not found
     */
    @Transactional
    public ValidationRuleDTO update(String contractTypeCode, String ruleId, UpdateValidationRuleRequest request) {
        log.info("ContractValidationRuleService.update");
        log.info("contractTypeCode = " + contractTypeCode + ", ruleId = " + ruleId + ", request = " + request);

        // Find existing
        ContractValidationRuleEntity entity = ContractValidationRuleEntity.findByContractTypeAndRuleId(contractTypeCode, ruleId);
        if (entity == null) {
            throw new NotFoundException("Validation rule with ID '" + ruleId +
                    "' not found for contract type '" + contractTypeCode + "'");
        }

        // Update fields
        entity.setLabel(request.getLabel());
        entity.setValidationType(request.getValidationType());
        entity.setRequired(request.isRequired());
        entity.setThresholdValue(request.getThresholdValue());
        entity.setConfigJson(request.getConfigJson());
        entity.setPriority(request.getPriority());
        entity.setActive(request.isActive());

        // Persist (automatic with @Transactional)
        entity.persist();

        log.info("Updated validation rule: " + ruleId + " for contract type " + contractTypeCode);
        return ValidationRuleDTO.fromEntity(entity);
    }

    /**
     * Soft delete a validation rule.
     * Sets active=false but preserves the record.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @throws NotFoundException if rule not found
     */
    @Transactional
    public void softDelete(String contractTypeCode, String ruleId) {
        log.info("ContractValidationRuleService.softDelete");
        log.info("contractTypeCode = " + contractTypeCode + ", ruleId = " + ruleId);

        // Find existing
        ContractValidationRuleEntity entity = ContractValidationRuleEntity.findByContractTypeAndRuleId(contractTypeCode, ruleId);
        if (entity == null) {
            throw new NotFoundException("Validation rule with ID '" + ruleId +
                    "' not found for contract type '" + contractTypeCode + "'");
        }

        // Soft delete
        entity.softDelete();

        log.info("Soft deleted validation rule: " + ruleId + " for contract type " + contractTypeCode);
    }

    /**
     * Find a validation rule by contract type and rule ID.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @return The validation rule DTO
     * @throws NotFoundException if rule not found
     */
    public ValidationRuleDTO findByRuleId(String contractTypeCode, String ruleId) {
        log.debug("ContractValidationRuleService.findByRuleId");
        log.debug("contractTypeCode = " + contractTypeCode + ", ruleId = " + ruleId);

        ContractValidationRuleEntity entity = ContractValidationRuleEntity.findByContractTypeAndRuleId(contractTypeCode, ruleId);
        if (entity == null) {
            throw new NotFoundException("Validation rule with ID '" + ruleId +
                    "' not found for contract type '" + contractTypeCode + "'");
        }

        return ValidationRuleDTO.fromEntity(entity);
    }

    /**
     * List all validation rules for a contract type.
     *
     * @param contractTypeCode The contract type code
     * @param includeInactive Whether to include inactive rules
     * @return List of validation rule DTOs
     */
    public List<ValidationRuleDTO> listAll(String contractTypeCode, boolean includeInactive) {
        log.debug("ContractValidationRuleService.listAll");
        log.debug("contractTypeCode = " + contractTypeCode + ", includeInactive = " + includeInactive);

        List<ContractValidationRuleEntity> entities = includeInactive
                ? ContractValidationRuleEntity.findByContractTypeIncludingInactive(contractTypeCode)
                : ContractValidationRuleEntity.findByContractType(contractTypeCode);

        return entities.stream()
                .map(ValidationRuleDTO::fromEntity)
                .collect(Collectors.toList());
    }

    // --- Helper methods for specific validation checks ---

    /**
     * Check if notes are required for time registration for this contract type.
     *
     * @param contractTypeCode The contract type code
     * @return true if notes are required, false otherwise
     */
    public boolean isNotesRequired(String contractTypeCode) {
        List<ContractValidationRuleEntity> rules = ContractValidationRuleEntity.findByContractTypeAndValidationType(
                contractTypeCode, ValidationType.NOTES_REQUIRED);

        return rules.stream()
                .anyMatch(ContractValidationRuleEntity::isRequired);
    }

    /**
     * Get the minimum hours per entry threshold for this contract type.
     *
     * @param contractTypeCode The contract type code
     * @return The minimum hours, or null if no threshold is set
     */
    public java.math.BigDecimal getMinHoursPerEntry(String contractTypeCode) {
        List<ContractValidationRuleEntity> rules = ContractValidationRuleEntity.findByContractTypeAndValidationType(
                contractTypeCode, ValidationType.MIN_HOURS_PER_ENTRY);

        return rules.stream()
                .map(ContractValidationRuleEntity::getThresholdValue)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * Get the maximum hours per day threshold for this contract type.
     *
     * @param contractTypeCode The contract type code
     * @return The maximum hours, or null if no threshold is set
     */
    public java.math.BigDecimal getMaxHoursPerDay(String contractTypeCode) {
        List<ContractValidationRuleEntity> rules = ContractValidationRuleEntity.findByContractTypeAndValidationType(
                contractTypeCode, ValidationType.MAX_HOURS_PER_DAY);

        return rules.stream()
                .map(ContractValidationRuleEntity::getThresholdValue)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * Check if task selection is required for time registration for this contract type.
     *
     * @param contractTypeCode The contract type code
     * @return true if task selection is required, false otherwise
     */
    public boolean isTaskSelectionRequired(String contractTypeCode) {
        List<ContractValidationRuleEntity> rules = ContractValidationRuleEntity.findByContractTypeAndValidationType(
                contractTypeCode, ValidationType.REQUIRE_TASK_SELECTION);

        return rules.stream()
                .anyMatch(ContractValidationRuleEntity::isRequired);
    }
}
