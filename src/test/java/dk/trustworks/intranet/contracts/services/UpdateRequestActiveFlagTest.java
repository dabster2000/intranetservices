package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.contracts.dto.CreateRateAdjustmentRequest;
import dk.trustworks.intranet.contracts.dto.CreateRuleStepRequest;
import dk.trustworks.intranet.contracts.dto.CreateValidationRuleRequest;
import dk.trustworks.intranet.contracts.dto.UpdateRateAdjustmentRequest;
import dk.trustworks.intranet.contracts.dto.UpdateRuleStepRequest;
import dk.trustworks.intranet.contracts.dto.UpdateValidationRuleRequest;
import dk.trustworks.intranet.contracts.model.ContractRateAdjustmentEntity;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.ContractValidationRuleEntity;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import dk.trustworks.intranet.contracts.model.enums.AdjustmentType;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-backed tests for the update-DTO active-flag hazards (spec §9.4 / P4).
 *
 * All three Update*Request DTOs carry {@code Boolean active} where null means
 * "leave unchanged". The two prior hazards this pins down:
 * <ul>
 *   <li>{@code UpdateRuleStepRequest.active} defaulted to {@code true} — a PUT omitting
 *       the field silently RE-ACTIVATED a disabled pricing rule.</li>
 *   <li>{@code UpdateValidationRuleRequest.active} / {@code UpdateRateAdjustmentRequest.active}
 *       defaulted to {@code false} — a PUT omitting the field silently DEACTIVATED an
 *       active rule/adjustment.</li>
 * </ul>
 *
 * Requires the local dev database (same requirement as every other {@code @QuarkusTest}
 * in this repo); all writes roll back via {@code @TestTransaction}.
 */
@QuarkusTest
@TestSecurity(user = "admin", roles = {"contracts:read", "contracts:write"})
class UpdateRequestActiveFlagTest {

    @Inject
    PricingRuleStepService pricingRuleService;

    @Inject
    ContractValidationRuleService validationRuleService;

    @Inject
    ContractRateAdjustmentService rateAdjustmentService;

    // --- Pricing rules: omitting active must NOT silently re-activate a disabled rule ---

    @Test
    @TestTransaction
    void pricingRule_putOmittingActive_leavesDisabledRuleDisabled() {
        String code = uniqueCode();
        persistContractType(code);
        pricingRuleService.createRule(code, pricingCreateRequest("flag-rule"));

        // Disable the rule
        pricingRuleService.deleteRule(code, "flag-rule");
        assertFalse(PricingRuleStepEntity.findByContractTypeAndRuleId(code, "flag-rule").isActive());

        // PUT without the active field (null) — must leave the rule disabled
        UpdateRuleStepRequest update = pricingUpdateRequest();
        update.setActive(null);
        assertFalse(pricingRuleService.updateRule(code, "flag-rule", update).isActive(),
                "PUT omitting 'active' must not silently re-activate a disabled pricing rule");
        assertFalse(PricingRuleStepEntity.findByContractTypeAndRuleId(code, "flag-rule").isActive());
    }

    @Test
    @TestTransaction
    void pricingRule_putWithExplicitActive_stillApplies() {
        String code = uniqueCode();
        persistContractType(code);
        pricingRuleService.createRule(code, pricingCreateRequest("flag-rule"));
        pricingRuleService.deleteRule(code, "flag-rule");

        // Explicit true re-activates
        UpdateRuleStepRequest activate = pricingUpdateRequest();
        activate.setActive(Boolean.TRUE);
        assertTrue(pricingRuleService.updateRule(code, "flag-rule", activate).isActive());

        // Explicit false deactivates
        UpdateRuleStepRequest deactivate = pricingUpdateRequest();
        deactivate.setActive(Boolean.FALSE);
        assertFalse(pricingRuleService.updateRule(code, "flag-rule", deactivate).isActive());
    }

    // --- Validation rules: omitting active must NOT silently deactivate an active rule ---

    @Test
    @TestTransaction
    void validationRule_putOmittingActive_leavesActiveRuleActive() {
        String code = uniqueCode();
        persistContractType(code);
        validationRuleService.create(code, validationCreateRequest("flag-rule"));
        assertTrue(ContractValidationRuleEntity.findByContractTypeAndRuleId(code, "flag-rule").isActive());

        // PUT without the active field (null) — must leave the rule active
        UpdateValidationRuleRequest update = validationUpdateRequest();
        update.setActive(null);
        assertTrue(validationRuleService.update(code, "flag-rule", update).isActive(),
                "PUT omitting 'active' must not silently deactivate an active validation rule");
        assertTrue(ContractValidationRuleEntity.findByContractTypeAndRuleId(code, "flag-rule").isActive());
    }

    @Test
    @TestTransaction
    void validationRule_putWithExplicitActive_stillApplies() {
        String code = uniqueCode();
        persistContractType(code);
        validationRuleService.create(code, validationCreateRequest("flag-rule"));

        UpdateValidationRuleRequest deactivate = validationUpdateRequest();
        deactivate.setActive(Boolean.FALSE);
        assertFalse(validationRuleService.update(code, "flag-rule", deactivate).isActive());

        UpdateValidationRuleRequest activate = validationUpdateRequest();
        activate.setActive(Boolean.TRUE);
        assertTrue(validationRuleService.update(code, "flag-rule", activate).isActive());
    }

