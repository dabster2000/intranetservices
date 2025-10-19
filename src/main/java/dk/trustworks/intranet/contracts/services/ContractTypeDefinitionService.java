package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.dto.ContractTypeDefinitionDTO;
import dk.trustworks.intranet.contracts.dto.CreateContractTypeRequest;
import dk.trustworks.intranet.contracts.dto.UpdateContractTypeRequest;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing contract type definitions.
 * Provides CRUD operations, validation, and business logic for dynamic contract types.
 */
@JBossLog
@ApplicationScoped
public class ContractTypeDefinitionService {

    /**
     * Create a new contract type definition.
     *
     * @param request The contract type data
     * @return The created contract type DTO
     * @throws BadRequestException if validation fails
     */
    @Transactional
    public ContractTypeDefinitionDTO create(CreateContractTypeRequest request) {
        log.info("ContractTypeDefinitionService.create");
        log.info("request = " + request);

        // Validate uniqueness
        if (ContractTypeDefinition.existsByCode(request.getCode())) {
            throw new BadRequestException("Contract type with code '" + request.getCode() + "' already exists");
        }

        // Validate date range if both dates are provided
        if (request.getValidFrom() != null && request.getValidUntil() != null) {
            if (!request.getValidUntil().isAfter(request.getValidFrom())) {
                throw new BadRequestException("validUntil must be after validFrom");
            }
        }

        // Create entity
        ContractTypeDefinition entity = new ContractTypeDefinition();
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setValidFrom(request.getValidFrom());
        entity.setValidUntil(request.getValidUntil());
        entity.setActive(request.isActive());

        // Persist
        entity.persist();

        log.info("Created contract type: " + entity.getCode());
        return ContractTypeDefinitionDTO.fromEntity(entity);
    }

    /**
     * Update an existing contract type definition.
     *
     * @param code The contract type code
     * @param request The updated data
     * @return The updated contract type DTO
     * @throws NotFoundException if contract type not found
     */
    @Transactional
    public ContractTypeDefinitionDTO update(String code, UpdateContractTypeRequest request) {
        log.info("ContractTypeDefinitionService.update");
        log.info("code = " + code + ", request = " + request);

        // Find existing
        ContractTypeDefinition entity = ContractTypeDefinition.findByCode(code);
        if (entity == null) {
            throw new NotFoundException("Contract type with code '" + code + "' not found");
        }

        // Validate date range if both dates are provided
        if (request.getValidFrom() != null && request.getValidUntil() != null) {
            if (!request.getValidUntil().isAfter(request.getValidFrom())) {
                throw new BadRequestException("validUntil must be after validFrom");
            }
        }

        // Update fields
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setValidFrom(request.getValidFrom());
        entity.setValidUntil(request.getValidUntil());
        entity.setActive(request.isActive());

        // Persist (automatic with @Transactional)
        entity.persist();

        log.info("Updated contract type: " + code);
        return ContractTypeDefinitionDTO.fromEntity(entity);
    }

    /**
     * Soft delete a contract type definition.
     * Sets active=false but preserves the record for historical data.
     *
     * @param code The contract type code
     * @throws NotFoundException if contract type not found
     * @throws BadRequestException if contract type is in use
     */
    @Transactional
    public void softDelete(String code) {
        log.info("ContractTypeDefinitionService.softDelete");
        log.info("code = " + code);

        // Find existing
        ContractTypeDefinition entity = ContractTypeDefinition.findByCode(code);
        if (entity == null) {
            throw new NotFoundException("Contract type with code '" + code + "' not found");
        }

        // Check if in use (check if any active rules exist)
        long activeRulesCount = PricingRuleStepEntity.count("contractTypeCode = ?1 AND active = true", code);
        if (activeRulesCount > 0) {
            log.warn("Cannot delete contract type " + code + " - has " + activeRulesCount + " active pricing rules");
            throw new BadRequestException("Cannot delete contract type with active pricing rules. " +
                    "Please deactivate or delete all rules first.");
        }

        // Soft delete
        entity.softDelete();

        log.info("Soft deleted contract type: " + code);
    }

    /**
     * Reactivate a soft-deleted contract type.
     *
     * @param code The contract type code
     * @throws NotFoundException if contract type not found
     */
    @Transactional
    public void activate(String code) {
        log.info("ContractTypeDefinitionService.activate");
        log.info("code = " + code);

        // Find existing
        ContractTypeDefinition entity = ContractTypeDefinition.findByCode(code);
        if (entity == null) {
            throw new NotFoundException("Contract type with code '" + code + "' not found");
        }

        // Activate
        entity.activate();

        log.info("Activated contract type: " + code);
    }

    /**
     * Find a contract type by code.
     *
     * @param code The contract type code
     * @return The contract type DTO
     * @throws NotFoundException if contract type not found
     */
    public ContractTypeDefinitionDTO findByCode(String code) {
        log.debug("ContractTypeDefinitionService.findByCode");
        log.debug("code = " + code);

        ContractTypeDefinition entity = ContractTypeDefinition.findByCode(code);
        if (entity == null) {
            throw new NotFoundException("Contract type with code '" + code + "' not found");
        }

        return ContractTypeDefinitionDTO.fromEntity(entity);
    }

    /**
     * List all contract types (active only by default).
     *
     * @param includeInactive Whether to include inactive types
     * @return List of contract type DTOs
     */
    public List<ContractTypeDefinitionDTO> listAll(boolean includeInactive) {
        log.debug("ContractTypeDefinitionService.listAll");
        log.debug("includeInactive = " + includeInactive);

        List<ContractTypeDefinition> entities = includeInactive
                ? ContractTypeDefinition.findAllIncludingInactive()
                : ContractTypeDefinition.findAllActive();

        return entities.stream()
                .map(ContractTypeDefinitionDTO::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Check if a contract type code already exists.
     *
     * @param code The code to check
     * @return true if exists, false otherwise
     */
    public boolean existsByCode(String code) {
        return ContractTypeDefinition.existsByCode(code);
    }
}
