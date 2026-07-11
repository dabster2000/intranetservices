package dk.trustworks.intranet.dao.workservice.validation;

import dk.trustworks.intranet.contracts.model.ContractValidationRuleEntity;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import dk.trustworks.intranet.contracts.services.RuleResolutionService;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetContractResolver.EligibleContract;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Bounded/TTL-backed immutable cache of only the validation policy needed during work saves.
 * Rate and pricing overrides are deliberately never loaded on this high-frequency path.
 */
@ApplicationScoped
public class TimesheetValidationPolicyCache {

    static final String CACHE_NAME = "timesheet-validation-policies";

    @Inject
    RuleResolutionService ruleResolutionService;

    @CacheResult(cacheName = CACHE_NAME)
    public Policy getPolicy(EligibleContract contract, LocalDate effectiveDate) {
        // Direct call is intentional: getEffectiveRuleSet() returns an empty set when the override
        // rollout flag is OFF, while getEffectiveValidationRules() correctly retains base rules.
        List<ValidationRule> rules = ruleResolutionService.getEffectiveValidationRules(
                        contract.contractTypeCode(), contract.contractUuid(), effectiveDate).stream()
                .filter(TimesheetValidationPolicyCache::isTimesheetRule)
                .map(ValidationRule::fromEntity)
                .sorted(Comparator
                        .comparing(ValidationRule::priority, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(ValidationRule::ruleId, Comparator.nullsLast(String::compareTo)))
                .toList();

        return new Policy(
                contract.contractUuid(),
                contract.contractTypeCode(),
                contract.agreementName(),
                effectiveDate,
                rules);
    }

    @CacheInvalidateAll(cacheName = CACHE_NAME)
    public void invalidateAll() {
        // Annotation performs the invalidation through the CDI interceptor.
    }

    private static boolean isTimesheetRule(ContractValidationRuleEntity rule) {
        return rule.getValidationType() == ValidationType.NOTES_REQUIRED
                || rule.getValidationType() == ValidationType.MIN_HOURS_PER_ENTRY;
    }

    public record Policy(
            String contractUuid,
            String contractTypeCode,
            String agreementName,
            LocalDate effectiveDate,
            List<ValidationRule> rules) {

        public Policy {
            rules = List.copyOf(rules);
        }
    }

    public record ValidationRule(
            String ruleId,
            ValidationType type,
            boolean required,
            BigDecimal threshold,
            Integer priority) {

        static ValidationRule fromEntity(ContractValidationRuleEntity entity) {
            return new ValidationRule(
                    entity.getRuleId(),
                    entity.getValidationType(),
                    entity.isRequired(),
                    entity.getThresholdValue(),
                    entity.getPriority());
        }
    }
}
