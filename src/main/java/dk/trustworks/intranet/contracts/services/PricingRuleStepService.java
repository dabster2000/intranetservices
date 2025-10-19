package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.contracts.dto.*;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import io.quarkus.cache.CacheInvalidate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing pricing rule steps.
 * Provides CRUD operations, priority management, validation, and cache invalidation.
 */
@JBossLog
@ApplicationScoped
public class PricingRuleStepService {

    private static final int DEFAULT_PRIORITY_INCREMENT = 10;

    /**
     * Create a new pricing rule step.
     *
     * @param contractTypeCode The contract type code
     * @param request The rule data
     * @return The created rule DTO
     * @throws BadRequestException if validation fails
     * @throws NotFoundException if contract type not found
     */
    @Transactional
    @CacheInvalidate(cacheName = "pricing-rules")
    public PricingRuleStepDTO createRule(String contractTypeCode, CreateRuleStepRequest request) {
        log.info("PricingRuleStepService.createRule");
        log.info("contractTypeCode = " + contractTypeCode + ", request = " + request);

        // Validate contract type exists
        if (!ContractTypeDefinition.existsByCode(contractTypeCode)) {
            throw new NotFoundException("Contract type with code '" + contractTypeCode + "' not found");
        }

        // Validate uniqueness of rule ID
        if (PricingRuleStepEntity.existsByContractTypeAndRuleId(contractTypeCode, request.getRuleId())) {
            throw new BadRequestException("Rule with ID '" + request.getRuleId() +
                    "' already exists for contract type '" + contractTypeCode + "'");
        }

        // Validate rule integrity
        validateRuleIntegrity(request.getRuleStepType(), request.getPercent(), request.getAmount(), request.getParamKey());

        // Auto-increment priority if not provided
        Integer priority = request.getPriority();
        if (priority == null) {
            int maxPriority = PricingRuleStepEntity.getMaxPriority(contractTypeCode);
            priority = maxPriority + DEFAULT_PRIORITY_INCREMENT;
            log.info("Auto-incrementing priority to: " + priority);
        }

        // Create entity
        PricingRuleStepEntity entity = new PricingRuleStepEntity();
        entity.setContractTypeCode(contractTypeCode);
        entity.setRuleId(request.getRuleId());
        entity.setLabel(request.getLabel());
        entity.setRuleStepType(request.getRuleStepType());
        entity.setStepBase(request.getStepBase());
        entity.setPercent(request.getPercent());
        entity.setAmount(request.getAmount());
        entity.setParamKey(request.getParamKey());
        entity.setValidFrom(request.getValidFrom());
        entity.setValidTo(request.getValidTo());
        entity.setPriority(priority);

        // Persist
        entity.persist();

        log.info("Created pricing rule: " + entity.getRuleId() + " for contract type: " + contractTypeCode);
        return PricingRuleStepDTO.fromEntity(entity);
    }

    /**
     * Create multiple rules at once (bulk operation).
     *
     * @param contractTypeCode The contract type code
     * @param request The bulk request with multiple rules
     * @return List of created rule DTOs
     */
    @Transactional
    @CacheInvalidate(cacheName = "pricing-rules")
    public List<PricingRuleStepDTO> createRulesBulk(String contractTypeCode, BulkCreateRulesRequest request) {
        log.info("PricingRuleStepService.createRulesBulk");
        log.info("contractTypeCode = " + contractTypeCode + ", rulesCount = " + request.getRules().size());

        // Validate contract type exists
        if (!ContractTypeDefinition.existsByCode(contractTypeCode)) {
            throw new NotFoundException("Contract type with code '" + contractTypeCode + "' not found");
        }

        List<PricingRuleStepDTO> results = new ArrayList<>();
        for (CreateRuleStepRequest ruleRequest : request.getRules()) {
            // Note: We call createRule for each, but cache is only invalidated once at the end
            PricingRuleStepDTO dto = createRuleInternal(contractTypeCode, ruleRequest);
            results.add(dto);
        }

        log.info("Created " + results.size() + " pricing rules for contract type: " + contractTypeCode);
        return results;
    }

    /**
     * Internal method to create a rule without cache invalidation (for bulk operations).
     */
    private PricingRuleStepDTO createRuleInternal(String contractTypeCode, CreateRuleStepRequest request) {
        // Validate uniqueness
        if (PricingRuleStepEntity.existsByContractTypeAndRuleId(contractTypeCode, request.getRuleId())) {
            throw new BadRequestException("Rule with ID '" + request.getRuleId() +
                    "' already exists for contract type '" + contractTypeCode + "'");
        }

        // Validate rule integrity
        validateRuleIntegrity(request.getRuleStepType(), request.getPercent(), request.getAmount(), request.getParamKey());

        // Auto-increment priority if not provided
        Integer priority = request.getPriority();
        if (priority == null) {
            int maxPriority = PricingRuleStepEntity.getMaxPriority(contractTypeCode);
            priority = maxPriority + DEFAULT_PRIORITY_INCREMENT;
        }

        // Create entity
        PricingRuleStepEntity entity = new PricingRuleStepEntity();
        entity.setContractTypeCode(contractTypeCode);
        entity.setRuleId(request.getRuleId());
        entity.setLabel(request.getLabel());
        entity.setRuleStepType(request.getRuleStepType());
        entity.setStepBase(request.getStepBase());
        entity.setPercent(request.getPercent());
        entity.setAmount(request.getAmount());
        entity.setParamKey(request.getParamKey());
        entity.setValidFrom(request.getValidFrom());
        entity.setValidTo(request.getValidTo());
        entity.setPriority(priority);

        entity.persist();
        return PricingRuleStepDTO.fromEntity(entity);
    }

