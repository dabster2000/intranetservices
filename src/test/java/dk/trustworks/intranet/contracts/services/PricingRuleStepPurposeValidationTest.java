package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.pricing.RulePurpose;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.contracts.dto.BulkCreateRulesRequest;
import dk.trustworks.intranet.contracts.dto.CreateRuleStepRequest;
import dk.trustworks.intranet.contracts.dto.PricingRuleStepDTO;
import dk.trustworks.intranet.contracts.dto.UpdateRuleStepRequest;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 rule-type consolidation (spec §8.1/§8.2 step 3): retired types are rejected on
 * create/update with 400, the purpose tag is PERCENT_DISCOUNT_ON_SUM-only, and purpose
 * round-trips through create → read → update. All writes roll back via {@code @TestTransaction}.
 */
@QuarkusTest
@SuppressWarnings("deprecation") // ADMIN_FEE_PERCENT / ROUNDING referenced to assert their rejection
class PricingRuleStepPurposeValidationTest {

    @Inject
    PricingRuleStepService service;

    // --- Retired types rejected (create) ---

    @Test
    @TestTransaction
    void create_adminFeePercent_isRejectedWithMigrationHint() {
        String code = persistContractType();
        CreateRuleStepRequest request = percentRule("legacy-admin", new BigDecimal("4.00"), null, null);
        request.setRuleStepType(RuleStepType.ADMIN_FEE_PERCENT);

        BadRequestException ex = assertThrows(BadRequestException.class, () -> service.createRule(code, request));
        assertTrue(ex.getMessage().contains("not supported — use PERCENT_DISCOUNT_ON_SUM with purpose ADMIN_FEE"),
                "message must point at the replacement, was: " + ex.getMessage());
    }

    @Test
    @TestTransaction
    void create_rounding_isRejected() {
        String code = persistContractType();
        CreateRuleStepRequest request = percentRule("rounding-rule", null, null, null);
        request.setRuleStepType(RuleStepType.ROUNDING);

        BadRequestException ex = assertThrows(BadRequestException.class, () -> service.createRule(code, request));
        assertTrue(ex.getMessage().contains("not supported"), "was: " + ex.getMessage());
    }

    @Test
    @TestTransaction
    void bulkCreate_rejectsRetiredTypes() {
        String code = persistContractType();
        CreateRuleStepRequest valid = percentRule("bulk-ok", new BigDecimal("5.00"), null, RulePurpose.DISCOUNT);
        CreateRuleStepRequest retired = percentRule("bulk-rounding", null, null, null);
        retired.setRuleStepType(RuleStepType.ROUNDING);

        BulkCreateRulesRequest bulk = new BulkCreateRulesRequest(List.of(valid, retired));
        assertThrows(BadRequestException.class, () -> service.createRulesBulk(code, bulk));
    }

    // --- Retired types rejected (update) ---

    @Test
    @TestTransaction
    void update_toRetiredTypes_isRejected() {
        String code = persistContractType();
        service.createRule(code, percentRule("mutate-me", new BigDecimal("5.00"), null, null));

        UpdateRuleStepRequest toAdminFee = updateRequest(RuleStepType.ADMIN_FEE_PERCENT, new BigDecimal("5.00"), null, null);
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.updateRule(code, "mutate-me", toAdminFee));
        assertTrue(ex.getMessage().contains("not supported — use PERCENT_DISCOUNT_ON_SUM with purpose ADMIN_FEE"));

