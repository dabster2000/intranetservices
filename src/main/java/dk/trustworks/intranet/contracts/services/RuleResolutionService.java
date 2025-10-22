package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.dto.ContractRuleSetDTO;
import dk.trustworks.intranet.contracts.mappers.ContractOverrideMapper;
import dk.trustworks.intranet.contracts.model.*;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for resolving effective rules by merging base contract type rules with contract-level overrides.
 *
 * <p>This is the core business logic for the override system. It implements the merge strategy:
 * - Load base rules from contract type
 * - Load overrides from contract
 * - Apply merge logic based on override type (REPLACE, DISABLE, MODIFY)
 * - Return the final effective rule set
 *
 * <p><b>Merge Strategy:</b>
 * <ul>
 *   <li><b>REPLACE</b>: Override completely replaces the base rule</li>
 *   <li><b>DISABLE</b>: Base rule is removed from effective set</li>
 *   <li><b>MODIFY</b>: Non-null override fields are merged with base rule fields</li>
 * </ul>
 *
 * <p><b>Caching:</b>
 * - Results are cached by contract UUID and date
 * - Cache is invalidated when overrides are created/updated/deleted
 * - TTL is configured via feature service
 *
 * @see ContractValidationRuleEntity
 * @see ContractRateAdjustmentEntity
 * @see PricingRuleStepEntity
 */
@ApplicationScoped
@JBossLog
public class RuleResolutionService {

    @Inject
    ContractOverrideFeatureService featureService;

    @Inject
    ContractOverrideMapper mapper;

    /**
     * Get the complete effective rule set for a contract on a specific date.
     * This is the main entry point for rule resolution.
     *
     * @param contractUuid The contract UUID
     * @param effectiveDate The date for which to calculate effective rules
     * @return Complete rule set with all overrides applied
     */
    @CacheResult(cacheName = "contract-effective-rules")
    public ContractRuleSetDTO getEffectiveRuleSet(
        @CacheKey String contractUuid,
        @CacheKey LocalDate effectiveDate
    ) {
        log.debugf("Resolving effective rules for contract %s on date %s", contractUuid, effectiveDate);

        // Check if overrides are enabled for this contract
        if (!featureService.isOverrideSystemEnabled() ||
            !featureService.isEnabledForContract(contractUuid)) {
            log.debugf("Overrides not enabled for contract %s, returning empty rule set", contractUuid);
            return ContractRuleSetDTO.builder()
                .contractUuid(contractUuid)
                .effectiveDate(effectiveDate)
                .fromCache(false)
                .build();
        }

        // Get all overrides for this contract
        List<ContractValidationOverride> validationOverrides =
            ContractValidationOverride.findByContract(contractUuid);
        List<ContractRateAdjustmentOverride> rateOverrides =
            ContractRateAdjustmentOverride.findByContractAndDate(contractUuid, effectiveDate);
        List<PricingRuleOverride> pricingOverrides =
            PricingRuleOverride.findByContractAndDate(contractUuid, effectiveDate);

        // Fetch contract to get its contract type
        Contract contract = Contract.findById(contractUuid);
        String contractTypeCode = contract != null ? contract.getContractType() : null;
        log.infof("Contract %s has contractType: '%s'", contractUuid, contractTypeCode);

        if (contractTypeCode == null || contractTypeCode.isEmpty()) {
            log.warnf("Contract %s has no contract type defined - will process overrides only", contractUuid);
        }

        // Get effective merged rules (framework + overrides)
        // Even if contractTypeCode is null, we need to process overrides (especially REPLACE type)
        List<ContractValidationRuleEntity> effectiveValidationRules =
            getEffectiveValidationRules(contractTypeCode != null ? contractTypeCode : "", contractUuid, effectiveDate);
        List<ContractRateAdjustmentEntity> effectiveRateAdjustments =
            getEffectiveRateAdjustments(contractTypeCode != null ? contractTypeCode : "", contractUuid, effectiveDate);
        List<PricingRuleStepEntity> effectivePricingRules =
            getEffectivePricingRules(contractTypeCode != null ? contractTypeCode : "", contractUuid, effectiveDate);

        // Build the rule set DTO
        ContractRuleSetDTO ruleSet = ContractRuleSetDTO.builder()
            .contractUuid(contractUuid)
            .effectiveDate(effectiveDate)
            .validationOverrides(mapper.toValidationDTOs(validationOverrides))
            .rateOverrides(mapper.toRateDTOs(rateOverrides))
            .pricingOverrides(mapper.toPricingDTOs(pricingOverrides))
            // Use override-aware mapping to enrich DTOs with override metadata
            .effectiveValidationRules(mapper.fromValidationEntities(effectiveValidationRules, validationOverrides))
            .effectiveRateAdjustments(mapper.fromRateEntities(effectiveRateAdjustments, rateOverrides))
            .effectivePricingRules(mapper.fromPricingEntities(effectivePricingRules, pricingOverrides))
            .fromCache(true)
            .build();

        log.debugf("Resolved rule set: %s with %d effective validation rules, %d rate adjustments, %d pricing rules",
            ruleSet, effectiveValidationRules.size(), effectiveRateAdjustments.size(), effectivePricingRules.size());

        return ruleSet;
    }