    /**
     * Update an existing pricing rule step.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @param request The updated data
     * @return The updated rule DTO
     * @throws NotFoundException if rule not found
     * @throws BadRequestException if validation fails
     */
    @Transactional
    @CacheInvalidate(cacheName = "pricing-rules")
    public PricingRuleStepDTO updateRule(String contractTypeCode, String ruleId, UpdateRuleStepRequest request) {
        log.info("PricingRuleStepService.updateRule");
        log.info("contractTypeCode = " + contractTypeCode + ", ruleId = " + ruleId);

        // Find existing
        PricingRuleStepEntity entity = PricingRuleStepEntity.findByContractTypeAndRuleId(contractTypeCode, ruleId);
        if (entity == null) {
            throw new NotFoundException("Rule with ID '" + ruleId +
                    "' not found for contract type '" + contractTypeCode + "'");
        }

        // Validate rule integrity
        validateRuleIntegrity(request.getRuleStepType(), request.getPercent(), request.getAmount(), request.getParamKey());

        // Update fields
        entity.setLabel(request.getLabel());
        entity.setRuleStepType(request.getRuleStepType());
        entity.setStepBase(request.getStepBase());
        entity.setPercent(request.getPercent());
        entity.setAmount(request.getAmount());
        entity.setParamKey(request.getParamKey());
        entity.setValidFrom(request.getValidFrom());
        entity.setValidTo(request.getValidTo());
        entity.setPriority(request.getPriority());
        entity.setActive(request.isActive());

        // Persist (automatic with @Transactional)
        entity.persist();

        log.info("Updated pricing rule: " + ruleId);
        return PricingRuleStepDTO.fromEntity(entity);
    }

    /**
     * Delete a pricing rule step.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @throws NotFoundException if rule not found
     */
    @Transactional
    @CacheInvalidate(cacheName = "pricing-rules")
    public void deleteRule(String contractTypeCode, String ruleId) {
        log.info("PricingRuleStepService.deleteRule");
        log.info("contractTypeCode = " + contractTypeCode + ", ruleId = " + ruleId);

        // Find existing
        PricingRuleStepEntity entity = PricingRuleStepEntity.findByContractTypeAndRuleId(contractTypeCode, ruleId);
        if (entity == null) {
            throw new NotFoundException("Rule with ID '" + ruleId +
                    "' not found for contract type '" + contractTypeCode + "'");
        }

        // Soft delete
        entity.softDelete();

        log.info("Deleted pricing rule: " + ruleId);
    }

    /**
     * Get all rules for a contract type.
     *
     * @param contractTypeCode The contract type code
     * @param includeInactive Whether to include inactive rules
     * @return List of rule DTOs, sorted by priority
     */
    public List<PricingRuleStepDTO> getRulesForContractType(String contractTypeCode, boolean includeInactive) {
        log.debug("PricingRuleStepService.getRulesForContractType");
        log.debug("contractTypeCode = " + contractTypeCode + ", includeInactive = " + includeInactive);

        List<PricingRuleStepEntity> entities = includeInactive
                ? PricingRuleStepEntity.findByContractTypeIncludingInactive(contractTypeCode)
                : PricingRuleStepEntity.findByContractType(contractTypeCode);

        return entities.stream()
                .map(PricingRuleStepDTO::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Get a specific rule by contract type and rule ID.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @return The rule DTO
     * @throws NotFoundException if rule not found
     */
    public PricingRuleStepDTO getRule(String contractTypeCode, String ruleId) {
        log.debug("PricingRuleStepService.getRule");
        log.debug("contractTypeCode = " + contractTypeCode + ", ruleId = " + ruleId);

        PricingRuleStepEntity entity = PricingRuleStepEntity.findByContractTypeAndRuleId(contractTypeCode, ruleId);
        if (entity == null) {
            throw new NotFoundException("Rule with ID '" + ruleId +
                    "' not found for contract type '" + contractTypeCode + "'");
        }

        return PricingRuleStepDTO.fromEntity(entity);
    }

    /**
     * Validate rule integrity based on rule type.
     * Ensures required fields are present and valid.
     */
    private void validateRuleIntegrity(RuleStepType ruleStepType,
                                       java.math.BigDecimal percent,
                                       java.math.BigDecimal amount,
                                       String paramKey) {
        switch (ruleStepType) {
            case PERCENT_DISCOUNT_ON_SUM:
                // Must have either percent or paramKey
                if (percent == null && (paramKey == null || paramKey.trim().isEmpty())) {
                    throw new BadRequestException(
                            "PERCENT_DISCOUNT_ON_SUM rules must have either 'percent' or 'paramKey' set");
                }
                break;

            case ADMIN_FEE_PERCENT:
            case GENERAL_DISCOUNT_PERCENT:
                // Percent-based rules should have percent (unless using invoice.discount for GENERAL_DISCOUNT_PERCENT)
                // We allow null for GENERAL_DISCOUNT_PERCENT as it uses invoice.discount field
                if (ruleStepType == RuleStepType.ADMIN_FEE_PERCENT && percent == null) {
                    throw new BadRequestException("ADMIN_FEE_PERCENT rules must have 'percent' set");
                }
                break;

            case FIXED_DEDUCTION:
                // Must have amount
                if (amount == null) {
                    throw new BadRequestException("FIXED_DEDUCTION rules must have 'amount' set");
                }
                break;

            case ROUNDING:
                // No specific requirements
                break;

            default:
                log.warn("Unknown rule step type: " + ruleStepType);
        }
    }
}
