package dk.trustworks.intranet.dao.workservice.validation;

import dk.trustworks.intranet.contracts.model.ContractValidationRuleEntity;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import dk.trustworks.intranet.contracts.services.RuleResolutionService;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetContractResolver.EligibleContract;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetValidationPolicyCache.Policy;
import io.quarkus.cache.CacheInvalidateAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimesheetValidationPolicyCacheTest {

    @Test
    void loadsBaseValidationRulesDirectlyEvenWhenOverrideFacadeWouldBeEmpty() {
        LocalDate date = LocalDate.of(2026, 7, 10);
        EligibleContract contract = new EligibleContract(
                "contract", "AGREEMENT", "Agreement Name", "project", "client");
        RuleResolutionService ruleResolutionService = mock(RuleResolutionService.class);
        when(ruleResolutionService.getEffectiveValidationRules("AGREEMENT", "contract", date))
                .thenReturn(List.of(
                        rule("later", ValidationType.MIN_HOURS_PER_ENTRY, 20),
                        rule("notes", ValidationType.NOTES_REQUIRED, 10),
                        rule("ignored", ValidationType.MAX_HOURS_PER_DAY, 5)));

        TimesheetValidationPolicyCache cache = new TimesheetValidationPolicyCache();
        cache.ruleResolutionService = ruleResolutionService;

        Policy policy = cache.getPolicy(contract, date);

        verify(ruleResolutionService).getEffectiveValidationRules("AGREEMENT", "contract", date);
        verify(ruleResolutionService, never()).getEffectiveRuleSet("contract", date);
        assertEquals(List.of("notes", "later"),
                policy.rules().stream().map(TimesheetValidationPolicyCache.ValidationRule::ruleId).toList());
        assertThrows(UnsupportedOperationException.class, () -> policy.rules().clear());
    }

    @Test
    void exposesExplicitWholePolicyCacheInvalidationHook() throws NoSuchMethodException {
        CacheInvalidateAll annotation = TimesheetValidationPolicyCache.class
                .getMethod("invalidateAll")
                .getAnnotation(CacheInvalidateAll.class);

        assertNotNull(annotation);
        assertEquals(TimesheetValidationPolicyCache.CACHE_NAME, annotation.cacheName());
    }

    private static ContractValidationRuleEntity rule(String id, ValidationType type, int priority) {
        ContractValidationRuleEntity rule = new ContractValidationRuleEntity();
        rule.setRuleId(id);
        rule.setValidationType(type);
        rule.setPriority(priority);
        rule.setRequired(type == ValidationType.NOTES_REQUIRED);
        rule.setThresholdValue(type == ValidationType.MIN_HOURS_PER_ENTRY ? BigDecimal.ONE : null);
        return rule;
    }
}