    /**
     * Resolve effective validation rules for a contract.
     * Merges base rules from contract type with contract-specific overrides.
     *
     * @param contractTypeCode The contract type code
     * @param contractUuid The contract UUID
     * @param effectiveDate The date for applicability filtering
     * @return List of effective validation rules, sorted by priority
     */
    public List<ContractValidationRuleEntity> getEffectiveValidationRules(
        String contractTypeCode,
        String contractUuid,
        LocalDate effectiveDate
    ) {
        log.debugf("Resolving validation rules for contract %s (type %s)", contractUuid, contractTypeCode);

        // Get base rules from contract type (empty list if no contract type)
        List<ContractValidationRuleEntity> baseRules =
            (contractTypeCode != null && !contractTypeCode.isEmpty()) ?
            ContractValidationRuleEntity.findByContractType(contractTypeCode) : List.of();

        // Check if overrides are enabled
        if (!featureService.isOverrideSystemEnabled() ||
            !featureService.isEnabledForContract(contractUuid)) {
            log.debugf("Overrides not enabled, returning %d base rules", baseRules.size());
            return baseRules.stream()
                .filter(ContractValidationRuleEntity::isActive)
                .sorted(Comparator.comparing(ContractValidationRuleEntity::getPriority))
                .collect(Collectors.toList());
        }

        // Get contract overrides
        List<ContractValidationOverride> overrides =
            ContractValidationOverride.findByContract(contractUuid);

        // Merge rules
        Map<String, ContractValidationRuleEntity> effectiveRules = new LinkedHashMap<>();

        // 1. Add base rules to map
        for (ContractValidationRuleEntity rule : baseRules) {
            if (rule.isActive()) {
                effectiveRules.put(rule.getRuleId(), rule);
            }
        }

        // 2. Apply overrides
        for (ContractValidationOverride override : overrides) {
            if (override.isApplicable(effectiveDate)) {
                String ruleId = override.getRuleId();
                ContractValidationRuleEntity baseRule = effectiveRules.get(ruleId);

                switch (override.getOverrideType()) {
                    case DISABLE:
                        // Remove rule from effective set
                        effectiveRules.remove(ruleId);
                        log.debugf("DISABLE: Removed rule %s", ruleId);
                        break;

                    case REPLACE: {
                        // Merge or replace and ensure most-specific (contract) wins by validationType
                        ContractValidationRuleEntity mergedRule = override.merge(baseRule);
                        if (mergedRule != null) {
                            if (mergedRule.getValidationType() != null) {
                                var vt = mergedRule.getValidationType();
                                // Remove any existing rule with the same validationType but different ruleId
                                effectiveRules.entrySet().removeIf(e ->
                                    e.getValue().getValidationType() == vt && !e.getKey().equals(ruleId)
                                );
                            }
                            effectiveRules.put(ruleId, mergedRule);
                            log.debugf("REPLACE: Applied override for rule %s (suppressed same-type base rules)", ruleId);
                        }
                        break;
                    }
                    case MODIFY:
                        // Merge attributes into base rule with same ruleId
                        ContractValidationRuleEntity mergedRule = override.merge(baseRule);
                        if (mergedRule != null) {
                            effectiveRules.put(ruleId, mergedRule);
                            log.debugf("MODIFY: Applied override for rule %s", ruleId);
                        }
                        break;
                }
            }
        }

        // 3. Sort by priority and return
        List<ContractValidationRuleEntity> result = effectiveRules.values().stream()
            .sorted(Comparator.comparing(ContractValidationRuleEntity::getPriority))
            .collect(Collectors.toList());

        log.debugf("Resolved %d validation rules (from %d base + %d overrides)",
            result.size(), baseRules.size(), overrides.size());

        return result;
    }