    // --- Rate adjustments: omitting active must NOT silently deactivate an active adjustment ---

    @Test
    @TestTransaction
    void rateAdjustment_putOmittingActive_leavesActiveAdjustmentActive() {
        String code = uniqueCode();
        persistContractType(code);
        rateAdjustmentService.create(code, rateAdjustmentCreateRequest("flag-rule"));
        assertTrue(ContractRateAdjustmentEntity.findByContractTypeAndRuleId(code, "flag-rule").isActive());

        // PUT without the active field (null) — must leave the adjustment active
        UpdateRateAdjustmentRequest update = rateAdjustmentUpdateRequest();
        update.setActive(null);
        assertTrue(rateAdjustmentService.update(code, "flag-rule", update).isActive(),
                "PUT omitting 'active' must not silently deactivate an active rate adjustment");
        assertTrue(ContractRateAdjustmentEntity.findByContractTypeAndRuleId(code, "flag-rule").isActive());
    }

    @Test
    @TestTransaction
    void rateAdjustment_putWithExplicitActive_stillApplies() {
        String code = uniqueCode();
        persistContractType(code);
        rateAdjustmentService.create(code, rateAdjustmentCreateRequest("flag-rule"));

        UpdateRateAdjustmentRequest deactivate = rateAdjustmentUpdateRequest();
        deactivate.setActive(Boolean.FALSE);
        assertFalse(rateAdjustmentService.update(code, "flag-rule", deactivate).isActive());

        UpdateRateAdjustmentRequest activate = rateAdjustmentUpdateRequest();
        activate.setActive(Boolean.TRUE);
        assertTrue(rateAdjustmentService.update(code, "flag-rule", activate).isActive());
    }

    // --- Fixture helpers ---

    private CreateRuleStepRequest pricingCreateRequest(String ruleId) {
        CreateRuleStepRequest request = new CreateRuleStepRequest();
        request.setRuleId(ruleId);
        request.setLabel("Active-flag test pricing rule");
        request.setRuleStepType(RuleStepType.PERCENT_DISCOUNT_ON_SUM);
        request.setStepBase(StepBase.SUM_BEFORE_DISCOUNTS);
        request.setPercent(new BigDecimal("5.00"));
        request.setPriority(10);
        return request;
    }

    private UpdateRuleStepRequest pricingUpdateRequest() {
        UpdateRuleStepRequest request = new UpdateRuleStepRequest();
        request.setLabel("Active-flag test pricing rule (updated)");
        request.setRuleStepType(RuleStepType.PERCENT_DISCOUNT_ON_SUM);
        request.setStepBase(StepBase.SUM_BEFORE_DISCOUNTS);
        request.setPercent(new BigDecimal("6.00"));
        request.setPriority(10);
        return request;
    }

    private CreateValidationRuleRequest validationCreateRequest(String ruleId) {
        CreateValidationRuleRequest request = new CreateValidationRuleRequest();
        request.setRuleId(ruleId);
        request.setLabel("Active-flag test validation rule");
        request.setValidationType(ValidationType.NOTES_REQUIRED);
        request.setRequired(true);
        request.setPriority(10);
        return request;
    }

    private UpdateValidationRuleRequest validationUpdateRequest() {
        UpdateValidationRuleRequest request = new UpdateValidationRuleRequest();
        request.setLabel("Active-flag test validation rule (updated)");
        request.setValidationType(ValidationType.NOTES_REQUIRED);
        request.setRequired(true);
        request.setPriority(10);
        return request;
    }

    private CreateRateAdjustmentRequest rateAdjustmentCreateRequest(String ruleId) {
        CreateRateAdjustmentRequest request = new CreateRateAdjustmentRequest();
        request.setRuleId(ruleId);
        request.setLabel("Active-flag test rate adjustment");
        request.setAdjustmentType(AdjustmentType.FIXED_ADJUSTMENT);
        request.setAdjustmentPercent(new BigDecimal("3.00"));
        request.setEffectiveDate(LocalDate.of(2026, 1, 1));
        request.setPriority(10);
        return request;
    }

    private UpdateRateAdjustmentRequest rateAdjustmentUpdateRequest() {
        UpdateRateAdjustmentRequest request = new UpdateRateAdjustmentRequest();
        request.setLabel("Active-flag test rate adjustment (updated)");
        request.setAdjustmentType(AdjustmentType.FIXED_ADJUSTMENT);
        request.setAdjustmentPercent(new BigDecimal("4.00"));
        request.setEffectiveDate(LocalDate.of(2026, 1, 1));
        request.setPriority(10);
        return request;
    }

    private void persistContractType(String code) {
        ContractTypeDefinition contractType = new ContractTypeDefinition();
        contractType.setCode(code);
        contractType.setName("Active-flag test type");
        contractType.persist();
    }

    private String uniqueCode() {
        return "ZZACTIVE" + (System.nanoTime() % 1_000_000_000L);
    }
}
