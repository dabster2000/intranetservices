package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.contracts.dto.CreateRuleStepRequest;
import dk.trustworks.intranet.contracts.dto.CreateValidationRuleRequest;
import dk.trustworks.intranet.contracts.dto.PricingRuleStepDTO;
import dk.trustworks.intranet.contracts.dto.ValidationRuleDTO;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.ContractValidationRuleEntity;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-backed round-trip tests for soft-delete symmetry and restore (spec §9.3 / C5+C6):
 * create → soft delete → activate → active again, on the same row (ruleId is never blocked),
 * plus inactive-validation-rule inclusion in the all-rules listing and 404 for unknown rule IDs.
 *
 * Requires the local dev database (same requirement as every other {@code @QuarkusTest} in this repo);
 * all writes roll back via {@code @TestTransaction}.
 */
@QuarkusTest
@TestSecurity(user = "admin", roles = {"contracts:read", "contracts:write"})
class ContractRuleRestoreRoundTripTest {

    @Inject
    PricingRuleStepService pricingRuleService;

    @Inject
    ContractValidationRuleService validationRuleService;

    @Test
    @TestTransaction
    void pricingRule_createSoftDeleteActivate_roundTrip() {
        String code = uniqueCode();
        persistContractType(code);

        CreateRuleStepRequest request = new CreateRuleStepRequest();
        request.setRuleId("restore-me");
        request.setLabel("Restore round-trip pricing rule");
        request.setRuleStepType(RuleStepType.ADMIN_FEE_PERCENT);
        request.setStepBase(StepBase.CURRENT_SUM);
        request.setPercent(new BigDecimal("5.00"));
        PricingRuleStepDTO created = pricingRuleService.createRule(code, request);
        assertTrue(created.isActive());

        // Soft delete
        pricingRuleService.deleteRule(code, "restore-me");
        PricingRuleStepEntity deleted = PricingRuleStepEntity.findByContractTypeAndRuleId(code, "restore-me");
        assertFalse(deleted.isActive(), "DELETE must soft delete (active=false), not remove the row");
        assertTrue(pricingRuleService.getRulesForContractType(code, false).isEmpty());

        // Restore — the soft-deleted ruleId must not block reactivation
        PricingRuleStepDTO restored = pricingRuleService.activateRule(code, "restore-me");
        assertTrue(restored.isActive());
        assertEquals(created.getId(), restored.getId(), "restore must reuse the same row, not create a new one");
        assertEquals(1, PricingRuleStepEntity.count("contractTypeCode = ?1 AND ruleId = ?2", code, "restore-me"));

        // Rule is active again in the active-only listing
        assertTrue(pricingRuleService.getRulesForContractType(code, false).stream()
                .anyMatch(r -> "restore-me".equals(r.getRuleId()) && r.isActive()));

        // Idempotent: activating an already-active rule succeeds and stays active
        PricingRuleStepDTO again = pricingRuleService.activateRule(code, "restore-me");
        assertTrue(again.isActive());
        assertEquals(created.getId(), again.getId());
    }

    @Test
    @TestTransaction
    void validationRule_createSoftDeleteActivate_roundTrip_andInactiveIncludedInAllRulesListing() {
        String code = uniqueCode();
        persistContractType(code);

        CreateValidationRuleRequest request = new CreateValidationRuleRequest();
        request.setRuleId("restore-me");
        request.setLabel("Restore round-trip validation rule");
        request.setValidationType(ValidationType.NOTES_REQUIRED);
        request.setRequired(true);
        request.setPriority(10);
        ValidationRuleDTO created = validationRuleService.create(code, request);
        assertTrue(created.isActive());

        // Soft delete
        validationRuleService.softDelete(code, "restore-me");
        ContractValidationRuleEntity deleted = ContractValidationRuleEntity.findByContractTypeAndRuleId(code, "restore-me");
        assertFalse(deleted.isActive(), "DELETE must soft delete (active=false), not remove the row");

        // C6: the includeInactive listing (backing /all-rules) must surface the soft-deleted rule
        assertTrue(validationRuleService.listAll(code, true).stream()
                        .anyMatch(r -> "restore-me".equals(r.getRuleId()) && !r.isActive()),
                "inactive validation rules must be included when includeInactive=true");
        assertTrue(validationRuleService.listAll(code, false).isEmpty(),
                "active-only listing must exclude the soft-deleted rule");

        // Restore — the soft-deleted ruleId must not block reactivation
        ValidationRuleDTO restored = validationRuleService.activate(code, "restore-me");
        assertTrue(restored.isActive());
        assertEquals(created.getId(), restored.getId(), "restore must reuse the same row, not create a new one");
        assertEquals(1, ContractValidationRuleEntity.count("contractTypeCode = ?1 AND ruleId = ?2", code, "restore-me"));

        // Idempotent second activate
        assertTrue(validationRuleService.activate(code, "restore-me").isActive());
    }

    @Test
    @TestTransaction
    void activate_unknownRuleId_throwsNotFound() {
        String code = uniqueCode();
        persistContractType(code);

        assertThrows(NotFoundException.class, () -> pricingRuleService.activateRule(code, "does-not-exist"));
        assertThrows(NotFoundException.class, () -> validationRuleService.activate(code, "does-not-exist"));
    }

    private void persistContractType(String code) {
        ContractTypeDefinition contractType = new ContractTypeDefinition();
        contractType.setCode(code);
        contractType.setName("Restore round-trip test type");
        contractType.persist();
    }

    private String uniqueCode() {
        return "ZZRESTORE" + (System.nanoTime() % 1_000_000_000L);
    }
}