    /**
     * Resolve effective rate adjustments for a contract.
     * Merges base rate adjustments from contract type with contract-specific overrides.
     *
     * @param contractTypeCode The contract type code
     * @param contractUuid The contract UUID
     * @param effectiveDate The date for applicability filtering
     * @return List of effective rate adjustments, sorted by priority
     */
    public List<ContractRateAdjustmentEntity> getEffectiveRateAdjustments(
        String contractTypeCode,
        String contractUuid,
        LocalDate effectiveDate
    ) {
        log.debugf("Resolving rate adjustments for contract %s (type %s)", contractUuid, contractTypeCode);

        // Get base rate adjustments from contract type (empty list if no contract type)
        List<ContractRateAdjustmentEntity> baseRules =
            (contractTypeCode != null && !contractTypeCode.isEmpty()) ?
            ContractRateAdjustmentEntity.findByContractType(contractTypeCode) : List.of();

        // Check if overrides are enabled
        if (!featureService.isOverrideSystemEnabled() ||
            !featureService.isEnabledForContract(contractUuid)) {
            return baseRules.stream()
                .filter(rule -> rule.isActiveOn(effectiveDate))
                .sorted(Comparator.comparing(ContractRateAdjustmentEntity::getPriority))
                .collect(Collectors.toList());
        }

        // Get contract overrides
        List<ContractRateAdjustmentOverride> overrides =
            ContractRateAdjustmentOverride.findByContractAndDate(contractUuid, effectiveDate);

        // Merge rules
        Map<String, ContractRateAdjustmentEntity> effectiveRules = new LinkedHashMap<>();

        // 1. Add base rules to map
        for (ContractRateAdjustmentEntity rule : baseRules) {
            if (rule.isActiveOn(effectiveDate)) {
                effectiveRules.put(rule.getRuleId(), rule);
            }
        }

        // 2. Apply overrides
        for (ContractRateAdjustmentOverride override : overrides) {
            if (override.isApplicable(effectiveDate)) {
                String ruleId = override.getRuleId();
                ContractRateAdjustmentEntity baseRule = effectiveRules.get(ruleId);

                switch (override.getOverrideType()) {
                    case DISABLE:
                        effectiveRules.remove(ruleId);
                        log.debugf("DISABLE: Removed rate adjustment %s", ruleId);
                        break;

                    case REPLACE: {
                        // Ensure most-specific (contract) wins by adjustmentType
                        ContractRateAdjustmentEntity mergedRule = override.merge(baseRule);
                        if (mergedRule != null) {
                            if (mergedRule.getAdjustmentType() != null) {
                                var at = mergedRule.getAdjustmentType();
                                effectiveRules.entrySet().removeIf(e ->
                                    e.getValue().getAdjustmentType() == at && !e.getKey().equals(ruleId)
                                );
                            }
                            effectiveRules.put(ruleId, mergedRule);
                            log.debugf("REPLACE: Applied override for rate adjustment %s (suppressed same-type base adjustments)", ruleId);
                        }
                        break;
                    }
                    case MODIFY:
                        ContractRateAdjustmentEntity mergedRule = override.merge(baseRule);
                        if (mergedRule != null) {
                            effectiveRules.put(ruleId, mergedRule);
                            log.debugf("MODIFY: Applied override for rate adjustment %s", ruleId);
                        }
                        break;
                }
            }
        }

        // 3. Sort by priority and return
        List<ContractRateAdjustmentEntity> result = effectiveRules.values().stream()
            .sorted(Comparator.comparing(ContractRateAdjustmentEntity::getPriority))
            .collect(Collectors.toList());

        log.debugf("Resolved %d rate adjustments (from %d base + %d overrides)",
            result.size(), baseRules.size(), overrides.size());

        return result;
    }