        UpdateRuleStepRequest toRounding = updateRequest(RuleStepType.ROUNDING, null, null, null);
        assertThrows(BadRequestException.class, () -> service.updateRule(code, "mutate-me", toRounding));
    }

    // --- percent / paramKey integrity for PERCENT_DISCOUNT_ON_SUM ---

    @Test
    @TestTransaction
    void create_percentDiscount_requiresPercentOrParamKey() {
        String code = persistContractType();

        assertThrows(BadRequestException.class,
                () -> service.createRule(code, percentRule("neither", null, null, null)),
                "neither percent nor paramKey must be rejected");

        assertEquals(new BigDecimal("5.00"),
                service.createRule(code, percentRule("percent-only", new BigDecimal("5.00"), null, null)).getPercent());
        assertEquals("trapperabat",
                service.createRule(code, percentRule("paramkey-only", null, "trapperabat", null)).getParamKey());

        // Both together are allowed — percent is the engine fallback when the contract lacks the paramKey
        PricingRuleStepDTO both = service.createRule(code, percentRule("both-set", new BigDecimal("8.00"), "trapperabat", null));
        assertEquals(new BigDecimal("8.00"), both.getPercent());
        assertEquals("trapperabat", both.getParamKey());
    }

    // --- purpose is PERCENT_DISCOUNT_ON_SUM-only ---

    @Test
    @TestTransaction
    void create_purposeOnOtherTypes_isRejected() {
        String code = persistContractType();

        CreateRuleStepRequest fixedWithPurpose = percentRule("fee-with-purpose", null, null, RulePurpose.DISCOUNT);
        fixedWithPurpose.setRuleStepType(RuleStepType.FIXED_DEDUCTION);
        fixedWithPurpose.setAmount(new BigDecimal("2000.00"));
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.createRule(code, fixedWithPurpose));
        assertTrue(ex.getMessage().contains("'purpose' can only be set on PERCENT_DISCOUNT_ON_SUM"));

        CreateRuleStepRequest generalWithPurpose = percentRule("general-with-purpose", null, null, RulePurpose.ADMIN_FEE);
        generalWithPurpose.setRuleStepType(RuleStepType.GENERAL_DISCOUNT_PERCENT);
        assertThrows(BadRequestException.class, () -> service.createRule(code, generalWithPurpose));
    }

    @Test
    @TestTransaction
    void create_generalDiscount_requiresNeitherPercentNorParamKeyNorPurpose() {
        String code = persistContractType();
        CreateRuleStepRequest general = percentRule("general", null, null, null);
        general.setRuleStepType(RuleStepType.GENERAL_DISCOUNT_PERCENT);

        PricingRuleStepDTO dto = service.createRule(code, general);
        assertEquals(RuleStepType.GENERAL_DISCOUNT_PERCENT, dto.getRuleStepType());
        assertNull(dto.getPurpose(), "placement rows carry no purpose");
    }

    // --- purpose round-trip create → read → update ---

    @Test
    @TestTransaction
    void purpose_roundTrips_throughCreateReadAndUpdate() {
        String code = persistContractType();

        PricingRuleStepDTO created = service.createRule(code,
                percentRule("admin-fee", new BigDecimal("4.00"), null, RulePurpose.ADMIN_FEE));
        assertEquals(RulePurpose.ADMIN_FEE, created.getPurpose());
        assertEquals(RuleStepType.PERCENT_DISCOUNT_ON_SUM, created.getRuleStepType());

        assertEquals(RulePurpose.ADMIN_FEE, service.getRule(code, "admin-fee").getPurpose());

        // Update purpose ADMIN_FEE -> DISCOUNT
        PricingRuleStepDTO retagged = service.updateRule(code, "admin-fee",
                updateRequest(RuleStepType.PERCENT_DISCOUNT_ON_SUM, new BigDecimal("4.00"), null, RulePurpose.DISCOUNT));
        assertEquals(RulePurpose.DISCOUNT, retagged.getPurpose());
        assertEquals(RulePurpose.DISCOUNT, service.getRule(code, "admin-fee").getPurpose());

        // Update purpose -> null clears the tag
        PricingRuleStepDTO cleared = service.updateRule(code, "admin-fee",
                updateRequest(RuleStepType.PERCENT_DISCOUNT_ON_SUM, new BigDecimal("4.00"), null, null));
        assertNull(cleared.getPurpose());
        assertNull(service.getRule(code, "admin-fee").getPurpose());
    }

    // --- legacy in-flight rows (between V395 and V396): type implies ADMIN_FEE ---

    @Test
    @TestTransaction
    void dto_impliesAdminFeePurpose_forLegacyAdminFeePercentRows() {
        String code = persistContractType();

        // Old rows written before the V396 retype: type ADMIN_FEE_PERCENT, purpose NULL.
        // They bypass create-validation by design (only create/update is blocked).
        PricingRuleStepEntity legacy = new PricingRuleStepEntity();
        legacy.setContractTypeCode(code);
        legacy.setRuleId("legacy-in-flight");
        legacy.setLabel("4% SKI administrationsgebyr");
        legacy.setRuleStepType(RuleStepType.ADMIN_FEE_PERCENT);
        legacy.setStepBase(StepBase.CURRENT_SUM);
        legacy.setPercent(new BigDecimal("4.00"));
        legacy.setPriority(20);
        legacy.persist();

        PricingRuleStepDTO dto = service.getRule(code, "legacy-in-flight");
        assertEquals(RuleStepType.ADMIN_FEE_PERCENT, dto.getRuleStepType(), "type is reported as stored");
        assertEquals(RulePurpose.ADMIN_FEE, dto.getPurpose(),
                "spec §8.2 rollout fallback: ADMIN_FEE_PERCENT implies purpose ADMIN_FEE");
    }

    // --- helpers ---

    private CreateRuleStepRequest percentRule(String ruleId, BigDecimal percent, String paramKey, RulePurpose purpose) {
        CreateRuleStepRequest request = new CreateRuleStepRequest();
        request.setRuleId(ruleId);
        request.setLabel("Purpose validation rule " + ruleId);
        request.setRuleStepType(RuleStepType.PERCENT_DISCOUNT_ON_SUM);
        request.setStepBase(StepBase.CURRENT_SUM);
        request.setPercent(percent);
        request.setParamKey(paramKey);
        request.setPurpose(purpose);
        return request;
    }

    private UpdateRuleStepRequest updateRequest(RuleStepType type, BigDecimal percent, String paramKey, RulePurpose purpose) {
        UpdateRuleStepRequest request = new UpdateRuleStepRequest();
        request.setLabel("Updated purpose validation rule");
        request.setRuleStepType(type);
        request.setStepBase(StepBase.CURRENT_SUM);
        request.setPercent(percent);
        request.setParamKey(paramKey);
        request.setPurpose(purpose);
        request.setPriority(20);
        return request;
    }

    private String persistContractType() {
        String code = "ZZPURPOSE" + (System.nanoTime() % 1_000_000_000L);
        ContractTypeDefinition contractType = new ContractTypeDefinition();
        contractType.setCode(code);
        contractType.setName("Purpose validation test type");
        contractType.persist();
        return code;
    }
}
