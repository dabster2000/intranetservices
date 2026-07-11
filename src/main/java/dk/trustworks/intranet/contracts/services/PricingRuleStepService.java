package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.pricing.RulePurpose;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.contracts.dto.*;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing pricing rule steps.
 * Provides CRUD operations, priority management, and validation.
 * Rule reads are deliberately uncached (spec §9.7) — the former
 * {@code @CacheInvalidate(cacheName = "pricing-rules")} annotations targeted a
 * cache that no {@code @CacheResult} ever populated and were removed.
 */
@JBossLog
@ApplicationScoped
public class PricingRuleStepService {

    @Inject
    EntityManager em;

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
        validateRuleIntegrity(request.getRuleStepType(), request.getPercent(), request.getAmount(),
                request.getParamKey(), request.getPurpose());

        // Validate date order up front so it surfaces as 400 (spec §9.5)
        validateDateOrder(request.getValidFrom(), request.getValidTo());

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
        entity.setPurpose(request.getPurpose());
        entity.setPercent(request.getPercent());
        entity.setAmount(request.getAmount());
        entity.setParamKey(normalizeParamKey(request.getParamKey()));
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
    public List<PricingRuleStepDTO> createRulesBulk(String contractTypeCode, BulkCreateRulesRequest request) {
        log.info("PricingRuleStepService.createRulesBulk");
        log.info("contractTypeCode = " + contractTypeCode + ", rulesCount = " + request.getRules().size());

        // Validate contract type exists
        if (!ContractTypeDefinition.existsByCode(contractTypeCode)) {
            throw new NotFoundException("Contract type with code '" + contractTypeCode + "' not found");
        }

        List<PricingRuleStepDTO> results = new ArrayList<>();
        for (CreateRuleStepRequest ruleRequest : request.getRules()) {
            PricingRuleStepDTO dto = createRuleInternal(contractTypeCode, ruleRequest);
            results.add(dto);
        }

        log.info("Created " + results.size() + " pricing rules for contract type: " + contractTypeCode);
        return results;
    }