    /**
     * Resolve effective pricing rules for a contract.
     * Merges base pricing rules from contract type with contract-specific overrides.
     *
     * @param contractTypeCode The contract type code
     * @param contractUuid The contract UUID
     * @param effectiveDate The date for applicability filtering
     * @return List of effective pricing rule steps, sorted by priority
     */
    public List<PricingRuleStepEntity> getEffectivePricingRules(
        String contractTypeCode,
        String contractUuid,
        LocalDate effectiveDate
    ) {
        log.debugf("Resolving pricing rules for contract %s (type %s)", contractUuid, contractTypeCode);

        // Get base pricing rules from contract type (empty list if no contract type)
        List<PricingRuleStepEntity> baseRules =
            (contractTypeCode != null && !contractTypeCode.isEmpty()) ?
            PricingRuleStepEntity.findByContractType(contractTypeCode) : List.of();

        // Check if overrides are enabled
        if (!featureService.isOverrideSystemEnabled() ||
            !featureService.isEnabledForContract(contractUuid)) {
            return baseRules.stream()
                .filter(rule -> rule.isActiveOn(effectiveDate))
                .sorted(Comparator.comparing(PricingRuleStepEntity::getPriority))
                .collect(Collectors.toList());
        }

        // Get contract overrides
        List<PricingRuleOverride> overrides =
            PricingRuleOverride.findByContractAndDate(contractUuid, effectiveDate);

        // Merge rules
        Map<String, PricingRuleStepEntity> effectiveRules = new LinkedHashMap<>();

        // 1. Add base rules to map
        for (PricingRuleStepEntity rule : baseRules) {
            if (rule.isActiveOn(effectiveDate)) {
                effectiveRules.put(rule.getRuleId(), rule);
            }
        }

        // 2. Apply overrides
        for (PricingRuleOverride override : overrides) {
            if (override.isApplicable(effectiveDate)) {
                String ruleId = override.getRuleId();
                PricingRuleStepEntity baseRule = effectiveRules.get(ruleId);

                switch (override.getOverrideType()) {
                    case DISABLE:
                        effectiveRules.remove(ruleId);
                        log.debugf("DISABLE: Removed pricing rule %s", ruleId);
                        break;

                    case REPLACE: {
                        // Ensure most-specific (contract) wins by ruleStepType
                        PricingRuleStepEntity mergedRule = override.merge(baseRule);
                        if (mergedRule != null) {
                            if (mergedRule.getRuleStepType() != null) {
                                var st = mergedRule.getRuleStepType();
                                effectiveRules.entrySet().removeIf(e ->
                                    e.getValue().getRuleStepType() == st && !e.getKey().equals(ruleId)
                                );
                            }
                            effectiveRules.put(ruleId, mergedRule);
                            log.debugf("REPLACE: Applied override for pricing rule %s (suppressed same-type base rules)", ruleId);
                        }
                        break;
                    }
                    case MODIFY:
                        PricingRuleStepEntity mergedRule = override.merge(baseRule);
                        if (mergedRule != null) {
                            effectiveRules.put(ruleId, mergedRule);
                            log.debugf("MODIFY: Applied override for pricing rule %s", ruleId);
                        }
                        break;
                }
            }
        }

        // 3. Sort by priority and return
        List<PricingRuleStepEntity> result = effectiveRules.values().stream()
            .sorted(Comparator.comparing(PricingRuleStepEntity::getPriority))
            .collect(Collectors.toList());

        log.debugf("Resolved %d pricing rules (from %d base + %d overrides)",
            result.size(), baseRules.size(), overrides.size());

        return result;
    }

    /**
     * Merge a validation rule with an override.
     * Public method for testing and direct usage.
     *
     * @param baseRule The base rule from contract type (may be null for REPLACE)
     * @param override The override to apply
     * @return The merged rule, or null if disabled
     */
    public ContractValidationRuleEntity mergeValidationRule(
        ContractValidationRuleEntity baseRule,
        ContractValidationOverride override
    ) {
        return override.merge(baseRule);
    }

    /**
     * Merge a rate adjustment with an override.
     * Public method for testing and direct usage.
     *
     * @param baseRule The base rule from contract type (may be null for REPLACE)
     * @param override The override to apply
     * @return The merged rule, or null if disabled
     */
    public ContractRateAdjustmentEntity mergeRateAdjustment(
        ContractRateAdjustmentEntity baseRule,
        ContractRateAdjustmentOverride override
    ) {
        return override.merge(baseRule);
    }

    /**
     * Merge a pricing rule with an override.
     * Public method for testing and direct usage.
     *
     * @param baseRule The base rule from contract type (may be null for REPLACE)
     * @param override The override to apply
     * @return The merged rule, or null if disabled
     */
    public PricingRuleStepEntity mergePricingRule(
        PricingRuleStepEntity baseRule,
        PricingRuleOverride override
    ) {
        return override.merge(baseRule);
    }
}