    /**
     * Internal method to create a rule, skipping the contract-type existence
     * check the bulk entry point already performed.
     */
    private PricingRuleStepDTO createRuleInternal(String contractTypeCode, CreateRuleStepRequest request) {
        // Validate uniqueness
        if (PricingRuleStepEntity.existsByContractTypeAndRuleId(contractTypeCode, request.getRuleId())) {
            throw new BadRequestException("Rule with ID '" + request.getRuleId() +
                    "' already exists for contract type '" + contractTypeCode + "'");
        }

        // Validate rule integrity
        validateRuleIntegrity(request.getRuleStepType(), request.getPercent(), request.getAmount(),
                request.getParamKey(), request.getPurpose());

        // Validate date order up front so it surfaces as 400 (spec §9.5)
        validateDateOrder(request.getValidFrom(), request.getValidTo());

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
        entity.setPurpose(request.getPurpose());
        entity.setPercent(request.getPercent());
        entity.setAmount(request.getAmount());
        entity.setParamKey(normalizeParamKey(request.getParamKey()));
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
        validateRuleIntegrity(request.getRuleStepType(), request.getPercent(), request.getAmount(),
                request.getParamKey(), request.getPurpose());

        // Validate date order up front so it surfaces as 400 (spec §9.5)
        validateDateOrder(request.getValidFrom(), request.getValidTo());

        // Update fields
        entity.setLabel(request.getLabel());
        entity.setRuleStepType(request.getRuleStepType());
        entity.setStepBase(request.getStepBase());
        entity.setPurpose(request.getPurpose());
        entity.setPercent(request.getPercent());
        entity.setAmount(request.getAmount());
        entity.setParamKey(normalizeParamKey(request.getParamKey()));
        entity.setValidFrom(request.getValidFrom());
        entity.setValidTo(request.getValidTo());
        entity.setPriority(request.getPriority());
        // Null active means "leave unchanged" (spec §9.4) — a PUT omitting the field
        // must not silently re-activate a disabled rule.
        if (request.getActive() != null) {
            entity.setActive(request.getActive());
        }

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
     * Restore (reactivate) a soft-deleted pricing rule step.
     * Idempotent: activating an already-active rule is a no-op returning the current state.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @return The updated rule DTO
     * @throws NotFoundException if rule not found
     */
    @Transactional
    public PricingRuleStepDTO activateRule(String contractTypeCode, String ruleId) {
        log.info("PricingRuleStepService.activateRule");
        log.info("contractTypeCode = " + contractTypeCode + ", ruleId = " + ruleId);

        // Find existing
        PricingRuleStepEntity entity = PricingRuleStepEntity.findByContractTypeAndRuleId(contractTypeCode, ruleId);
        if (entity == null) {
            throw new NotFoundException("Rule with ID '" + ruleId +
                    "' not found for contract type '" + contractTypeCode + "'");
        }

        // Restore (idempotent when already active)
        entity.activate();
        // Flush so @PreUpdate has fired and the returned DTO carries the fresh updatedAt
        // (em is null only when the service is constructed directly in plain unit tests)
        if (em != null) {
            em.flush();
        }

        log.info("Activated pricing rule: " + ruleId);
        return PricingRuleStepDTO.fromEntity(entity);
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
     * Validate that validFrom is strictly before validTo when both are set (spec §9.5).
     * Mirrors the entity lifecycle rule ({@code PricingRuleStepEntity} @PrePersist/@PreUpdate),
     * but surfaces it as a 400 with a field-level message instead of an unhandled 500
     * from the lifecycle {@code IllegalArgumentException}.
     */
    private static void validateDateOrder(LocalDate validFrom, LocalDate validTo) {
        if (validFrom != null && validTo != null && !validFrom.isBefore(validTo)) {
            throw new BadRequestException(
                    "validFrom must be before validTo (validFrom=" + validFrom + ", validTo=" + validTo + ")");
        }
    }

    /**
     * Validate rule integrity based on rule type (spec §8.1 target taxonomy).
     * Ensures required fields are present and valid, and rejects the retired types:
     * ADMIN_FEE_PERCENT (folded into PERCENT_DISCOUNT_ON_SUM + purpose ADMIN_FEE by V396)
     * and ROUNDING (broken placeholder, never offered for creation). Rows persisted with
     * those types before the retype stay readable and executable — only create/update is blocked.
     */
    @SuppressWarnings("deprecation") // ADMIN_FEE_PERCENT/ROUNDING referenced to reject them
    private void validateRuleIntegrity(RuleStepType ruleStepType,
                                       java.math.BigDecimal percent,
                                       java.math.BigDecimal amount,
                                       String paramKey,
                                       RulePurpose purpose) {
        // Retired types are rejected outright (spec §8.2 step 3)
        if (ruleStepType == RuleStepType.ADMIN_FEE_PERCENT) {
            throw new BadRequestException(
                    "Rule step type ADMIN_FEE_PERCENT is not supported — use PERCENT_DISCOUNT_ON_SUM with purpose ADMIN_FEE");
        }
        if (ruleStepType == RuleStepType.ROUNDING) {
            throw new BadRequestException("Rule step type ROUNDING is not supported");
        }

        // purpose is a PERCENT_DISCOUNT_ON_SUM-only tag (DISCOUNT | ADMIN_FEE)
        if (purpose != null && ruleStepType != RuleStepType.PERCENT_DISCOUNT_ON_SUM) {
            throw new BadRequestException(
                    "'purpose' can only be set on PERCENT_DISCOUNT_ON_SUM rules");
        }

        if (paramKey != null && !paramKey.isBlank()) {
            if (ruleStepType != RuleStepType.PERCENT_DISCOUNT_ON_SUM) {
                throw new BadRequestException(
                        "'paramKey' can only be set on PERCENT_DISCOUNT_ON_SUM rules");
            }
            if (paramKey.trim().length() > 64) {
                throw new BadRequestException("'paramKey' must not exceed 64 characters");
            }
        }

        switch (ruleStepType) {
            case PERCENT_DISCOUNT_ON_SUM:
                // At least one of percent/paramKey; both together are allowed — the engine
                // uses percent as fallback when the paramKey is missing on the contract.
                if (percent == null && (paramKey == null || paramKey.trim().isEmpty())) {
                    throw new BadRequestException(
                            "PERCENT_DISCOUNT_ON_SUM rules must have either 'percent' or 'paramKey' set");
                }
                break;

            case GENERAL_DISCOUNT_PERCENT:
                // Requires neither percent nor paramKey — it only positions the
                // invoice-level discount (invoice.discount) in the pipeline.
                break;

            case FIXED_DEDUCTION:
                // Must have amount
                if (amount == null) {
                    throw new BadRequestException("FIXED_DEDUCTION rules must have 'amount' set");
                }
                break;

            default:
                log.warn("Unknown rule step type: " + ruleStepType);
        }
    }

    private static String normalizeParamKey(String paramKey) {
        return paramKey == null || paramKey.isBlank() ? null : paramKey.trim();
    }
}
